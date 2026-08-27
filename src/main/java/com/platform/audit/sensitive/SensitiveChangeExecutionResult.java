package com.platform.audit.sensitive;

/** 通用敏感申请的持久化状态与仅本次可见的执行输出。 */
public record SensitiveChangeExecutionResult(
        SensitiveChangeRecord change,
        SensitiveOperationResult operationResult
) {
}
