package com.platform.carbon;

import com.platform.carbon.CarbonModels.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
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

    record ChangeLease(DependencyChange change, String token) { }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    ChangeLease claimChange() {
        LocalDateTime now = LocalDateTime.now();
        repository.recoverExpiredChanges(now, properties.getMaximumRetries());
        DependencyChange value = repository.findPendingChangeForUpdate(now);
        String token = id();
        if (value == null || repository.claimChange(value.changeId(), token,
                now.plus(properties.getRecalculationLease())) != 1) return null;
        return new ChangeLease(value, token);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    List<String> materialize(ChangeLease lease) {
        DependencyChange change = lease.change();
        LocalDateTime now = LocalDateTime.now();
        if (!repository.lockChange(change.changeId(), lease.token(), now)) return List.of();
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
        // 已冻结或边界不同的活动项仍占有建筑年度锁；保留原变化等待后继批次，不能吞入旧审批依据。
        for (CalculationBatch oldResult : impacted) {
            int year = oldResult.periodStart().atZone(ZoneId.of(oldResult.timezoneId())).getYear();
            String active = repository.findActiveBatchForItem(oldResult.buildingId(), year, oldResult.resultNature());
            if (active != null && !repository.canMerge(active, mergeKey(change, oldResult))) {
                repository.deferChange(change.changeId(), lease.token(), now.plus(properties.getRetryBackoff()));
                return List.of();
            }
        }
        String triggerId = id();
        String triggerFingerprint = CarbonCalculationCore.sha256("TRIGGER|"
                + change.changeFingerprint());
        if (impacted.isEmpty()) {
            repository.insertTrigger(triggerId, change, triggerFingerprint, 0,
                    "NO_IMPACT", now);
            repository.completeChange(change.changeId(), lease.token(), now);
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
                if (!repository.canMerge(activeBatch, mergeKey(change, oldResult))) {
                    throw CarbonErrors.error(409, CarbonErrors.RECALCULATION_CONFLICT, "批次范围已冻结");
                }
                repository.attachTrigger(activeBatch, triggerId);
                repository.setMergeWindow(activeBatch, mergeKey(change, oldResult),
                        now.plus(properties.getRecalculationMergeWindow()));
                if (!batchIds.contains(activeBatch)) batchIds.add(activeBatch);
                continue;
            }
            // 影响查询与建项之间可能恰逢审批发布；旧基线已替代时回滚本次分析，重新读取后继结果。
            if (!repository.lockCurrentResult(oldResult.batchId())) {
                throw CarbonErrors.error(409, CarbonErrors.RECALCULATION_CONFLICT, "原结果已被替代，需要重新分析影响");
            }
            String boundary = boundary(change, oldResult);
            RecalculationBatch batch = repository.findMergeableBatch(mergeKey(change, oldResult),
                    properties.getMaximumBatchItems());
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
            repository.setMergeWindow(batch.batchId(), mergeKey(change, oldResult),
                    now.plus(properties.getRecalculationMergeWindow()));
            if (repository.insertItem(batch.batchId(), oldResult, year) == 1) {
                repository.refreshBatchItemCount(batch.batchId());
            }
            if (!batchIds.contains(batch.batchId())) batchIds.add(batch.batchId());
        }
        repository.completeChange(change.changeId(), lease.token(), now);
        return List.copyOf(batchIds);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void failChange(ChangeLease lease) {
        repository.failChange(lease.change().changeId(), lease.token(), properties.getMaximumRetries(),
                LocalDateTime.now().plus(properties.getRetryBackoff()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    RecalculationBatch claimBatch() {
        LocalDateTime now = LocalDateTime.now();
        RecalculationBatch expired = repository.findExpiredBatchForUpdate(now);
        if (expired != null) {
            repository.recoverExpiredItems(expired.batchId(), now, properties.getMaximumRetries());
            repository.finishCalculationPhase(expired.batchId(), expired.resultNature(), now);
        }
        RecalculationBatch value = repository.findClaimableBatchForUpdate(now);
        if (value == null) return null;
        String token = id();
        if (repository.claimBatch(value.batchId(), token,
                now.plus(properties.getRecalculationLease()), now) != 1) return null;
        return repository.findBatch(value.batchId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean startItem(String itemId, String batchId, String token) {
        if (!repository.lockLease(batchId, token, LocalDateTime.now())) return false;
        return repository.startItem(itemId, batchId) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    void succeedItem(RecalculationItem item, String candidateBatchId, String token) {
        if (!repository.lockLease(item.batchId(), token, LocalDateTime.now())) return;
        RecalculationItem current = repository.findItem(item.itemId());
        if (!"CALCULATING".equals(current.status()) || current.retryCount() != item.retryCount()) return;
        if (repository.findBatch(item.batchId()).resultNature() == ResultNature.FORMAL) {
            repository.registerCandidate(item.batchId(), item.buildingId(),
                    item.oldCalculationBatchId(), candidateBatchId);
        }
        repository.succeedItem(item.itemId(), candidateBatchId, LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void failItem(RecalculationItem item, String code, String message, String token) {
        if (!repository.lockLease(item.batchId(), token, LocalDateTime.now())) return;
        int retries = item.retryCount() + 1;
        boolean dead = retries > properties.getMaximumRetries();
        repository.failItem(item.itemId(), dead, retries,
                dead ? null : LocalDateTime.now().plus(properties.getRetryBackoff()),
                code, message, LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void finishCalculationPhase(String batchId, ResultNature nature, String token) {
        if (!repository.lockLease(batchId, token, LocalDateTime.now())) return;
        repository.finishCalculationPhase(batchId, nature, LocalDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean renewLease(String batchId, String token) {
        LocalDateTime now = LocalDateTime.now();
        if (!repository.lockLease(batchId, token, now)) return false;
        repository.renewLease(batchId, token, now.plus(properties.getRecalculationLease()));
        return true;
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

    private static String boundary(DependencyChange change, CalculationBatch result) {
        return change.organizationBoundary() == null ? "BUILDING:" + result.buildingId()
                : change.organizationBoundary();
    }

    private static String mergeKey(DependencyChange change, CalculationBatch result) {
        return CarbonCalculationCore.sha256(change.changeType() + "|" + boundary(change, result)
                + "|" + result.resultNature() + "|" + change.effectiveFrom() + "|" + change.effectiveTo()
                + ("MANUAL".equals(change.changeType()) ? "|" + change.changeDetail() : ""));
    }

    private static final class SetLike {
        private SetLike() {
        }

        static boolean factor(String changeType) {
            return "FACTOR".equals(changeType) || "MISSING_FACTOR_FILLED".equals(changeType);
        }
    }
}
