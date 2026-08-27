package com.platform.audit.retention;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceErrors;
import com.platform.audit.AuditGovernanceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
/** 按批准策略自动执行有界批次；失败批次保留证据并由下一次调度从剩余最早记录重试。 */
public class AuditCleanupService {
    private static final Logger log = LoggerFactory.getLogger(AuditCleanupService.class);
    private static final String SYSTEM_ACTOR = "AUDIT_RETENTION_SCHEDULER";

    private final AuditRetentionPolicyRepository policyRepository;
    private final AuditCleanupRunRepository runRepository;
    private final Map<String, AuditRetentionHandler> handlers;
    private final AuditEvidenceWriter evidenceWriter;
    private final AuditGovernanceProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final AuditCleanupConcurrencyGuard cleanupGuard;

    public AuditCleanupService(
            AuditRetentionPolicyRepository policyRepository,
            AuditCleanupRunRepository runRepository,
            List<AuditRetentionHandler> handlers,
            AuditEvidenceWriter evidenceWriter,
            AuditGovernanceProperties properties,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            AuditCleanupConcurrencyGuard cleanupGuard) {
        this.policyRepository = policyRepository;
        this.runRepository = runRepository;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
                AuditRetentionHandler::sourceModule, Function.identity()));
        this.evidenceWriter = evidenceWriter;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.cleanupGuard = cleanupGuard;
    }

    public void runAutomatic() {
        LocalDateTime now = LocalDateTime.now();
        for (AuditRetentionPolicy policy : policyRepository.findDueCurrent(now)) {
            executePolicy(policy, now, id());
        }
    }

    public void runApprovedExceptions() {
        runPendingExceptions();
    }

    public AuditExceptionCleanupCommand normalizeException(AuditExceptionCleanupCommand value) {
        if (value == null || value.scope() == null) {
            throw DeleteAuditEvidenceExceptionHandler.invalid("例外删除范围不能为空");
        }
        String source = DeleteAuditEvidenceExceptionHandler.required(
                value.sourceModule(), 50, "例外删除来源无效").toUpperCase(Locale.ROOT);
        if (!handlers.containsKey(source)) {
            throw DeleteAuditEvidenceExceptionHandler.invalid("例外删除来源无效");
        }
        AuditRetentionScope raw = value.scope();
        AuditRetentionScope scope = new AuditRetentionScope(
                optional(raw.auditId(), 32), optional(raw.buildingId(), 32), key(raw.actionType()),
                key(raw.objectType()), optional(raw.objectId(), 128), raw.fromTime(), raw.toTime());
        if ((scope.objectType() == null) != (scope.objectId() == null)) {
            throw DeleteAuditEvidenceExceptionHandler.invalid("对象类型和对象编号必须同时指定");
        }
        if (scope.fromTime() != null && scope.toTime() != null
                && !scope.fromTime().isBefore(scope.toTime())) {
            throw DeleteAuditEvidenceExceptionHandler.invalid("例外删除时间范围无效");
        }
        if (scope.auditId() == null && scope.buildingId() == null && scope.actionType() == null
                && scope.objectId() == null && scope.fromTime() == null && scope.toTime() == null) {
            throw DeleteAuditEvidenceExceptionHandler.invalid("例外删除必须指定有界范围");
        }
        if (value.expectedCount() < 0 || value.allowedCountDifference() < 0) {
            throw DeleteAuditEvidenceExceptionHandler.invalid("预计数量和容差不能为负数");
        }
        return new AuditExceptionCleanupCommand(DeleteAuditEvidenceExceptionHandler.mode(value.mode()),
                keyRequired(value.dataCategory(), 64, "数据类别无效"), source, scope,
                DeleteAuditEvidenceExceptionHandler.required(value.reason(), 500, "例外删除原因无效"),
                DeleteAuditEvidenceExceptionHandler.required(value.legalBasis(), 500, "例外删除依据无效"),
                value.expectedCount(), value.allowedCountDifference());
    }

    public void enqueueException(
            AuditExceptionCleanupCommand command, String canonicalJson,
            String requestId, long reviewerId) {
        AuditRetentionHandler handler = handlers.get(command.sourceModule());
        AuditRetentionPreview preview = handler.preview(command.scope());
        verifyExpectedCount(command, preview.eligibleCount());
        LocalDateTime now = LocalDateTime.now();
        runRepository.insert(new AuditCleanupRun(id(), "EXCEPTION", null, null,
                command.dataCategory(), command.sourceModule(), command.scope().buildingId(),
                command.scope().toTime(), preview.candidateCount(), 0, preview.heldCount(),
                preview.protectedCount(), preview.earliestOperationTime(), preview.latestOperationTime(),
                "PENDING", null, "AUDIT_EXCEPTION_CLEANUP_WORKER", reviewerId, requestId,
                id(), null, now, null), canonicalJson);
    }

    private void runPendingExceptions() {
        for (AuditCleanupRunRepository.PendingExceptionRun run
                : runRepository.findPendingExceptions(properties.getRetentionCleanupMaxBatches())) {
            if (!runRepository.claim(run.runId())) continue;
            executeException(run);
        }
    }

    private void executeException(AuditCleanupRunRepository.PendingExceptionRun run) {
        AuditRetentionHandler handler = handlers.get(run.sourceModule());
        try {
            AuditExceptionCleanupCommand command = objectMapper.readValue(
                    run.scopeJson(), AuditExceptionCleanupCommand.class);
            AuditRetentionPreview preview = handler.preview(command.scope());
            verifyExpectedCount(command, preview.eligibleCount() + run.deletedCount());
            long deleted = run.deletedCount();
            LocalDateTime earliest = run.earliestOperationTime();
            LocalDateTime latest = run.latestOperationTime();
            MessageDigest manifest = sha256();
            if (run.manifestSha256() != null) {
                manifest.update(run.manifestSha256().getBytes(StandardCharsets.UTF_8));
            }
            for (int batch = 0; batch < properties.getRetentionCleanupMaxBatches(); batch++) {
                AuditCleanupBatch value = transactionTemplate.execute(status -> {
                    cleanupGuard.lock();
                    return handler.deleteNextBatch(command.scope(), properties.getRetentionCleanupBatchSize());
                });
                if (value == null || value.deletedCount() == 0) break;
                deleted += value.deletedCount();
                if (earliest == null || value.earliestOperationTime().isBefore(earliest)) earliest = value.earliestOperationTime();
                if (latest == null || value.latestOperationTime().isAfter(latest)) latest = value.latestOperationTime();
                manifest.update(value.manifestSha256().getBytes(StandardCharsets.UTF_8));
            }
            boolean remaining = handler.preview(command.scope()).eligibleCount() > 0;
            String outcome = remaining ? "PARTIAL" : "SUCCEEDED";
            String digest = deleted == 0 ? null : HexFormat.of().formatHex(manifest.digest());
            runRepository.finish(run.runId(), outcome, deleted, earliest, latest, digest,
                    null, LocalDateTime.now());
            appendExceptionEvidence(run, outcome, deleted, preview);
        } catch (RuntimeException | JsonProcessingException failure) {
            runRepository.finish(run.runId(), "FAILED", run.deletedCount(), run.earliestOperationTime(),
                    run.latestOperationTime(), run.manifestSha256(),
                    failure instanceof com.platform.framework.exception.BusinessException
                            ? AuditGovernanceErrors.CLEANUP_SCOPE_CHANGED : "AUDIT_CLEANUP_FAILED",
                    LocalDateTime.now());
            log.error("审计例外清理失败 runId={} sourceModule={} traceId={}",
                    run.runId(), run.sourceModule(), run.traceId(), failure);
        }
    }

    void executePolicy(AuditRetentionPolicy policy, LocalDateTime now, String traceId) {
        AuditRetentionHandler handler = handlers.get(policy.sourceModule());
        if (handler == null) {
            throw new IllegalStateException("批准策略引用了未登记审计源");
        }
        LocalDateTime cutoff = now.minus(Period.parse(policy.retentionPeriod()));
        AuditRetentionScope scope = AuditRetentionScope.expiredBefore(cutoff);
        AuditRetentionPreview preview = handler.preview(scope);
        String runId = id();
        runRepository.insert(new AuditCleanupRun(runId, "AUTOMATIC", policy.policyId(),
                policy.policyVersion(), policy.dataCategory(), policy.sourceModule(), null, cutoff,
                preview.candidateCount(), 0, preview.heldCount(), preview.protectedCount(),
                preview.earliestOperationTime(), preview.latestOperationTime(), "RUNNING", null,
                SYSTEM_ACTOR, null, null, traceId, null, now, null));

        long deleted = 0;
        LocalDateTime earliest = null;
        LocalDateTime latest = null;
        MessageDigest manifest = sha256();
        try {
            for (int batch = 0; batch < properties.getRetentionCleanupMaxBatches(); batch++) {
                AuditCleanupBatch value = transactionTemplate.execute(status -> {
                    cleanupGuard.lock();
                    return handler.deleteNextBatch(scope, properties.getRetentionCleanupBatchSize());
                });
                if (value == null || value.deletedCount() == 0) break;
                deleted += value.deletedCount();
                if (earliest == null || value.earliestOperationTime().isBefore(earliest)) {
                    earliest = value.earliestOperationTime();
                }
                if (latest == null || value.latestOperationTime().isAfter(latest)) {
                    latest = value.latestOperationTime();
                }
                manifest.update(value.manifestSha256().getBytes(StandardCharsets.UTF_8));
            }
            boolean remaining = handler.preview(scope).eligibleCount() > 0;
            String outcome = remaining ? "PARTIAL" : "SUCCEEDED";
            String digest = deleted == 0 ? null : HexFormat.of().formatHex(manifest.digest());
            runRepository.finish(runId, outcome, deleted, earliest, latest, digest, null, LocalDateTime.now());
            appendRunEvidence(policy, runId, traceId, outcome, deleted, preview);
        } catch (RuntimeException failure) {
            runRepository.finish(runId, "FAILED", deleted, earliest, latest,
                    deleted == 0 ? null : HexFormat.of().formatHex(manifest.digest()),
                    "AUDIT_CLEANUP_FAILED", LocalDateTime.now());
            appendRunEvidence(policy, runId, traceId, "FAILED", deleted, preview);
            log.error("审计清理批次失败 runId={} sourceModule={} traceId={}",
                    runId, policy.sourceModule(), traceId, failure);
        }
    }

    private void appendRunEvidence(AuditRetentionPolicy policy, String runId, String traceId,
                                   String outcome, long deleted, AuditRetentionPreview preview) {
        evidenceWriter.append(new AuditEvidence("SYSTEM_SECURITY", null, "SYSTEM", null,
                "AUDIT_RETENTION_CLEANUP", "AUDIT_CLEANUP_RUN", runId,
                Integer.toString(policy.policyVersion()), policy.requestId(),
                "source=" + policy.sourceModule() + ";candidate=" + preview.candidateCount()
                        + ";held=" + preview.heldCount() + ";protected=" + preview.protectedCount(),
                "deleted=" + deleted + ";outcome=" + outcome,
                "FAILED".equals(outcome) ? "FAILED" : "SUCCESS",
                "FAILED".equals(outcome) ? "AUDIT_CLEANUP_FAILED" : null,
                traceId, LocalDateTime.now(), properties.getEnvironmentMode(), false));
    }

    private void appendExceptionEvidence(
            AuditCleanupRunRepository.PendingExceptionRun run, String outcome,
            long deleted, AuditRetentionPreview preview) {
        evidenceWriter.append(new AuditEvidence("SYSTEM_SECURITY", null, "SYSTEM", null,
                "AUDIT_EXCEPTION_CLEANUP", "AUDIT_CLEANUP_RUN", run.runId(), null,
                run.triggerRequestId(), "source=" + run.sourceModule() + ";candidate="
                        + preview.candidateCount() + ";held=" + preview.heldCount()
                        + ";protected=" + preview.protectedCount(),
                "deleted=" + deleted + ";outcome=" + outcome, "SUCCESS", null,
                run.traceId(), LocalDateTime.now(), properties.getEnvironmentMode(), false));
    }

    private static void verifyExpectedCount(AuditExceptionCleanupCommand command, long actual) {
        long difference = actual >= command.expectedCount()
                ? actual - command.expectedCount() : command.expectedCount() - actual;
        if (difference > command.allowedCountDifference()) {
            throw AuditGovernanceErrors.conflict(AuditGovernanceErrors.CLEANUP_SCOPE_CHANGED,
                    "例外删除范围数量已超过审核容差，必须重新申请");
        }
    }

    private static String key(String value) {
        if (value == null || value.isBlank()) return null;
        return keyRequired(value, 64, "范围键无效");
    }

    private static String keyRequired(String value, int max, String message) {
        String normalized = DeleteAuditEvidenceExceptionHandler.required(value, max, message)
                .toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{1," + max + "}")) {
            throw DeleteAuditEvidenceExceptionHandler.invalid(message);
        }
        return normalized;
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return DeleteAuditEvidenceExceptionHandler.required(value, max, "例外删除范围无效");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
