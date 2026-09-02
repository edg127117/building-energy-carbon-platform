package com.platform.carbon.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 碳规则、计算和自动重算使用的稳定 HTTP DTO。 */
public final class CarbonContracts {
    private CarbonContracts() {
    }

    public record CreateFactorSourceRequest(
            @NotBlank @Size(max = 64) String sourceCode,
            @NotBlank @Size(max = 200) String sourceName,
            @NotBlank @Size(max = 200) String publisher,
            @NotBlank @Size(max = 500) String documentReference,
            @Min(1900) @Max(2200) Integer publicationYear,
            LocalDate publishedOn,
            @NotBlank @Size(max = 1000) String applicabilityNote,
            @NotBlank @Size(max = 1000) String evidenceReference,
            @NotBlank String usageNature) {
    }

    public record FactorComponentRequest(
            @NotBlank String componentType,
            @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal value,
            @NotBlank @Size(max = 100) String unit,
            @NotBlank @Size(max = 32) String sourceVersionId,
            @NotBlank @Size(max = 1000) String evidenceReference) {
    }

    public record CreateFactorVersionRequest(
            @NotBlank @Size(max = 64) String factorCode,
            @NotBlank String scopeType,
            @NotBlank @Size(max = 64) String energyItemCode,
            @NotBlank String factorCategory,
            @NotBlank String resultBasis,
            @NotBlank @Size(max = 32) String gasCode,
            @NotBlank @Size(max = 100) String gasCoverage,
            @NotBlank @Size(max = 32) String sourceVersionId,
            @NotBlank String applicabilityLevel,
            @Size(max = 32) String buildingId,
            @Size(max = 64) String regionCode,
            @NotBlank @Size(max = 64) String inputUnitCode,
            @Size(max = 100) String standardConditionCode,
            @NotBlank String usageNature,
            @NotNull LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo,
            @NotBlank @Size(max = 32) String formulaVersionId,
            @NotBlank @Size(max = 32) String roundingPolicyVersionId,
            @NotEmpty @Size(max = 4) List<@Valid FactorComponentRequest> components) {
    }

    public record ReviewRequest(
            @NotNull @Min(0) Integer expectedRevision,
            @NotNull Boolean approved,
            @NotBlank @Size(max = 500) String reviewComment) {
    }

    public record LifecycleRequest(@NotNull @Min(0) Integer expectedRevision) {
    }

    public record CreateDenominatorRequest(
            @NotBlank @Size(max = 32) String buildingId,
            @NotBlank String denominatorType,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal value,
            @NotBlank @Size(max = 32) String unitCode,
            @NotBlank @Size(max = 1000) String sourceReference,
            @NotBlank @Size(max = 1000) String evidenceReference,
            @NotBlank String usageNature,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }

    public record RunCalculationRequest(
            @NotBlank @Size(max = 32) String buildingId,
            @NotBlank String periodType,
            @NotNull Instant startInclusive,
            @NotNull Instant endExclusive,
            @NotBlank @Size(max = 64) String timezoneId,
            @NotBlank String resultNature,
            @NotBlank @Size(max = 100) String idempotencyKey) {
    }

    public record ManualRecalculationRequest(
            @NotBlank @Size(max = 32) String buildingId,
            @Min(2000) @Max(2200) int accountingYear,
            @NotBlank String resultNature,
            @NotBlank @Size(max = 500) String reason,
            @NotBlank @Size(max = 100) String organizationBoundary) {
    }

    public record ApproveRecalculationRequest(
            @NotBlank @Size(max = 500) String reviewComment) {
    }

    public record RecoverDeadItemRequest(
            @NotBlank @Size(max = 500) String reason) {
    }

    public record ManualRecalculationAcceptedView(
            String changeId, String status, String buildingId, int accountingYear) {
    }

    public record FactorSourceView(
            String sourceId, String sourceCode, String sourceVersionId, int versionNo,
            String sourceName, String publisher, String documentReference,
            Integer publicationYear, LocalDate publishedOn, String applicabilityNote,
            String evidenceReference, String usageNature, long createdBy,
            LocalDateTime createdAt) {
    }

    public record FactorComponentView(
            String componentId, String componentType, BigDecimal value, String unit,
            String sourceVersionId, String evidenceReference) {
    }

    public record FactorVersionView(
            String factorId, String factorCode, String factorVersionId, int versionNo,
            String scopeType, String energyItemCode, String factorCategory,
            String resultBasis, String gasCode, String gasCoverage,
            String sourceVersionId, String applicabilityLevel, String buildingId,
            String regionCode, String inputUnitCode, String standardConditionCode,
            String usageNature, String status, LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo, String formulaVersionId,
            String roundingPolicyVersionId, int configRevision, long createdBy,
            LocalDateTime createdAt, Long reviewedBy, LocalDateTime reviewedAt,
            String reviewComment, Long activatedBy, LocalDateTime activatedAt,
            List<FactorComponentView> components) {
    }

    public record DenominatorVersionView(
            String denominatorId, String denominatorVersionId, int versionNo,
            String buildingId, String denominatorType, BigDecimal value, String unitCode,
            String sourceReference, String evidenceReference, String usageNature,
            String status, LocalDate effectiveFrom, LocalDate effectiveTo,
            int configRevision, long createdBy, LocalDateTime createdAt,
            Long reviewedBy, LocalDateTime reviewedAt, String reviewComment,
            Long activatedBy, LocalDateTime activatedAt) {
    }

    public record CalculationItemView(
            String calculationItemId, String snapshotId, String energyItemCode,
            String scopeType, BigDecimal activityQuantity, String activityUnitCode,
            String factorVersionId, String formulaVersionId, String gwpVersionId,
            BigDecimal emissionKgCo2e, String matchReason, String evidenceHash) {
    }

    public record SummaryView(
            String metricCode, String dimensionCode, BigDecimal rawValue,
            BigDecimal finalValue, String unitCode, String denominatorVersionId,
            String unavailableReason, String evidenceHash) {
    }

    public record CalculationFailureView(
            String snapshotId, String energyItemCode, Instant startInclusive,
            Instant endExclusive, String errorCode, String errorMessage,
            String activityEvidenceHash) {
    }

    public record CalculationBatchView(
            String calculationBatchId, String buildingId, String periodType,
            Instant startInclusive, Instant endExclusive, String timezoneId,
            String resultNature, String publicationStatus, String status, String idempotencyKey,
            String supersedesCalculationBatchId, LocalDateTime startedAt,
            LocalDateTime completedAt, Long durationMs, int snapshotCount,
            int detailCount, boolean slowCalculation, String safeErrorCode,
            String safeErrorMessage, List<CalculationItemView> items,
            List<CalculationFailureView> failures,
            List<SummaryView> summaries) {
    }

    public record RecalculationItemView(
            String recalculationItemId, String buildingId, int accountingYear,
            String oldCalculationBatchId, String candidateCalculationBatchId,
            String status, boolean approvalEligible, int retryCount,
            LocalDateTime nextAttemptAt, String safeErrorCode,
            String safeErrorMessage) {
    }

    public record RecalculationBatchView(
            String recalculationBatchId, String triggerReason,
            String organizationBoundary, String resultNature, String status,
            boolean scopeFrozen, int itemCount, int eligibleItemCount,
            Long initiatedBy, Long approvedBy, LocalDateTime approvedAt,
            String reviewComment, String safeErrorCode,
            List<RecalculationItemView> items) {
    }

    public record CarbonApiError(
            int code, String errorCode, String msg, boolean success, String traceId) {
    }
}
