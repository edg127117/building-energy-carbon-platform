package com.platform.audit.sensitive;

/** 处理器从白名单 DTO 生成的不可变、可哈希命令。 */
public record NormalizedSensitiveCommand(
        String buildingId,
        String targetType,
        String targetId,
        String canonicalJson,
        String impactSummary
) {
}
