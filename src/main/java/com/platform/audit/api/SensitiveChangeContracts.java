package com.platform.audit.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.audit.sensitive.SensitiveChangeRecord;
import com.platform.audit.sensitive.SensitiveChangeExecutionResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** 通用敏感变更 API 契约；原始命令和请求哈希不进入响应。 */
public final class SensitiveChangeContracts {
    private SensitiveChangeContracts() {
    }

    public record CreateRequest(
            @NotBlank @Size(max = 64) String operationCode,
            @NotNull JsonNode command,
            @NotBlank @Size(max = 100) String idempotencyKey
    ) {
    }

    public record ReviewRequest(@NotBlank @Size(max = 500) String comment) {
    }

    public record View(
            String requestId,
            String operationCode,
            String status,
            String buildingId,
            String targetType,
            String targetId,
            String impactSummary,
            long submittedBy,
            LocalDateTime submittedAt,
            Long reviewerId,
            String reviewComment,
            LocalDateTime reviewedAt,
            LocalDateTime executedAt,
            String executionErrorCode,
            String environmentMode,
            boolean selfApprovalDevMode,
            String traceId,
            String oneTimeToken,
            String tokenPurpose,
            LocalDateTime tokenExpiresAt
    ) {
        static View from(SensitiveChangeRecord value) {
            return new View(value.requestId(), value.operationCode(), value.status().name(), value.buildingId(),
                    value.targetType(), value.targetId(), value.impactSummary(), value.submittedBy(),
                    value.submittedAt(), value.reviewerId(), value.reviewComment(), value.reviewedAt(),
                    value.executedAt(), value.executionErrorCode(), value.environmentMode(),
                    value.selfApprovalDevMode(), value.traceId(), null, null, null);
        }

        static View from(SensitiveChangeExecutionResult value) {
            SensitiveChangeRecord change = value.change();
            return new View(change.requestId(), change.operationCode(), change.status().name(), change.buildingId(),
                    change.targetType(), change.targetId(), change.impactSummary(), change.submittedBy(),
                    change.submittedAt(), change.reviewerId(), change.reviewComment(), change.reviewedAt(),
                    change.executedAt(), change.executionErrorCode(), change.environmentMode(),
                    change.selfApprovalDevMode(), change.traceId(), value.operationResult().oneTimeToken(),
                    value.operationResult().tokenPurpose(), value.operationResult().tokenExpiresAt());
        }
    }
}
