package com.platform.audit.sensitive;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 系统敏感操作的显式代码注册边界。
 *
 * <p>实现只能解析自己的白名单 DTO 并调用领域 Service，不得接受任意类名、SQL、脚本或反射目标。</p>
 */
public interface SensitiveOperationHandler {
    String operationCode();

    NormalizedSensitiveCommand normalize(JsonNode command);

    void execute(NormalizedSensitiveCommand command, SensitiveOperationContext context);
}
