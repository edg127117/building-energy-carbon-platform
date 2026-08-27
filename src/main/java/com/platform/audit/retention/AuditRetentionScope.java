package com.platform.audit.retention;

import java.time.LocalDateTime;

/** 清理处理器可接受的固定字段范围；不暴露任意 SQL 或表名。 */
public record AuditRetentionScope(
        String auditId,
        String buildingId,
        String actionType,
        String objectType,
        String objectId,
        LocalDateTime fromTime,
        LocalDateTime toTime
) {
    public static AuditRetentionScope expiredBefore(LocalDateTime cutoff) {
        return new AuditRetentionScope(null, null, null, null, null, null, cutoff);
    }
}
