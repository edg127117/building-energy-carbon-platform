package com.platform.audit.retention;

import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceErrors;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import com.platform.audit.TraceContext;
import com.platform.framework.exception.BusinessException;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
/** 保全设置立即阻止匹配证据清理；解除只能由已审核敏感命令调用。 */
public class AuditEvidenceHoldService {
    private static final Pattern KEY = Pattern.compile("[A-Z0-9_]{1,64}");

    private final AuditEvidenceHoldRepository repository;
    private final List<AuditRetentionHandler> handlers;
    private final BackendDutyService dutyService;
    private final BuildingScopeService buildingScopeService;
    private final AuditEvidenceWriter evidenceWriter;
    private final AuditGovernanceProperties properties;
    private final AuditCleanupConcurrencyGuard cleanupGuard;

    @Transactional(rollbackFor = Exception.class)
    public AuditEvidenceHold create(long userId, Collection<String> roles, CreateHold command) {
        dutyService.requireDuty(userId, BackendDuty.AUDIT_EVIDENCE_HOLD_MANAGER);
        CreateHold normalized = normalize(command);
        if (normalized.buildingId() != null
                && !buildingScopeService.canAccess(userId, roles, normalized.buildingId())) {
            throw AuditGovernanceErrors.forbidden(AuditGovernanceErrors.OPERATION_FORBIDDEN);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!normalized.reviewAt().isAfter(now)) {
            throw invalid("保全复核时间必须晚于当前时间");
        }
        cleanupGuard.lock();
        AuditEvidenceHold hold = new AuditEvidenceHold(id(), normalized.sourceModule(), normalized.auditId(),
                normalized.buildingId(), normalized.actionType(), normalized.objectType(), normalized.objectId(),
                normalized.fromTime(), normalized.toTime(), normalized.investigationId(), normalized.reason(),
                normalized.legalBasis(), now, normalized.reviewAt(), "ACTIVE", userId, now,
                null, null, null, null);
        repository.insert(hold);
        append(hold, userId, "SET_AUDIT_EVIDENCE_HOLD", null, false, null, "status=ACTIVE");
        return hold;
    }

    @Transactional(readOnly = true)
    public List<AuditEvidenceHold> list(long userId) {
        dutyService.requireDuty(userId, BackendDuty.AUDIT_EVIDENCE_HOLD_MANAGER);
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public AuditEvidenceHold requireActive(String holdId) {
        AuditEvidenceHold value = repository.find(holdId, false).orElseThrow(() -> invalid("保全记录不存在"));
        if (!"ACTIVE".equals(value.status())) {
            throw AuditGovernanceErrors.conflict(AuditGovernanceErrors.EVIDENCE_HOLD_CONFLICT, "保全状态已变化");
        }
        return value;
    }

    @Transactional(rollbackFor = Exception.class)
    public void release(String holdId, String reason, String requestId,
                        long submitterId, long reviewerId) {
        AuditEvidenceHold value = repository.find(holdId, true).orElseThrow(() -> invalid("保全记录不存在"));
        if (!"ACTIVE".equals(value.status())
                || repository.release(holdId, reviewerId, requestId, text(reason, 500, "解除原因无效"),
                LocalDateTime.now()) != 1) {
            throw AuditGovernanceErrors.conflict(AuditGovernanceErrors.EVIDENCE_HOLD_CONFLICT, "保全状态已变化");
        }
        append(value, reviewerId, "RELEASE_AUDIT_EVIDENCE_HOLD", requestId,
                submitterId == reviewerId, "status=ACTIVE", "status=RELEASED");
    }

    private CreateHold normalize(CreateHold value) {
        if (value == null) throw invalid("保全范围不能为空");
        String source = key(value.sourceModule());
        if (source != null && handlers.stream().noneMatch(handler -> handler.sourceModule().equals(source))) {
            throw invalid("保全审计来源无效");
        }
        String auditId = optional(value.auditId(), 32, "审计编号无效");
        if (auditId != null && source == null) throw invalid("按审计编号保全时必须指定来源");
        String building = optional(value.buildingId(), 32, "建筑编号无效");
        String action = key(value.actionType());
        String objectType = key(value.objectType());
        String objectId = optional(value.objectId(), 128, "对象编号无效");
        if ((objectType == null) != (objectId == null)) throw invalid("对象类型和对象编号必须同时指定");
        if (value.fromTime() != null && value.toTime() != null && !value.fromTime().isBefore(value.toTime())) {
            throw invalid("保全时间范围无效");
        }
        if (auditId == null && building == null && action == null && objectId == null
                && value.fromTime() == null && value.toTime() == null) {
            throw invalid("保全必须包含至少一个可匹配审计证据的范围条件");
        }
        if (value.reviewAt() == null) throw invalid("保全复核时间不能为空");
        return new CreateHold(source, auditId, building, action, objectType, objectId,
                value.fromTime(), value.toTime(), optional(value.investigationId(), 100, "调查编号无效"),
                text(value.reason(), 500, "保全原因无效"),
                text(value.legalBasis(), 500, "保全依据无效"), value.reviewAt());
    }

    private void append(AuditEvidenceHold hold, long operatorId, String action, String reviewRequestId,
                        boolean selfApproval, String before, String after) {
        evidenceWriter.append(new AuditEvidence("SYSTEM_SECURITY", hold.buildingId(), "USER", operatorId,
                action, "AUDIT_EVIDENCE_HOLD", hold.holdId(), null, reviewRequestId, before, after,
                "SUCCESS", null, TraceContext.current(), LocalDateTime.now(), properties.getEnvironmentMode(),
                selfApproval));
    }

    static BusinessException invalid(String message) {
        return new BusinessException(400, AuditGovernanceErrors.EVIDENCE_HOLD_INVALID, message);
    }

    private static String key(String value) {
        String normalized = optional(value, 64, "保全范围键无效");
        if (normalized == null) return null;
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!KEY.matcher(normalized).matches()) throw invalid("保全范围键无效");
        return normalized;
    }

    private static String text(String value, int max, String message) {
        String normalized = optional(value, max, message);
        if (normalized == null) throw invalid(message);
        return normalized;
    }

    private static String optional(String value, int max, String message) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl)) throw invalid(message);
        return normalized;
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public record CreateHold(
            String sourceModule, String auditId, String buildingId, String actionType,
            String objectType, String objectId, LocalDateTime fromTime, LocalDateTime toTime,
            String investigationId, String reason, String legalBasis, LocalDateTime reviewAt) {
    }
}
