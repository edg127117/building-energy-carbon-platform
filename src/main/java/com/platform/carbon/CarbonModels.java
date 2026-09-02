package com.platform.carbon;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 碳规则、活动输入、确定性计算和自动重算共享的不可变领域模型。 */
public final class CarbonModels {
    private CarbonModels() {
    }

    public enum ResultNature { DEVELOPMENT_SIMULATION, FORMAL }
    public enum UsageNature { DEVELOPMENT_REFERENCE, FORMAL }
    public enum LifecycleStatus { PENDING_REVIEW, APPROVED, ACTIVE, DISABLED, REJECTED }
    public enum PeriodType { MONTH, QUARTER, YEAR }
    public enum ScopeType { SCOPE_1, SCOPE_2 }
    public enum FactorCategory {
        STATIONARY_COMBUSTION, PURCHASED_ELECTRICITY_LOCATION, PURCHASED_HEAT
    }
    public enum ApplicabilityLevel {
        BUILDING_SPECIFIC, PROVINCE, NATIONAL, NOT_REGION_SPECIFIC
    }
    public enum ComponentType {
        LOWER_HEATING_VALUE, CARBON_CONTENT_PER_HEAT, OXIDATION_RATE,
        DIRECT_EMISSION_FACTOR
    }
    public enum DenominatorType { BUILDING_AREA, RESIDENT_POPULATION }

    public record ActivitySegment(
            String snapshotId, String buildingId, PeriodType periodType,
            Instant startInclusive, Instant endExclusive, String timezoneId,
            String energyItemCode, BigDecimal quantity, String unitCode,
            String lockStatus, String completeness, ResultNature resultNature,
            String evidenceHash) {
    }

    public record FactorSourceVersion(
            String sourceId, String sourceCode, String sourceVersionId, int versionNo,
            String sourceName, String publisher, String documentReference,
            Integer publicationYear, LocalDate publishedOn, String applicabilityNote,
            String evidenceReference, UsageNature usageNature, long createdBy,
            LocalDateTime createdAt) {
    }

    public record FactorComponent(
            String componentId, ComponentType type, BigDecimal value, String unit,
            String sourceVersionId, String evidenceReference) {
    }

    public record FactorVersion(
            String factorId, String factorCode, String factorVersionId, int versionNo,
            ScopeType scopeType, String energyItemCode, FactorCategory category,
            String resultBasis, String gasCode, String gasCoverage,
            String sourceVersionId, ApplicabilityLevel applicabilityLevel,
            String buildingId, String regionCode, String inputUnitCode,
            String standardConditionCode, UsageNature usageNature,
            LifecycleStatus status, LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo, String formulaVersionId,
            String roundingPolicyVersionId, int configRevision, long createdBy,
            LocalDateTime createdAt, Long reviewedBy, LocalDateTime reviewedAt,
            String reviewComment, Long activatedBy, LocalDateTime activatedAt,
            List<FactorComponent> components) {
        public FactorVersion {
            components = List.copyOf(components);
        }
    }

    public record DenominatorVersion(
            String denominatorId, String denominatorVersionId, int versionNo,
            String buildingId, DenominatorType type, BigDecimal value, String unitCode,
            String sourceReference, String evidenceReference, UsageNature usageNature,
            LifecycleStatus status, LocalDate effectiveFrom, LocalDate effectiveTo,
            int configRevision, long createdBy, LocalDateTime createdAt,
            Long reviewedBy, LocalDateTime reviewedAt, String reviewComment,
            Long activatedBy, LocalDateTime activatedAt) {
    }

    public record FactorMatch(FactorVersion factor, BigDecimal convertedActivity,
                              String matchReason) {
    }

    public record GwpVersion(
            String gwpVersionId, String gasCode, BigDecimal value,
            String sourceReference, UsageNature usageNature,
            LocalDateTime effectiveFrom, LocalDateTime effectiveTo) {
    }

    public record CalculatedItem(
            ActivitySegment activity, FactorVersion factor, BigDecimal convertedActivity,
            String formulaVersionId, String gwpVersionId, BigDecimal exactEmissionKgCo2e,
            BigDecimal persistedEmissionKgCo2e, String matchReason,
            String evidenceJson, String evidenceHash) {
    }

    public record CalculationFailure(
            ActivitySegment activity, String errorCode, String errorMessage) {
    }

    public record SummaryMetric(
            String metricCode, String dimensionCode, BigDecimal rawValue,
            BigDecimal finalValue, String unitCode, String denominatorVersionId,
            String unavailableReason, String evidenceHash) {
    }

    public record CalculationResult(
            List<CalculatedItem> items, List<CalculationFailure> failures,
            List<SummaryMetric> summaries, boolean complete,
            List<String> incompleteReasons) {
        public CalculationResult {
            items = List.copyOf(items);
            failures = List.copyOf(failures);
            summaries = List.copyOf(summaries);
            incompleteReasons = List.copyOf(incompleteReasons);
        }
    }

    public record CalculationBatch(
            String batchId, String buildingId, PeriodType periodType,
            Instant periodStart, Instant periodEnd, String timezoneId,
            ResultNature resultNature, String publicationStatus, String status, String idempotencyKey,
            String requestHash, String activeLockKey, String roundingPolicyVersionId,
            String supersedesBatchId, LocalDateTime startedAt, LocalDateTime deadlineAt,
            LocalDateTime completedAt, Long durationMs, int snapshotCount, int detailCount,
            boolean slowCalculation, String safeErrorCode, String safeErrorMessage,
            long createdBy, LocalDateTime createdAt) {
    }

    public record StoredCalculationItem(
            String calculationItemId, String batchId, String snapshotId,
            String energyItemCode, ScopeType scopeType, BigDecimal activityQuantity,
            String activityUnitCode, String factorVersionId, String formulaVersionId,
            String gwpVersionId, BigDecimal emissionKgCo2e, String matchReason,
            String evidenceHash) {
    }

    public record CalculationDetail(
            CalculationBatch batch, List<StoredCalculationItem> items,
            List<CalculationFailure> failures, List<SummaryMetric> summaries) {
        public CalculationDetail {
            items = List.copyOf(items);
            failures = List.copyOf(failures);
            summaries = List.copyOf(summaries);
        }
    }

    public record RecalculationBatch(
            String batchId, String batchKey, String triggerReason,
            String organizationBoundary, ResultNature resultNature, String status,
            boolean scopeFrozen, int itemCount, int eligibleItemCount,
            String leaseToken, LocalDateTime leaseUntil, LocalDateTime startedAt,
            LocalDateTime completedAt, Long initiatedBy, Long approvedBy,
            LocalDateTime approvedAt, String reviewComment, String safeErrorCode,
            LocalDateTime createdAt) {
    }

    public record DependencyChange(
            String changeId, String changeType, String sourceObjectType,
            String sourceObjectId, String changeDetail, String oldVersionId, String newVersionId,
            String changeFingerprint, String buildingId, String organizationBoundary,
            LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo, Long triggeredBy, String status,
            LocalDateTime createdAt, LocalDateTime processedAt) {
    }

    public record RecalculationItem(
            String itemId, String batchId, String buildingId, int accountingYear,
            String oldCalculationBatchId, String candidateCalculationBatchId,
            String status, boolean approvalEligible, int retryCount,
            LocalDateTime nextAttemptAt, String safeErrorCode,
            String safeErrorMessage, String activeLockKey, LocalDateTime createdAt,
            LocalDateTime completedAt) {
    }
}
