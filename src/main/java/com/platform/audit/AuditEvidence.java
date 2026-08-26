package com.platform.audit;

import java.time.LocalDateTime;

/** 公共安全审计写入契约；领域模块可以通过独立实现保持与领域事务原子提交。 */
public record AuditEvidence(
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
        LocalDateTime operationTime,
        AuditEnvironmentMode environmentMode,
        boolean selfApprovalDevMode
) {
}
