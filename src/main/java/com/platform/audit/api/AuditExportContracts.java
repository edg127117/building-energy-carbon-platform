package com.platform.audit.api;

import com.platform.audit.export.AuditExportJob;
import com.platform.audit.query.AuditQueryFilter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** 导出申请只接受统一查询条件和用途，不接受脱敏开关、文件路径或任意 SQL。 */
public final class AuditExportContracts {
    private AuditExportContracts() {
    }

    public record CreateRequest(
            @NotBlank @Size(max = 500) String purpose,
            String sourceModule, String buildingId, Long operatorId, String actorType,
            String actionType, String objectType, String objectId, String result,
            String reasonCode, String traceId, OffsetDateTime from, OffsetDateTime to) {
        AuditQueryFilter filter() {
            return new AuditQueryFilter(sourceModule, buildingId, operatorId, actorType, actionType,
                    objectType, objectId, result, reasonCode, traceId,
                    from == null ? null : from.toInstant(), to == null ? null : to.toInstant());
        }
    }

    public record JobView(
            String exportId, String status, int maxRows, Integer rowCount,
            String querySha256, String fileSha256, LocalDateTime expiresAt,
            String errorCode, String traceId, LocalDateTime createdAt,
            LocalDateTime startedAt, LocalDateTime completedAt, LocalDateTime downloadedAt) {
        static JobView from(AuditExportJob value, int maxRows) {
            return new JobView(value.exportId(), value.status(), maxRows, value.rowCount(),
                    value.querySha256(), value.fileSha256(), value.expiresAt(), value.errorCode(),
                    value.traceId(), value.createdAt(), value.startedAt(), value.completedAt(),
                    value.downloadedAt());
        }
    }
}
