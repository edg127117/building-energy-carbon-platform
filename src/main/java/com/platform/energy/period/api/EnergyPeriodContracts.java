package com.platform.energy.period.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
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

/** 周期口径、当前投影、月度封账和有界重算的稳定 HTTP DTO。 */
public final class EnergyPeriodContracts {
    private EnergyPeriodContracts() {
    }

    public record ApproveRequest(
            @NotNull @Min(0) Integer expectedRevision,
            @NotBlank @Size(max = 500) String reviewComment) {
    }

    public record CreatePeriodPolicyRequest(
            @NotBlank @Size(max = 32) String buildingId,
            @NotBlank @Size(max = 64) String timezoneId,
            @NotNull @Min(0) @Max(720) Integer closingDelayHours,
            @NotBlank String lockMode,
            @NotBlank String sourceType,
            @NotBlank @Size(max = 500) String evidenceReference,
            @NotNull LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo) {
    }

    public record CreateExceptionPolicyRequest(
            @NotBlank @Size(max = 32) String buildingId,
            @NotBlank @Size(max = 64) String issueCode,
            @NotBlank @Size(max = 16) String severity,
            @NotBlank String lockAction,
            @Min(0) Integer maximumAffectedCount,
            @DecimalMin("0") @DecimalMax("1") BigDecimal maximumAffectedRatio,
            @DecimalMin("0") @DecimalMax("1") BigDecimal minimumCoverageRatio,
            @NotBlank @Size(max = 64) String applicableScope,
            @NotNull Boolean requiresApproval,
            @NotBlank @Size(max = 500) String requiredEvidence,
            @NotBlank String sourceType,
            @NotBlank @Size(max = 500) String evidenceReference,
            @NotNull LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo) {
    }

    public record ConversionSelectionRequest(
            @NotBlank String method,
            @NotBlank String perspective,
            @NotBlank String consumptionScope,
            @NotBlank @Size(max = 32) String regionCode) {
    }

    public record RefreshProjectionRequest(
            @NotBlank @Size(max = 32) String buildingId,
            @NotBlank @Size(max = 32) String pointId,
            @NotBlank String periodType,
            @NotNull LocalDate periodDate,
            @Valid ConversionSelectionRequest conversion,
            Instant calculationAsOf) {
    }

    public record SubmitLockRequest(
            @NotBlank @Size(max = 32) String projectionId,
            @NotNull @Min(1) Long expectedRevision,
            @NotBlank @Size(max = 500) String reason,
            @NotBlank @Size(max = 500) String evidenceReference) {
    }

    public record ApproveLockRequest(
            @NotBlank @Size(max = 500) String reviewComment) {
    }

    public record SubmitRecalculationRequest(
            @NotBlank @Size(max = 32) String buildingId,
            @NotBlank @Size(max = 160) String idempotencyKey,
            @NotBlank String mode,
            @NotBlank @Size(max = 500) String reason,
            @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 32) String> snapshotIds) {
    }

    public record ApproveRecalculationRequest(
            @NotBlank @Size(max = 500) String reviewComment) {
    }

    public record PeriodPolicyView(
            String policyId, String versionId, int versionNo, String buildingId,
            String timezoneId, int closingDelayHours, String lockMode, String status,
            String sourceType, String evidenceReference, LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo, int configRevision, long createdBy,
            LocalDateTime createdAt, Long approvedBy, LocalDateTime approvedAt) {
    }

    public record ExceptionPolicyView(
            String policyId, String versionId, int versionNo, String buildingId,
            String issueCode, String severity, String lockAction, Integer maximumAffectedCount,
            BigDecimal maximumAffectedRatio, BigDecimal minimumCoverageRatio,
            String applicableScope, boolean requiresApproval, String requiredEvidence,
            String status, String sourceType, String evidenceReference,
            LocalDateTime effectiveFrom, LocalDateTime effectiveTo, int configRevision,
            long createdBy, LocalDateTime createdAt, Long approvedBy, LocalDateTime approvedAt) {
    }

    public record ProjectionView(
            String projectionId, String resultKey, String resultNature, String buildingId,
            String pointId, String periodType, Instant startInclusive, Instant endExclusive,
            String timezoneId, String periodPolicyVersionId, String status, long revision,
            String energyItemCode, BigDecimal nativeQuantity, String nativeUnitCode,
            BigDecimal tce, String tceUnitCode, BigDecimal coverageRatio,
            List<String> issueCodes, String evidenceHash, Instant activityWatermark,
            LocalDateTime calculatedAt) {
    }

    public record LockRequestView(
            String requestId, String projectionId, long projectionRevision, String buildingId,
            String status, List<String> issuePolicyVersions, String reason, String evidenceReference,
            long submittedBy,
            LocalDateTime submittedAt, Long reviewedBy, LocalDateTime reviewedAt,
            String reviewComment) {
    }

    public record SnapshotView(
            String snapshotId, String resultKey, String projectionId, String buildingId,
            String pointId, String periodType, Instant startInclusive, Instant endExclusive,
            String timezoneId, String periodPolicyVersionId, int snapshotVersion,
            String status, String resultNature, String energyItemCode,
            BigDecimal nativeQuantity, String nativeUnitCode, BigDecimal tce,
            String tceUnitCode, BigDecimal coverageRatio, List<String> issueCodes,
            List<String> exceptionPolicyVersions, String evidenceHash,
            String supersedesSnapshotId, String sourceBatchId, long lockedBy,
            LocalDateTime lockedAt) {
    }

    public record RecalculationBatchView(
            String batchId, String buildingId, String idempotencyKey, String mode,
            String status, String reason, int totalItems, int processedItems,
            int changedItems, int unchangedItems, int failedItems, long submittedBy,
            LocalDateTime submittedAt, Long approvedBy, LocalDateTime approvedAt,
            String reviewComment, String safeError, LocalDateTime completedAt) {
    }

    public record EnergyPeriodApiError(
            int code, String errorCode, String msg, boolean success, String traceId) {
    }
}
