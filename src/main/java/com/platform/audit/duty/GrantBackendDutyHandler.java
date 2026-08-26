package com.platform.audit.duty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditGovernanceErrors;
import com.platform.audit.BackendDuty;
import com.platform.audit.sensitive.NormalizedSensitiveCommand;
import com.platform.audit.sensitive.SensitiveOperationContext;
import com.platform.audit.sensitive.SensitiveOperationHandler;
import com.platform.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
/** 通过固定白名单命令授予后台职责；生产初始化不会自动给管理员批量授予职责。 */
public class GrantBackendDutyHandler implements SensitiveOperationHandler {
    public static final String OPERATION_CODE = "GRANT_BACKEND_DUTY";

    private final ObjectMapper objectMapper;
    private final BackendDutyAssignmentService assignmentService;

    @Override
    public String operationCode() {
        return OPERATION_CODE;
    }

    @Override
    public NormalizedSensitiveCommand normalize(JsonNode command) {
        try {
            GrantCommand value = objectMapper.treeToValue(command, GrantCommand.class);
            if (value.userId() == null || value.userId() < 1 || value.dutyKey() == null) {
                throw invalid();
            }
            BackendDuty duty = BackendDuty.valueOf(value.dutyKey().trim().toUpperCase());
            GrantCommand normalized = new GrantCommand(value.userId(), duty.name(), value.effectiveAt(), value.expiresAt());
            return new NormalizedSensitiveCommand(null, "BACKEND_DUTY_ASSIGNMENT",
                    value.userId() + ":" + duty.name(), objectMapper.writeValueAsString(normalized),
                    "userId=" + value.userId() + ";dutyKey=" + duty.name());
        } catch (IllegalArgumentException | JsonProcessingException e) {
            throw invalid();
        }
    }

    @Override
    public void execute(NormalizedSensitiveCommand command, SensitiveOperationContext context) {
        try {
            GrantCommand value = objectMapper.readValue(command.canonicalJson(), GrantCommand.class);
            assignmentService.grant(value.userId(), BackendDuty.valueOf(value.dutyKey()),
                    value.effectiveAt(), value.expiresAt(), context.requestId(), context.reviewerId());
        } catch (JsonProcessingException e) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(400, AuditGovernanceErrors.REQUEST_CONFLICT, "职责授予命令无效");
    }

    private record GrantCommand(Long userId, String dutyKey, LocalDateTime effectiveAt, LocalDateTime expiresAt) {
    }
}
