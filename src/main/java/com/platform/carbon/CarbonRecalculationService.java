package com.platform.carbon;

import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import com.platform.carbon.CarbonModels.*;
import com.platform.carbon.api.CarbonContracts.ApproveRecalculationRequest;
import com.platform.carbon.api.CarbonContracts.ManualRecalculationAcceptedView;
import com.platform.carbon.api.CarbonContracts.ManualRecalculationRequest;
import com.platform.carbon.api.CarbonContracts.RecoverDeadItemRequest;
import com.platform.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.platform.carbon.CarbonErrors.*;

@Service
@RequiredArgsConstructor
/** 自动分析依赖变化、领取持久化任务、计算候选结果并执行正式批次审批发布。 */
public class CarbonRecalculationService {
    private final CarbonAuthorization authorization;
    private final CarbonRuleRepository ruleRepository;
    private final CarbonRecalculationRepository repository;
    private final CarbonRecalculationPersistence persistence;
    private final CarbonCalculationService calculationService;
    private final CarbonProperties properties;
    private final AuditEvidenceWriter auditWriter;
    private final AuditGovernanceProperties auditProperties;

    @Transactional(rollbackFor = Exception.class)
    public ManualRecalculationAcceptedView submitManual(
            long userId, Collection<String> roles, ManualRecalculationRequest request) {
        String buildingId = text(request.buildingId(), 32, "建筑编码无效");
        authorization.requireRecalculationTrigger(userId, roles, buildingId);
        ResultNature nature = enumValue(ResultNature.class, request.resultNature(), "结果性质无效");
        String reason = text(request.reason(), 500, "重算原因无效");
        String organizationBoundary = text(request.organizationBoundary(), 100,
                "组织权限边界无效");
        int year = request.accountingYear();
        if (year < 2000 || year > 2200) validation("核算年度无效");
        String raw = "MANUAL|" + buildingId + '|' + year + '|' + nature + '|'
                + organizationBoundary + '|' + reason;
        String fingerprint = CarbonCalculationCore.sha256(raw);
        DependencyChange existing = ruleRepository.findDependencyChangeByFingerprint(fingerprint);
        if (existing != null) {
            return new ManualRecalculationAcceptedView(existing.changeId(), existing.status(),
                    buildingId, year);
        }
        String changeId = id();
        LocalDateTime now = LocalDateTime.now();
        try {
            ruleRepository.insertDependencyChange(changeId, "MANUAL", "CARBON_RESULT_YEAR",
                    buildingId + '-' + year, reason, null,
                    "MANUAL_" + fingerprint.substring(0, 24), fingerprint, buildingId,
                    organizationBoundary,
                    LocalDate.of(year, 1, 1).atStartOfDay(),
                    LocalDate.of(year + 1, 1, 1).atStartOfDay(), userId, now);
        } catch (DuplicateKeyException exception) {
            DependencyChange raced = ruleRepository.findDependencyChangeByFingerprint(fingerprint);
            if (raced == null) throw exception;
            changeId = raced.changeId();
        }
        audit(userId, buildingId, "CREATE_CARBON_RECALCULATION_TRIGGER", changeId,
                "year=" + year + ";nature=" + nature + ";reason=" + reason, false);
        return new ManualRecalculationAcceptedView(changeId, "PENDING", buildingId, year);
    }

    @Transactional(rollbackFor = Exception.class)
    public ManualRecalculationAcceptedView recoverDead(
            long userId, Collection<String> roles, String itemId,
            RecoverDeadItemRequest request) {
        RecalculationItem item = repository.findItem(text(itemId, 32, "重算项标识无效"));
        if (item == null) throw error(404, NOT_FOUND, "碳重算项不存在");
        if (!"DEAD".equals(item.status())) {
            throw error(409, RECALCULATION_CONFLICT, "只有 DEAD 重算项可以显式恢复");
        }
        RecalculationBatch batch = requireBatch(item.batchId());
        authorization.requireRecalculationTrigger(userId, roles, item.buildingId());
        String reason = "DEAD项人工处理后恢复:" + text(request.reason(), 450, "恢复原因无效")
                + ";sourceItem=" + item.itemId();
        return submitManual(userId, roles, new ManualRecalculationRequest(
                item.buildingId(), item.accountingYear(), batch.resultNature().name(),
                reason, batch.organizationBoundary()));
    }

    public RecalculationBatch detail(long userId, Collection<String> roles, String batchId) {
        RecalculationBatch value = requireBatch(batchId);
        repository.listBuildingIds(value.batchId()).forEach(buildingId ->
                authorization.requireReader(userId, roles, buildingId));
        return value;
    }

    public List<RecalculationItem> items(long userId, Collection<String> roles, String batchId) {
        RecalculationBatch batch = detail(userId, roles, batchId);
        return repository.listItems(batch.batchId());
    }

    public RecalculationBatch approve(long userId, Collection<String> roles, String batchId,
                                      ApproveRecalculationRequest request) {
        RecalculationBatch batch = requireBatch(batchId);
        if (!"PENDING_APPROVAL".equals(batch.status())
                || batch.resultNature() != ResultNature.FORMAL) {
            throw error(409, APPROVAL_CONFLICT, "批次不是待审批正式候选结果");
        }
        List<String> buildings = repository.listBuildingIds(batch.batchId());
        authorization.requireRecalculationApprover(userId, roles, buildings);
        if (batch.initiatedBy() != null) {
            authorization.requireSeparation(batch.initiatedBy(), userId);
        }
        repository.listResponsibleUsers(batch.batchId()).forEach(responsible ->
                authorization.requireSeparation(responsible, userId));
        String comment = text(request.reviewComment(), 500, "审批意见无效");
        persistence.publish(batch.batchId(), userId, comment);
        RecalculationBatch published = requireBatch(batchId);
        audit(userId, null, "APPROVE_AND_PUBLISH_CARBON_RECALCULATION", batchId,
                "items=" + batch.eligibleItemCount() + ";status=" + published.status(), false);
        return published;
    }

    public RecalculationBatch reject(long userId, Collection<String> roles, String batchId,
                                     ApproveRecalculationRequest request) {
        RecalculationBatch batch = requireBatch(batchId);
        List<String> buildings = repository.listBuildingIds(batch.batchId());
        authorization.requireRecalculationApprover(userId, roles, buildings);
        if (batch.initiatedBy() != null) authorization.requireSeparation(batch.initiatedBy(), userId);
        repository.listResponsibleUsers(batch.batchId()).forEach(responsible ->
                authorization.requireSeparation(responsible, userId));
        persistence.reject(batch.batchId(), userId,
                text(request.reviewComment(), 500, "审批意见无效"));
        RecalculationBatch rejected = requireBatch(batchId);
        audit(userId, null, "REJECT_CARBON_RECALCULATION", batchId,
                "status=" + rejected.status(), false);
        return rejected;
    }

    @Scheduled(
            initialDelayString = "#{@carbonProperties.recalculationScanDelay.toMillis()}",
            fixedDelayString = "#{@carbonProperties.recalculationScanDelay.toMillis()}")
    public void scheduledWork() {
        if (!properties.isRecalculationEnabled()) return;
        analyzeOne();
        executeOne();
    }

    void analyzeOne() {
        DependencyChange change = persistence.claimChange();
        if (change == null) return;
        try {
            List<String> batches = persistence.materialize(change);
            auditSystem(change.buildingId(), "ANALYZE_CARBON_RECALCULATION_IMPACT",
                    change.changeId(), "batches=" + batches.size() + ";detail="
                            + change.changeDetail());
        } catch (RuntimeException exception) {
            persistence.failChange(change.changeId());
            auditSystem(change.buildingId(), "FAIL_CARBON_RECALCULATION_IMPACT",
                    change.changeId(), "error=" + safeCode(exception));
        }
    }

    void executeOne() {
        RecalculationBatch batch = persistence.claimBatch();
        if (batch == null) return;
        for (RecalculationItem item : repository.listItems(batch.batchId())) {
            if (!List.of("PENDING", "FAILED_RETRYABLE").contains(item.status())
                    || (item.nextAttemptAt() != null
                    && item.nextAttemptAt().isAfter(LocalDateTime.now()))) continue;
            if (!persistence.startItem(item.itemId(), batch.batchId())) continue;
            try {
                String idempotency = "recalc:" + item.itemId() + ':' + item.retryCount();
                CalculationDetail candidate = calculationService.runCandidate(
                        item.buildingId(), item.accountingYear(), batch.resultNature(),
                        item.oldCalculationBatchId(), idempotency);
                if (!List.of("COMPLETED_COMPLETE", "COMPLETED_INCOMPLETE")
                        .contains(candidate.batch().status())) {
                    throw error(409, RECALCULATION_CONFLICT, "候选计算未形成完整终态");
                }
                persistence.succeedItem(item, candidate.batch().batchId());
            } catch (RuntimeException exception) {
                persistence.failItem(item, safeCode(exception), safeMessage(exception));
            }
        }
        persistence.finishCalculationPhase(batch.batchId(), batch.resultNature());
        RecalculationBatch completed = requireBatch(batch.batchId());
        auditSystem(null, "FINISH_CARBON_RECALCULATION_CALCULATION", batch.batchId(),
                "status=" + completed.status() + ";eligible="
                        + completed.eligibleItemCount());
    }

    private RecalculationBatch requireBatch(String batchId) {
        RecalculationBatch value = repository.findBatch(text(batchId, 32, "重算批次标识无效"));
        if (value == null) throw error(404, NOT_FOUND, "碳重算批次不存在");
        return value;
    }

    private void auditSystem(String buildingId, String action, String objectId, String after) {
        audit(0L, buildingId, action, objectId, after, false);
    }

    private void audit(long userId, String buildingId, String action, String objectId,
                       String after, boolean selfApproval) {
        auditWriter.append(new AuditEvidence("CARBON_MANAGEMENT", buildingId,
                userId == 0 ? "SYSTEM" : "USER", userId == 0 ? null : userId,
                action, "CARBON_RECALCULATION", objectId, null, null, null, after,
                "SUCCESS", null, TraceContext.current(), LocalDateTime.now(),
                auditProperties.getEnvironmentMode(), selfApproval));
    }

    private static String safeCode(Throwable failure) {
        return failure instanceof BusinessException business && business.getErrorCode() != null
                ? business.getErrorCode() : "CARBON_RECALCULATION_FAILED";
    }

    private static String safeMessage(Throwable failure) {
        String value = failure instanceof BusinessException ? failure.getMessage()
                : "碳重算执行失败";
        return value == null ? "碳重算执行失败"
                : value.substring(0, Math.min(500, value.length()));
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String message) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            validation(message);
            return null;
        }
    }

    private static String text(String value, int max, String message) {
        if (value == null || value.isBlank() || value.trim().length() > max) validation(message);
        return value.trim();
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void validation(String message) {
        throw error(400, VALIDATION_FAILED, message);
    }
}
