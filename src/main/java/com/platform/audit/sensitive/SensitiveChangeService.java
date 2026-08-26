package com.platform.audit.sensitive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceErrors;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import com.platform.audit.TraceContext;
import com.platform.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
/**
 * 通用系统敏感操作的申请、审核和执行编排。
 *
 * <p>只有代码注册处理器能解释并执行冻结命令；拥有自身审核状态机的领域模块不得接入本服务。</p>
 */
public class SensitiveChangeService {
    private static final String SOURCE_MODULE = "SYSTEM_SECURITY";

    private final SensitiveChangeRepository repository;
    private final SensitiveOperationRegistry registry;
    private final BackendDutyService dutyService;
    private final AuditEvidenceWriter auditWriter;
    private final AuditGovernanceProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Transactional(rollbackFor = Exception.class)
    public SensitiveChangeRecord createDraft(long userId, String operationCode, JsonNode command,
                                              String idempotencyKey) {
        dutyService.requireDuty(userId, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        requireText(idempotencyKey, "幂等键不能为空");
        SensitiveOperationHandler handler = registry.require(operationCode);
        NormalizedSensitiveCommand normalized = handler.normalize(command);
        String hash = hash(operationCode, normalized);
        SensitiveChangeRecord existing = repository.findByIdempotency(userId, idempotencyKey).orElse(null);
        if (existing != null) {
            if (!existing.requestSha256().equals(hash)) {
                throw AuditGovernanceErrors.conflict(AuditGovernanceErrors.REQUEST_CONFLICT, "幂等键请求内容不一致");
            }
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        SensitiveChangeRecord created = new SensitiveChangeRecord(id(), operationCode,
                SensitiveChangeStatus.DRAFT, normalized.buildingId(), normalized.targetType(), normalized.targetId(),
                normalized.canonicalJson(), hash, normalized.impactSummary(), userId, null, null, null,
                null, null, null, idempotencyKey, TraceContext.current(),
                properties.getEnvironmentMode().name(), false, now, now);
        try {
            repository.insert(created);
        } catch (DuplicateKeyException concurrentDuplicate) {
            SensitiveChangeRecord concurrent = repository.findByIdempotency(userId, idempotencyKey)
                    .orElseThrow(() -> concurrentDuplicate);
            if (!concurrent.requestSha256().equals(hash)) {
                throw AuditGovernanceErrors.conflict(
                        AuditGovernanceErrors.REQUEST_CONFLICT, "幂等键请求内容不一致");
            }
            return concurrent;
        }
        append(created, userId, "CREATE_SENSITIVE_CHANGE_DRAFT", "SUCCESS", null, false, null, "status=DRAFT");
        return require(created.requestId(), false);
    }

    @Transactional(rollbackFor = Exception.class)
    public SensitiveChangeRecord submit(long userId, String requestId) {
        dutyService.requireDuty(userId, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        SensitiveChangeRecord value = require(requestId, true);
        requireOwner(value, userId);
        requireStatus(value, SensitiveChangeStatus.DRAFT);
        verifyHash(value);
        LocalDateTime now = LocalDateTime.now();
        changed(repository.submit(requestId, now, TraceContext.current()));
        append(value, userId, "SUBMIT_SENSITIVE_CHANGE", "SUCCESS", null, false,
                "status=DRAFT", "status=PENDING_REVIEW");
        return require(requestId, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public SensitiveChangeRecord withdraw(long userId, String requestId) {
        dutyService.requireDuty(userId, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        SensitiveChangeRecord value = require(requestId, true);
        requireOwner(value, userId);
        if (value.status() != SensitiveChangeStatus.DRAFT
                && value.status() != SensitiveChangeStatus.PENDING_REVIEW) {
            throw conflict();
        }
        LocalDateTime now = LocalDateTime.now();
        changed(repository.withdraw(requestId, now, TraceContext.current()));
        append(value, userId, "WITHDRAW_SENSITIVE_CHANGE", "SUCCESS", null, false,
                "status=" + value.status(), "status=WITHDRAWN");
        return require(requestId, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public SensitiveChangeRecord approve(long reviewerId, String requestId, String comment) {
        dutyService.requireDuty(reviewerId, BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
        requireText(comment, "审核意见不能为空");
        SensitiveChangeRecord value = require(requestId, true);
        requireStatus(value, SensitiveChangeStatus.PENDING_REVIEW);
        dutyService.requireSeparation(value.submittedBy(), reviewerId);
        verifyHash(value);
        boolean selfApproval = value.submittedBy() == reviewerId;
        LocalDateTime now = LocalDateTime.now();
        changed(repository.approve(requestId, reviewerId, comment.trim(), now,
                TraceContext.current(), selfApproval));
        append(value, reviewerId, "APPROVE_SENSITIVE_CHANGE", "SUCCESS", null, selfApproval,
                "status=PENDING_REVIEW", "status=APPROVED");
        return require(requestId, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public SensitiveChangeRecord reject(long reviewerId, String requestId, String comment) {
        dutyService.requireDuty(reviewerId, BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
        requireText(comment, "审核意见不能为空");
        SensitiveChangeRecord value = require(requestId, true);
        requireStatus(value, SensitiveChangeStatus.PENDING_REVIEW);
        dutyService.requireSeparation(value.submittedBy(), reviewerId);
        verifyHash(value);
        boolean selfApproval = value.submittedBy() == reviewerId;
        LocalDateTime now = LocalDateTime.now();
        changed(repository.reject(requestId, reviewerId, comment.trim(), now,
                TraceContext.current(), selfApproval));
        append(value, reviewerId, "REJECT_SENSITIVE_CHANGE", "REJECTED", null, selfApproval,
                "status=PENDING_REVIEW", "status=REJECTED");
        return require(requestId, false);
    }

    public SensitiveChangeRecord execute(long reviewerId, String requestId) {
        dutyService.requireDuty(reviewerId, BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
        try {
            return Objects.requireNonNull(transactionTemplate.execute(status -> executeApproved(reviewerId, requestId)));
        } catch (HandlerExecutionException failure) {
            transactionTemplate.executeWithoutResult(status -> markExecutionFailed(reviewerId, requestId));
            throw new BusinessException(409, AuditGovernanceErrors.REQUEST_CONFLICT, "敏感操作执行失败");
        }
    }

    @Transactional(readOnly = true)
    public SensitiveChangeRecord detail(long userId, String requestId) {
        SensitiveChangeRecord value = require(requestId, false);
        if (value.submittedBy() != userId) {
            dutyService.requireDuty(userId, BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
        }
        return value;
    }

    private SensitiveChangeRecord executeApproved(long reviewerId, String requestId) {
        SensitiveChangeRecord value = require(requestId, true);
        requireStatus(value, SensitiveChangeStatus.APPROVED);
        if (!Objects.equals(value.reviewerId(), reviewerId)) {
            throw AuditGovernanceErrors.forbidden(AuditGovernanceErrors.OPERATION_FORBIDDEN);
        }
        NormalizedSensitiveCommand normalized = verifyHash(value);
        SensitiveOperationHandler handler = registry.require(value.operationCode());
        try {
            handler.execute(normalized, new SensitiveOperationContext(value.requestId(), value.submittedBy(),
                    reviewerId, TraceContext.current()));
        } catch (RuntimeException failure) {
            throw new HandlerExecutionException(failure);
        }
        LocalDateTime now = LocalDateTime.now();
        changed(repository.markExecuted(requestId, now, TraceContext.current()));
        append(value, reviewerId, "EXECUTE_SENSITIVE_CHANGE", "SUCCESS", null,
                value.selfApprovalDevMode(), "status=APPROVED", "status=EXECUTED");
        return require(requestId, false);
    }

    private void markExecutionFailed(long reviewerId, String requestId) {
        SensitiveChangeRecord value = require(requestId, true);
        if (value.status() != SensitiveChangeStatus.APPROVED) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        changed(repository.markExecutionFailed(requestId, "SENSITIVE_OPERATION_EXECUTION_FAILED",
                now, TraceContext.current()));
        append(value, reviewerId, "EXECUTE_SENSITIVE_CHANGE", "FAILED",
                "SENSITIVE_OPERATION_EXECUTION_FAILED", value.selfApprovalDevMode(),
                "status=APPROVED", "status=EXECUTION_FAILED");
    }

    private NormalizedSensitiveCommand verifyHash(SensitiveChangeRecord value) {
        try {
            JsonNode command = objectMapper.readTree(value.commandJson());
            NormalizedSensitiveCommand normalized = registry.require(value.operationCode()).normalize(command);
            if (!value.requestSha256().equals(hash(value.operationCode(), normalized))) {
                throw AuditGovernanceErrors.conflict(AuditGovernanceErrors.REQUEST_HASH_MISMATCH, "敏感申请摘要校验失败");
            }
            return normalized;
        } catch (JsonProcessingException e) {
            throw AuditGovernanceErrors.conflict(AuditGovernanceErrors.REQUEST_HASH_MISMATCH, "敏感申请摘要校验失败");
        }
    }

    private void append(SensitiveChangeRecord value, long operatorId, String action, String result,
                        String reason, boolean selfApproval, String before, String after) {
        auditWriter.append(new AuditEvidence(SOURCE_MODULE, value.buildingId(), "USER", operatorId,
                action, value.targetType(), value.targetId(), null, value.requestId(), before, after,
                result, reason, TraceContext.current(), LocalDateTime.now(), properties.getEnvironmentMode(), selfApproval));
    }

    private SensitiveChangeRecord require(String requestId, boolean lock) {
        return repository.find(requestId, lock).orElseThrow(() ->
                new BusinessException(404, AuditGovernanceErrors.REQUEST_CONFLICT, "敏感申请不存在或不可见"));
    }

    private static void requireOwner(SensitiveChangeRecord value, long userId) {
        if (value.submittedBy() != userId) {
            throw AuditGovernanceErrors.forbidden(AuditGovernanceErrors.OPERATION_FORBIDDEN);
        }
    }

    private static void requireStatus(SensitiveChangeRecord value, SensitiveChangeStatus expected) {
        if (value.status() != expected) {
            throw conflict();
        }
    }

    private static void changed(int count) {
        if (count != 1) {
            throw conflict();
        }
    }

    private static BusinessException conflict() {
        return AuditGovernanceErrors.conflict(AuditGovernanceErrors.REQUEST_CONFLICT, "敏感申请状态已变化");
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, AuditGovernanceErrors.REQUEST_CONFLICT, message);
        }
    }

    private static String hash(String operationCode, NormalizedSensitiveCommand command) {
        String material = operationCode + '\u001f' + nullSafe(command.buildingId()) + '\u001f'
                + command.targetType() + '\u001f' + command.targetId() + '\u001f' + command.canonicalJson();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static final class HandlerExecutionException extends RuntimeException {
        private HandlerExecutionException(RuntimeException cause) {
            super(cause);
        }
    }
}
