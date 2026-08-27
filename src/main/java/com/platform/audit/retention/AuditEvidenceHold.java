package com.platform.audit.retention;

import java.time.LocalDateTime;

/** 保全只冻结匹配范围的清理资格，不扩大原有查询和导出权限。 */
public record AuditEvidenceHold(
        String holdId,
        String sourceModule,
        String auditId,
        String buildingId,
        String actionType,
        String objectType,
        String objectId,
        LocalDateTime fromTime,
        LocalDateTime toTime,
        String investigationId,
        String reason,
        String legalBasis,
        LocalDateTime startsAt,
        LocalDateTime reviewAt,
        String status,
        long createdBy,
        LocalDateTime createdAt,
        Long releasedBy,
        LocalDateTime releasedAt,
        String releaseRequestId,
        String releaseReason
) {
}
