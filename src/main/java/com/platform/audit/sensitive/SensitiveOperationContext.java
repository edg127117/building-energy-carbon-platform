package com.platform.audit.sensitive;

/** 已批准命令执行时可用的受控上下文。 */
public record SensitiveOperationContext(
        String requestId,
        long submitterId,
        long reviewerId,
        String traceId
) {
}
