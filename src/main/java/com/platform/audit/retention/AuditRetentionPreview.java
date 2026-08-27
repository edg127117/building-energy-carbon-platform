package com.platform.audit.retention;

import java.time.LocalDateTime;

/** 清理前只统计范围和排除数量，不复制候选审计内容。 */
public record AuditRetentionPreview(
        long candidateCount,
        long eligibleCount,
        long heldCount,
        long protectedCount,
        LocalDateTime earliestOperationTime,
        LocalDateTime latestOperationTime
) {
}
