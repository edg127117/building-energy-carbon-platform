package com.platform.audit.query;

import java.time.Instant;

/** 统一查询只暴露公共索引字段和脱敏摘要，领域完整事实仍保留在各自审计源。 */
public record AuditEvidenceRecord(
        String auditId,
        String sourceModule,
        String buildingId,
        String actorType,
        Long operatorId,
        String actionType,
        String objectType,
        String objectId,
        String versionId,
        String reviewRequestId,
        String beforeSummary,
        String afterSummary,
        String result,
        String reasonCode,
        String traceId,
        Instant operationTime,
        String environmentMode,
        boolean selfApprovalDevMode
) {
}
