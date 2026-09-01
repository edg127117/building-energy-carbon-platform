package com.platform.energy.period;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** 周期投影、封账和重算之间共享的稳定领域契约。 */
public final class EnergyPeriodModels {
    private EnergyPeriodModels() {
    }

    public enum PeriodType { DAY, MONTH, YEAR }
    public enum PolicyStatus { PENDING_REVIEW, APPROVED, DISABLED }
    public enum CurrentStatus { OPEN, PROVISIONAL }
    public enum SnapshotStatus {
        LOCKED_COMPLETE, LOCKED_WITH_EXCEPTIONS, LOCKED_PARTIAL, SUPERSEDED, INVALIDATED
    }
    public enum LockAction {
        BLOCK, ALLOW_WITH_EXCEPTION, EXCLUDE_AFFECTED_AND_LOCK, LOCK_NATIVE_QUANTITY_ONLY
    }
    public enum ReviewStatus { PENDING_REVIEW, APPROVED, REJECTED }
    public enum RecalculationMode { SAME_RULES, HISTORICAL_RESTATEMENT }
    public enum BatchStatus {
        CREATED, PENDING_REVIEW, VALIDATING, CALCULATING, WRITING_RESULTS, COMPLETED, FAILED
    }
    public enum BatchItemStatus { PENDING, CHANGED, UNCHANGED, FAILED }

    public record PeriodWindow(
            PeriodType type, Instant startInclusive, Instant endExclusive, String timezoneId) {
    }

    public record PeriodPolicyVersion(
            String policyId, String versionId, int versionNo, String buildingId,
            String timezoneId, int closingDelayHours, String lockMode, String status,
            String sourceType, String evidenceReference, LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo, int configRevision, long createdBy,
            LocalDateTime createdAt, Long approvedBy, LocalDateTime approvedAt) {
    }

    public record ExceptionPolicyVersion(
            String policyId, String versionId, int versionNo, String buildingId,
            String issueCode, String severity, String lockAction, Integer maximumAffectedCount,
            BigDecimal maximumAffectedRatio, BigDecimal minimumCoverageRatio,
            String applicableScope, boolean requiresApproval, String requiredEvidence,
            String status, String sourceType, String evidenceReference,
            LocalDateTime effectiveFrom, LocalDateTime effectiveTo, int configRevision,
            long createdBy, LocalDateTime createdAt, Long approvedBy, LocalDateTime approvedAt) {
    }

    public record ConversionSelection(
            String method, String perspective, String consumptionScope, String regionCode) {
    }

    public record ProjectionCalculation(
            String resultNature, String buildingId, String pointId, PeriodWindow window,
            String periodPolicyVersionId, String energyItemCode, BigDecimal nativeQuantity,
            String nativeUnitCode, BigDecimal tce, String tceUnitCode, BigDecimal coverageRatio,
            List<String> issueCodes, String evidenceJson, String evidenceHash,
            String conversionSelectionJson, Instant activityWatermark, LocalDateTime calculatedAt) {
        public ProjectionCalculation {
            issueCodes = List.copyOf(issueCodes);
        }
    }

    public record CurrentProjection(
            String projectionId, String resultKey, String buildingId, String pointId,
            String periodType, Instant startInclusive, Instant endExclusive, String timezoneId,
            String periodPolicyVersionId, String status, long revision, String resultNature,
            String energyItemCode, BigDecimal nativeQuantity, String nativeUnitCode,
            BigDecimal tce, String tceUnitCode, BigDecimal coverageRatio, String issueCodes,
            String evidenceJson, String evidenceHash, String conversionSelectionJson,
            Instant activityWatermark, LocalDateTime calculatedAt, LocalDateTime updatedAt) {
    }

    public record LockRequest(
            String requestId, String projectionId, long projectionRevision, String buildingId,
            String status, String issuePolicyVersions, String reason, String evidenceReference,
            long submittedBy,
            LocalDateTime submittedAt, Long reviewedBy, LocalDateTime reviewedAt,
            String reviewComment) {
    }

    public record PeriodSnapshot(
            String snapshotId, String resultKey, String projectionId, String buildingId,
            String pointId, String periodType, Instant startInclusive, Instant endExclusive,
            String timezoneId, String periodPolicyVersionId, int snapshotVersion,
            String status, String resultNature, String energyItemCode,
            BigDecimal nativeQuantity, String nativeUnitCode, BigDecimal tce,
            String tceUnitCode, BigDecimal coverageRatio, String issueCodes,
            String exceptionPolicyVersions, String evidenceJson, String evidenceHash,
            String conversionSelectionJson, Instant activityWatermark,
            String supersedesSnapshotId, String sourceBatchId, long lockedBy,
            LocalDateTime lockedAt) {
    }

    public record RecalculationBatch(
            String batchId, String buildingId, String idempotencyKey, String requestHash,
            String mode, String status, String reason, int totalItems, int processedItems,
            int changedItems, int unchangedItems, int failedItems, long submittedBy,
            LocalDateTime submittedAt, Long approvedBy, LocalDateTime approvedAt,
            String reviewComment, String safeError, LocalDateTime completedAt) {
    }

    public record RecalculationItem(
            String itemId, String batchId, String sourceSnapshotId, int itemOrder,
            String status, String newSnapshotId, String safeError) {
    }

    public record NumericResult(
            String resultKey, String buildingId, String pointId, Instant periodStart,
            BigDecimal nativeQuantity, String nativeUnitCode, BigDecimal tce,
            String tceUnitCode, BigDecimal coverageRatio, String resultNature,
            String evidenceHash, long revision) {
    }
}
