package com.platform.audit.retention;

import java.time.LocalDateTime;

/** 长期保留的清理批次证据，只保存范围、数量、结果和摘要。 */
public record AuditCleanupRun(
        String runId,
        String triggerType,
        String policyId,
        Integer policyVersion,
        String dataCategory,
        String sourceModule,
        String buildingId,
        LocalDateTime cutoffTime,
        long candidateCount,
        long deletedCount,
        long heldCount,
        long protectedCount,
        LocalDateTime earliestOperationTime,
        LocalDateTime latestOperationTime,
        String status,
        String errorCode,
        String systemActor,
        Long triggeredBy,
        String triggerRequestId,
        String traceId,
        String manifestSha256,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
}
