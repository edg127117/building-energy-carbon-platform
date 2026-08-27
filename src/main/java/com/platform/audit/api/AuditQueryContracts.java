package com.platform.audit.api;

import com.platform.audit.query.AuditEvidenceRecord;

import java.time.Instant;
import java.util.List;

/** 审计查询响应只包含公共字段和再次脱敏后的摘要。 */
public final class AuditQueryContracts {
    private AuditQueryContracts() {
    }

    public record PageView(List<EventView> items, String nextCursor) {
    }

    public record EventView(
            String auditId, String sourceModule, String buildingId, String actorType,
            Long operatorId, String actionType, String objectType, String objectId,
            String versionId, String reviewRequestId, String beforeSummary, String afterSummary,
            String result, String reasonCode, String traceId, Instant operationTime,
            String environmentMode, boolean selfApprovalDevMode) {
        static EventView from(AuditEvidenceRecord value) {
            return new EventView(value.auditId(), value.sourceModule(), value.buildingId(), value.actorType(),
                    value.operatorId(), value.actionType(), value.objectType(), value.objectId(), value.versionId(),
                    value.reviewRequestId(), value.beforeSummary(), value.afterSummary(), value.result(),
                    value.reasonCode(), value.traceId(), value.operationTime(), value.environmentMode(),
                    value.selfApprovalDevMode());
        }
    }
}
