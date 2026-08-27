package com.platform.audit.retention;

import java.time.LocalDateTime;

/** 审核后不可原地改写的保留策略版本。 */
public record AuditRetentionPolicy(
        String policyId,
        String dataCategory,
        String sourceModule,
        String retentionPeriod,
        boolean cleanupEnabled,
        LocalDateTime effectiveAt,
        int policyVersion,
        String lifecycleStatus,
        String changeReason,
        String requestId,
        long approvedBy,
        LocalDateTime approvedAt
) {
}
