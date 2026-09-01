package com.platform.energy.summary;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 计量边界汇总策略、固定输入证据和查询结果使用的领域模型。 */
public final class EnergySummaryModels {
    private EnergySummaryModels() {
    }

    public enum AggregationMode {
        MAIN_METER_TOTAL,
        SUBMETER_SUM,
        INDEPENDENT_METER_SUM,
        MAIN_WITH_SUBMETER_BREAKDOWN
    }

    public enum QueryDimension {
        BUILDING,
        METERING_BOUNDARY,
        SPACE,
        SYSTEM,
        DEVICE,
        ENERGY_ITEM,
        ENERGY_SOURCE,
        FLOW_DIRECTION,
        PERIOD
    }

    public record BoundaryPolicyVersion(
            String policyId, String versionId, int versionNo, String buildingId,
            String meteringBoundaryId, String energyItemCode, String aggregationMode,
            String status, String sourceType, String evidenceReference,
            LocalDateTime effectiveFrom, LocalDateTime effectiveTo, int configRevision,
            long createdBy, LocalDateTime createdAt, Long approvedBy,
            LocalDateTime approvedAt, String reviewComment) {
    }

    public record SnapshotMeasure(
            String snapshotId, String buildingId, String pointId, String periodType,
            Instant startInclusive, Instant endExclusive, String timezoneId, String lockStatus,
            String resultNature, String energyItemCode, BigDecimal nativeQuantity,
            String nativeUnitCode, BigDecimal tce, String tceUnitCode,
            BigDecimal coverageRatio, List<String> issueCodes, String evidenceHash,
            String relationVersionId, long relationModelRevision,
            String energySource, String conversionPerspective,
            String conversionRuleVersionId) {
        public SnapshotMeasure {
            issueCodes = List.copyOf(issueCodes);
        }
    }

    public record AssignmentEvidence(
            String relationVersionId, long relationModelRevision, String pointId,
            String allocationStatus, String meteringBoundaryId,
            String meteringBoundaryCode, String meteringBoundaryName,
            String boundaryConfirmationStatus, String boundaryStatus,
            String meterRole, String meterDirection, String meterConfirmationStatus,
            String targetNodeType, String targetObjectId, String targetObjectCode,
            String targetObjectName) {
    }

    public record SummaryQuery(
            String buildingId, String periodType, Instant startInclusive,
            Instant endExclusive, List<QueryDimension> dimensions) {
        public SummaryQuery {
            dimensions = List.copyOf(dimensions);
        }
    }

    public record BoundaryAggregate(
            String buildingId, String meteringBoundaryId, String meteringBoundaryCode,
            String meteringBoundaryName, String energyItemCode, String energySource,
            String flowDirection, String periodType, Instant startInclusive,
            Instant endExclusive, String nativeUnitCode, BigDecimal authorityQuantity,
            BigDecimal assignedQuantity, BigDecimal unallocatedQuantity,
            BigDecimal residualQuantity, String tceUnitCode, BigDecimal authorityTce,
            BigDecimal coverageRatio, int exceptionCount, String lockStatus,
            String resultCompleteness, String resultNature, String relationVersionId,
            long relationModelRevision, String conversionPerspective,
            String conversionRuleVersionId, String summaryPolicyVersionId,
            String aggregationMode, List<String> evidenceHashes,
            List<TargetContribution> targetContributions) {
        public BoundaryAggregate {
            evidenceHashes = List.copyOf(evidenceHashes);
            targetContributions = List.copyOf(targetContributions);
        }
    }

    public record TargetContribution(
            String targetNodeType, String targetObjectId, String targetObjectCode,
            String targetObjectName, String allocationStatus, BigDecimal quantity,
            BigDecimal tce, boolean residual) {
    }

    public record QueryGroup(
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
}
