package com.platform.audit.sensitive;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.audit.BackendDuty;

import java.util.Set;

/**
 * 系统敏感操作的显式代码注册边界。
 *
 * <p>实现只能解析自己的白名单 DTO 并调用领域 Service，不得接受任意类名、SQL、脚本或反射目标。</p>
 */
public interface SensitiveOperationHandler {
    String operationCode();

    NormalizedSensitiveCommand normalize(JsonNode command);

    /** 除通用提交职责外，操作在建草稿和提交时必须仍然具备的专项职责。 */
    default Set<BackendDuty> requiredSubmitterDuties() {
        return Set.of();
    }

    SensitiveOperationResult execute(NormalizedSensitiveCommand command, SensitiveOperationContext context);
}
