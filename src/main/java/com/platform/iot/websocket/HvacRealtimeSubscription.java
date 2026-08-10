package com.platform.iot.websocket;

import java.util.Objects;
import java.util.Set;

/**
 * 会话已通过首帧认证后保存的最小身份与建筑范围。
 *
 * <p>原始 Token 只在当前 WebSocket 会话内用于心跳复核，关闭或移除会话后立即丢弃；它既不
 * 序列化给浏览器，也不得写入日志。重写 {@link #toString()} 是为了避免未来排障时意外泄露。
 * </p>
 */
public record HvacRealtimeSubscription(
        Long userId,
        Set<String> roles,
        String buildingId,
        String token,
        long expiresAt) {

    public HvacRealtimeSubscription {
        userId = Objects.requireNonNull(userId, "userId");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        buildingId = Objects.requireNonNull(buildingId, "buildingId");
        token = Objects.requireNonNull(token, "token");
    }

    @Override
    public String toString() {
        return "HvacRealtimeSubscription[userId=" + userId
                + ", roles=" + roles
                + ", buildingId=" + buildingId
                + ", token=<redacted>"
                + ", expiresAt=" + expiresAt + ']';
    }
}
