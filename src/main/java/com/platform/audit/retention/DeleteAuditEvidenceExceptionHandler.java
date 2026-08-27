package com.platform.audit.retention;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditGovernanceErrors;
import com.platform.audit.BackendDuty;
import com.platform.audit.sensitive.NormalizedSensitiveCommand;
import com.platform.audit.sensitive.SensitiveOperationContext;
import com.platform.audit.sensitive.SensitiveOperationHandler;
import com.platform.audit.sensitive.SensitiveOperationResult;
import com.platform.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
/** 提前、强制或手工范围删除只冻结受限字段，审核执行仅创建待清理批次。 */
public class DeleteAuditEvidenceExceptionHandler implements SensitiveOperationHandler {
    public static final String OPERATION_CODE = "DELETE_AUDIT_EVIDENCE_EXCEPTION";
    private static final Set<String> MODES = Set.of("EARLY_DELETE", "FORCED_DELETE", "MANUAL_SCOPE");

    private final ObjectMapper objectMapper;
    private final AuditCleanupService cleanupService;

    @Override
    public String operationCode() {
        return OPERATION_CODE;
    }

    @Override
    public Set<BackendDuty> requiredSubmitterDuties() {
        return Set.of(BackendDuty.AUDIT_EVIDENCE_HOLD_MANAGER);
    }

    @Override
    public NormalizedSensitiveCommand normalize(JsonNode command) {
        try {
            AuditExceptionCleanupCommand raw = objectMapper.readerFor(AuditExceptionCleanupCommand.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(command);
            AuditExceptionCleanupCommand normalized = cleanupService.normalizeException(raw);
            String json = objectMapper.writeValueAsString(normalized);
            return new NormalizedSensitiveCommand(normalized.scope().buildingId(), "AUDIT_EVIDENCE_SCOPE",
                    normalized.sourceModule() + ":" + normalized.mode(), json,
                    "source=" + normalized.sourceModule() + ";mode=" + normalized.mode()
                            + ";expectedCount=" + normalized.expectedCount());
        } catch (IOException | IllegalArgumentException failure) {
            throw invalid("例外删除命令无效");
        }
    }

    @Override
    public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        try {
            AuditExceptionCleanupCommand value = objectMapper.readValue(
                    command.canonicalJson(), AuditExceptionCleanupCommand.class);
            cleanupService.enqueueException(value, command.canonicalJson(), context.requestId(), context.reviewerId());
            return SensitiveOperationResult.none();
        } catch (IOException failure) {
            throw invalid("例外删除命令无效");
        }
    }

    static String mode(String value) {
        String normalized = required(value, 32, "例外删除模式无效").toUpperCase(Locale.ROOT);
        if (!MODES.contains(normalized)) throw invalid("例外删除模式无效");
        return normalized;
    }

    static String required(String value, int max, String message) {
        if (value == null || value.isBlank()) throw invalid(message);
        String normalized = value.trim();
        if (normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl)) throw invalid(message);
        return normalized;
    }

    static BusinessException invalid(String message) {
        return new BusinessException(400, AuditGovernanceErrors.RETENTION_POLICY_INVALID, message);
    }
}
