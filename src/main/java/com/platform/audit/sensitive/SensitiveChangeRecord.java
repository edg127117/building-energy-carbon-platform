package com.platform.audit.sensitive;

import java.time.LocalDateTime;

/** 通用敏感变更的持久化视图。命令原文只供已注册处理器重新校验，不直接返回 API。 */
public record SensitiveChangeRecord(
        String requestId,
        String operationCode,
        SensitiveChangeStatus status,
        String buildingId,
        String targetType,
        String targetId,
        String commandJson,
        String requestSha256,
        String impactSummary,
        long submittedBy,
        LocalDateTime submittedAt,
        Long reviewerId,
        String reviewComment,
        LocalDateTime reviewedAt,
        LocalDateTime executedAt,
        String executionErrorCode,
        String idempotencyKey,
        String traceId,
        String environmentMode,
        boolean selfApprovalDevMode,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
