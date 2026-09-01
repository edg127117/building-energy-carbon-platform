package com.platform.energy.aggregation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 聚合端口和算法之间的稳定十进制领域契约。 */
public final class EnergyAggregationModels {
    private EnergyAggregationModels() {
    }

    public enum ValueSemantics { CUMULATIVE, PERIOD_TOTAL, INSTANTANEOUS }
    public enum DataNature { REAL, SIMULATED }
    public enum MeterEventType { RESET, ROLLOVER, REPLACEMENT, DATA_ERROR }
    public enum EvidenceStatus { PENDING_REVIEW, APPROVED, DISABLED }
    public enum IntegrationMethod { STEP_PREVIOUS, TRAPEZOIDAL }
    public enum BoundaryHandling { REQUIRE_BOUNDARY_READINGS }
    public enum ResultCompleteness { COMPLETE, COMPLETE_WITH_ALLOWED_QUALITY }

    public record AggregationQuery(
            String buildingId, String pointId, Instant startInclusive, Instant endExclusive,
            Instant calculationAsOf) {
    }

    public record MeasurementContext(
            String energyItemCode, String pointBindingVersionId, String sourceUnitCode,
            String unitDefinitionVersionId, ValueSemantics valueSemantics,
            String standardConditionCode, DataNature dataNature, String confirmationStatus,
            Instant effectiveFrom, Instant effectiveTo, String evidenceReference) {
    }

    public record ActivityFact(
            String factIdentity, BigDecimal rawValue, Instant eventTime, Instant receivedTime,
            String qualityLevel, String qualityPolicyVersion, boolean late,
            Instant sourcePeriodStart, Instant sourcePeriodEnd, String sourcePeriodTimezone,
            String periodDefinitionVersion) {
    }

    public record MeterEventEvidence(
            String eventId, String eventVersionId, String buildingId, String meterPointId,
            MeterEventType eventType, Instant occurredAt,
            EvidenceStatus status, BigDecimal preEventReading, BigDecimal postEventReading,
            BigDecimal rolloverModulus, String oldMeterId, String newMeterId,
            String relationVersionBefore, String relationVersionAfter,
            String evidenceReference, long createdBy, Long approvedBy, boolean simulationFlag) {
    }

    public record CorrectionEvidence(
            String correctionId, String correctionVersionId, String originalFactIdentity,
            BigDecimal originalValue, BigDecimal correctedValue, String correctionReason,
            EvidenceStatus status, String evidenceReference, long createdBy, Long approvedBy,
            boolean qualityGatePassed) {
    }

    public record IntegrationPolicy(
            String policyVersionId, IntegrationMethod method, long maximumGapSeconds,
            BigDecimal minimumCoverageRatio, BoundaryHandling boundaryHandling,
            EvidenceStatus status) {
    }

    public record MeteringAssignmentEvidence(
            String relationVersionId, long relationModelRevision, String assignmentItemId,
            String meteringBoundaryId, String targetNodeId, String allocationStatus,
            String confirmationStatus) {
    }

    public record AggregationInput(
            AggregationQuery query, MeasurementContext measurement,
            MeteringAssignmentEvidence assignment, Instant activityWatermark,
            List<ActivityFact> facts, List<MeterEventEvidence> meterEvents,
            List<CorrectionEvidence> corrections, IntegrationPolicy integrationPolicy) {
        public AggregationInput {
            facts = List.copyOf(facts);
            meterEvents = List.copyOf(meterEvents);
            corrections = List.copyOf(corrections);
        }
    }

    public record AggregationResult(
            String resultNature, String buildingId, String pointId, String energyItemCode,
            ValueSemantics valueSemantics, BigDecimal quantity, String resultUnitCode,
            BigDecimal coverageRatio, long maximumObservedGapSeconds,
            ResultCompleteness completeness, Instant calculationAsOf, Instant activityWatermark,
            String relationVersionId, String pointBindingVersionId,
            List<String> qualityPolicyVersions, List<String> meterEventVersions,
            List<String> correctionVersions, String integrationPolicyVersionId) {
    }
}
