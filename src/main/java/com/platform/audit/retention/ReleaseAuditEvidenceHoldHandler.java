package com.platform.audit.retention;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.BackendDuty;
import com.platform.audit.sensitive.NormalizedSensitiveCommand;
import com.platform.audit.sensitive.SensitiveOperationContext;
import com.platform.audit.sensitive.SensitiveOperationHandler;
import com.platform.audit.sensitive.SensitiveOperationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

@Component
@RequiredArgsConstructor
/** 解除保全必须冻结原因并通过通用双人审批，不能从保全 API 直接改状态。 */
public class ReleaseAuditEvidenceHoldHandler implements SensitiveOperationHandler {
    public static final String OPERATION_CODE = "RELEASE_AUDIT_EVIDENCE_HOLD";

    private final ObjectMapper objectMapper;
    private final AuditEvidenceHoldService service;

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
            ReleaseCommand raw = objectMapper.readerFor(ReleaseCommand.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(command);
            String holdId = required(raw.holdId(), 32);
            String reason = required(raw.reason(), 500);
            AuditEvidenceHold hold = service.requireActive(holdId);
            ReleaseCommand normalized = new ReleaseCommand(holdId, reason);
            return new NormalizedSensitiveCommand(hold.buildingId(), "AUDIT_EVIDENCE_HOLD", holdId,
                    objectMapper.writeValueAsString(normalized), "holdId=" + holdId + ";release=true");
        } catch (IOException | IllegalArgumentException failure) {
            throw AuditEvidenceHoldService.invalid("解除保全命令无效");
        }
    }

    @Override
    public SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        try {
            ReleaseCommand value = objectMapper.readValue(command.canonicalJson(), ReleaseCommand.class);
            service.release(value.holdId(), value.reason(), context.requestId(),
                    context.submitterId(), context.reviewerId());
            return SensitiveOperationResult.none();
        } catch (IOException failure) {
            throw AuditEvidenceHoldService.invalid("解除保全命令无效");
        }
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank()) throw AuditEvidenceHoldService.invalid("解除保全命令无效");
        String normalized = value.trim();
        if (normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl)) {
            throw AuditEvidenceHoldService.invalid("解除保全命令无效");
        }
        return normalized;
    }

    private record ReleaseCommand(String holdId, String reason) {
    }
}
