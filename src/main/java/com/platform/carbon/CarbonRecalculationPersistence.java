package com.platform.carbon;

import com.platform.carbon.CarbonModels.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
/** 为影响分析、数据库租约、单项结果和批次发布提供独立短事务。 */
class CarbonRecalculationPersistence {
    private final CarbonRecalculationRepository repository;
    private final CarbonRuleRepository ruleRepository;
    private final CarbonProperties properties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    DependencyChange claimChange() {
        DependencyChange value = repository.findPendingChangeForUpdate();
        if (value == null || repository.claimChange(value.changeId()) != 1) return null;
        return value;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    List<String> materialize(DependencyChange change) {
        int limit = Math.min(10_001, properties.getMaximumBatchItems() * 100 + 1);
        List<CalculationBatch> impacted = repository.findImpactedResults(change, limit);
        if (impacted.size() >= limit) {
            throw CarbonErrors.error(409, CarbonErrors.LIMIT_EXCEEDED,
                    "单次依赖变化影响范围超过自动分析硬上限，必须拆分处理");
        }
        if (SetLike.factor(change.changeType())) {
            CarbonRuleRepository.FactorIdentity factor =
                    ruleRepository.findFactorIdentity(change.sourceObjectId());
            if (factor != null) {
                impacted = impacted.stream().filter(value -> repository.batchTouchesEnergyItem(
                        value.batchId(), factor.energyItemCode())).toList();
            }
        }
        String triggerId = id();
        String triggerFingerprint = CarbonCalculationCore.sha256("TRIGGER|"
                + change.changeFingerprint());
        LocalDateTime now = LocalDateTime.now();
        if (impacted.isEmpty()) {
            repository.insertTrigger(triggerId, change, triggerFingerprint, 0,
                    "NO_IMPACT", now);
            repository.completeChange(change.changeId(), true, now);
            return List.of();
        }
        repository.insertTrigger(triggerId, change, triggerFingerprint, impacted.size(),
                "IMPACTED", now);
        List<String> batchIds = new ArrayList<>();
        for (CalculationBatch oldResult : impacted) {
            int year = oldResult.periodStart().atZone(ZoneId.of(oldResult.timezoneId())).getYear();
            String activeBatch = repository.findActiveBatchForItem(oldResult.buildingId(), year,
                    oldResult.resultNature());
            if (activeBatch != null) {
                repository.attachTrigger(activeBatch, triggerId);
                if (!batchIds.contains(activeBatch)) batchIds.add(activeBatch);
                continue;
            }
            String boundary = change.organizationBoundary() == null
                    ? "AUTO_PERMISSION_SCOPE" : change.organizationBoundary();
            RecalculationBatch batch = repository.findMergeableBatch(change.changeType(), boundary,
                    oldResult.resultNature(), properties.getMaximumBatchItems());
            if (batch == null) {
                String batchId = id();
                batch = new RecalculationBatch(batchId,
                        CarbonCalculationCore.sha256(change.changeFingerprint() + '|'
                                + oldResult.resultNature() + '|' + batchId),
                        change.changeType(), boundary, oldResult.resultNature(),
                        "PENDING_CALCULATION", false, 0, 0, null, null,
                        null, null, change.triggeredBy(), null, null, null, null, now);
                repository.insertBatch(batch);
            }
            repository.attachTrigger(batch.batchId(), triggerId);
            if (repository.insertItem(batch.batchId(), oldResult, year) == 1) {
                repository.refreshBatchItemCount(batch.batchId());
            }
            if (!batchIds.contains(batch.batchId())) batchIds.add(batch.batchId());
        }
        repository.completeChange(change.changeId(), true, now);
        return List.copyOf(batchIds);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void failChange(String changeId) {
        repository.completeChange(changeId, false, LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    RecalculationBatch claimBatch() {
        LocalDateTime now = LocalDateTime.now();
        repository.recoverExpiredBatches(now, properties.getMaximumRetries());
        RecalculationBatch value = repository.findClaimableBatchForUpdate(now);
        if (value == null) return null;
        String token = id();
        if (repository.claimBatch(value.batchId(), token,
                now.plus(properties.getRecalculationLease()), now) != 1) return null;
        return repository.findBatch(value.batchId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean startItem(String itemId, String batchId) {
        return repository.startItem(itemId, batchId) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    void succeedItem(RecalculationItem item, String candidateBatchId) {
        if (repository.findBatch(item.batchId()).resultNature() == ResultNature.FORMAL) {
            repository.registerCandidate(item.batchId(), item.buildingId(),
                    item.oldCalculationBatchId(), candidateBatchId);
        }
        repository.succeedItem(item.itemId(), candidateBatchId, LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void failItem(RecalculationItem item, String code, String message) {
        int retries = item.retryCount() + 1;
        boolean dead = retries > properties.getMaximumRetries();
        repository.failItem(item.itemId(), dead, retries,
                dead ? null : LocalDateTime.now().plus(properties.getRetryBackoff()),
                code, message, LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void finishCalculationPhase(String batchId, ResultNature nature) {
        repository.finishCalculationPhase(batchId, nature, LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    void publish(String batchId, long reviewerId, String comment) {
        LocalDateTime now = LocalDateTime.now();
        if (repository.approveBatch(batchId, reviewerId, comment, now) != 1
                || repository.publishBatch(batchId, now) != 1) {
            throw CarbonErrors.error(409, CarbonErrors.APPROVAL_CONFLICT,
                    "重算批次状态或候选结果已变化，发布失败");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void reject(String batchId, long reviewerId, String comment) {
        if (repository.rejectBatch(batchId, reviewerId, comment,
                LocalDateTime.now()) != 1) {
            throw CarbonErrors.error(409, CarbonErrors.APPROVAL_CONFLICT,
                    "重算批次不是待审批状态");
        }
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static final class SetLike {
        private SetLike() {
        }

        static boolean factor(String changeType) {
            return "FACTOR".equals(changeType) || "MISSING_FACTOR_FILLED".equals(changeType);
        }
    }
}
