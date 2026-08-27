package com.platform.audit.retention;

/** 审核冻结的例外删除范围与数量容差，不包含任意 SQL 或完整审计载荷。 */
public record AuditExceptionCleanupCommand(
        String mode,
        String dataCategory,
        String sourceModule,
        AuditRetentionScope scope,
        String reason,
        String legalBasis,
        long expectedCount,
        long allowedCountDifference
) {
}
