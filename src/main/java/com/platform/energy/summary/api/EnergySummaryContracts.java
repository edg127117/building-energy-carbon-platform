package com.platform.energy.summary.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 计量边界汇总策略和受限多维查询的稳定 HTTP DTO。 */
public final class EnergySummaryContracts {
    private EnergySummaryContracts() {
    }

    public record CreateBoundaryPolicyRequest(
            @NotBlank @Size(max = 32) String buildingId,
            @NotBlank @Size(max = 32) String meteringBoundaryId,
            @NotBlank @Size(max = 64) String energyItemCode,
            @NotBlank String aggregationMode,
            @NotBlank String sourceType,
            @NotBlank @Size(max = 500) String evidenceReference,
            @NotNull LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo) {
    }

    public record ApproveBoundaryPolicyRequest(
            @NotNull @Min(0) Integer expectedRevision,
            @NotBlank @Size(max = 500) String reviewComment) {
    }

    public record SummaryQueryRequest(
            @NotBlank @Size(max = 32) String buildingId,
            @NotBlank String periodType,
            @NotNull Instant startInclusive,
            @NotNull Instant endExclusive,
            @NotEmpty @Size(max = 5) List<@NotBlank String> dimensions) {
    }

    public record BoundaryPolicyView(
            String policyId, String versionId, int versionNo, String buildingId,
            String meteringBoundaryId, String energyItemCode, String aggregationMode,
            String status, String sourceType, String evidenceReference,
            LocalDateTime effectiveFrom, LocalDateTime effectiveTo, int configRevision,
            long createdBy, LocalDateTime createdAt, Long approvedBy,
            LocalDateTime approvedAt, String reviewComment) {
    }

    public record SummaryQueryView(
            String buildingId, String periodType, Instant startInclusive,
            Instant endExclusive, List<String> dimensions, int sourceSnapshotCount,
            List<SummaryGroupView> groups) {
    }

    public record SummaryGroupView(
            Map<String, String> groupKey, Map<String, BigDecimal> originalQuantities,
            Map<String, BigDecimal> grossInboundQuantities,
            Map<String, BigDecimal> grossOutboundQuantities,
            Map<String, BigDecimal> netQuantities,
            Map<String, BigDecimal> tceByPerspective,
            Map<String, BigDecimal> assignedQuantities,
            Map<String, BigDecimal> unallocatedQuantities,
            Map<String, BigDecimal> residualQuantities,
            BigDecimal coverageRatio, int exceptionCount, String lockStatus,
            String resultCompleteness, List<String> relationVersionIds,
            List<String> conversionRuleVersionIds, List<String> summaryPolicyVersionIds,
            List<String> evidenceHashes, String resultNature) {
    }

    public record EnergySummaryApiError(
            int code, String errorCode, String msg, boolean success, String traceId) {
    }
}
