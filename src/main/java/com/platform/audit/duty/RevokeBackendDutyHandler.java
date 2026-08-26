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

@Component
@RequiredArgsConstructor
/** 通过固定白名单命令撤销后台职责，历史授权和来源申请不会被物理覆盖。 */
public class RevokeBackendDutyHandler implements SensitiveOperationHandler {
    public static final String OPERATION_CODE = "REVOKE_BACKEND_DUTY";

    private final ObjectMapper objectMapper;
    private final BackendDutyAssignmentService assignmentService;

    @Override
    public String operationCode() {
        return OPERATION_CODE;
    }

    @Override
    public NormalizedSensitiveCommand normalize(JsonNode command) {
        try {
            RevokeCommand value = objectMapper.treeToValue(command, RevokeCommand.class);
            if (value.userId() == null || value.userId() < 1 || value.dutyKey() == null) {
                throw invalid();
            }
            BackendDuty duty = BackendDuty.valueOf(value.dutyKey().trim().toUpperCase());
            RevokeCommand normalized = new RevokeCommand(value.userId(), duty.name());
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
            RevokeCommand value = objectMapper.readValue(command.canonicalJson(), RevokeCommand.class);
            assignmentService.revoke(value.userId(), BackendDuty.valueOf(value.dutyKey()),
                    context.requestId(), context.reviewerId());
        } catch (JsonProcessingException e) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(400, AuditGovernanceErrors.REQUEST_CONFLICT, "职责撤销命令无效");
    }

    private record RevokeCommand(Long userId, String dutyKey) {
    }
}
