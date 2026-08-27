package com.platform.audit.sensitive;

import java.time.LocalDateTime;

/**
 * 已批准命令的非持久化执行输出。
 *
 * <p>一次性令牌只随本次执行响应返回，不能写入敏感申请、审计摘要或服务日志；普通命令返回空结果。</p>
 */
public record SensitiveOperationResult(
        String oneTimeToken,
        String tokenPurpose,
        LocalDateTime tokenExpiresAt
) {
    public static SensitiveOperationResult none() {
        return new SensitiveOperationResult(null, null, null);
    }

    public static SensitiveOperationResult oneTimeToken(String token, String purpose, LocalDateTime expiresAt) {
        if (token == null || token.isBlank() || purpose == null || purpose.isBlank() || expiresAt == null) {
            throw new IllegalArgumentException("一次性令牌执行结果不完整");
        }
        return new SensitiveOperationResult(token, purpose, expiresAt);
    }
}
