package com.platform.audit.retention;

import java.time.LocalDateTime;

/** 单个事务实际删除的数量与校验摘要；不保存被删除记录的完整内容。 */
public record AuditCleanupBatch(
        int deletedCount,
        LocalDateTime earliestOperationTime,
        LocalDateTime latestOperationTime,
        String manifestSha256
) {
    public static AuditCleanupBatch empty() {
        return new AuditCleanupBatch(0, null, null, null);
    }
}
