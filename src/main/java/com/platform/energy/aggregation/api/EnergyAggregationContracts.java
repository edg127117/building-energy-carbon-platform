package com.platform.energy.aggregation.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** 计量事件、修正、积分策略和研发聚合的稳定 HTTP DTO。 */
public final class EnergyAggregationContracts {
    private EnergyAggregationContracts() {
    }

    public record CreateMeterEventVersionRequest(
            String eventId,
            @NotBlank String buildingId,
            @NotBlank String meterPointId,
            @NotBlank String eventType,
            @NotNull Instant occurredAt,
            BigDecimal preEventReading,
            BigDecimal postEventReading,
            BigDecimal rolloverModulus,
            @Size(max = 64) String oldMeterId,
            @Size(max = 64) String newMeterId,
            @Size(max = 32) String relationVersionBefore,
            @Size(max = 32) String relationVersionAfter,
            @NotBlank String sourceType,
            @NotBlank @Size(max = 500) String evidenceReference,
            @NotNull Boolean simulationFlag) {
    }

    public record MeterEventVersionView(
            String eventId, String eventVersionId, int versionNo, String buildingId,
            String meterPointId, String eventType, Instant occurredAt,
            BigDecimal preEventReading, BigDecimal postEventReading, BigDecimal rolloverModulus,
            String oldMeterId, String newMeterId, String relationVersionBefore,
            String relationVersionAfter, String status, String sourceType,
            String evidenceReference, boolean simulationFlag, int configRevision,
            long createdBy, LocalDateTime createdAt, Long approvedBy,
            LocalDateTime approvedAt, String reviewComment) {
    }

    public record CreateCorrectionVersionRequest(
            String correctionId,
            @NotBlank String buildingId,
            @NotBlank String meterPointId,
            @NotBlank @Size(max = 160) String originalFactIdentity,
            @NotNull BigDecimal originalValue,
            @NotNull BigDecimal correctedValue,
            @NotBlank @Size(max = 500) String correctionReason,
            @NotBlank String sourceType,
            @NotBlank @Size(max = 500) String evidenceReference) {
    }

    public record CorrectionVersionView(
            String correctionId, String correctionVersionId, int versionNo, String buildingId,
            String meterPointId, String originalFactIdentity, BigDecimal originalValue,
            BigDecimal correctedValue, String correctionReason, String status, String sourceType,
            String evidenceReference, boolean qualityGatePassed, String qualityPolicyVersion,
            int configRevision, long createdBy, LocalDateTime createdAt, Long approvedBy,
            LocalDateTime approvedAt, String reviewComment) {
    }

    public record CreateIntegrationPolicyVersionRequest(
            @NotBlank String buildingId,
            @NotBlank String meterPointId,
            @NotBlank String integrationMethod,
            @NotNull @Min(1) Long maximumGapSeconds,
            @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal minimumCoverageRatio,
            @NotBlank String boundaryHandling,
            @NotBlank String sourceType,
            @NotBlank @Size(max = 500) String evidenceReference,
            @NotNull LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo) {
    }

    public record IntegrationPolicyVersionView(
            String policyId, String policyVersionId, int versionNo, String buildingId,
            String meterPointId, String integrationMethod, long maximumGapSeconds,
            BigDecimal minimumCoverageRatio, String boundaryHandling, String status,
            String sourceType, String evidenceReference, LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo, int configRevision, long createdBy,
            LocalDateTime createdAt, Long approvedBy, LocalDateTime approvedAt,
            String reviewComment) {
    }

    public record ApproveEvidenceRequest(
            @NotNull @Min(0) Integer expectedRevision,
            @NotBlank @Size(max = 500) String reviewComment) {
    }

    public record AggregationSimulationRequest(
            @NotBlank String buildingId,
            @NotBlank String pointId,
            @NotNull Instant startInclusive,
            @NotNull Instant endExclusive,
            @NotNull Instant calculationAsOf) {
    }

    public record AggregationSimulationView(
            String resultNature, String buildingId, String pointId, String energyItemCode,
            String valueSemantics, BigDecimal quantity, String resultUnitCode,
            BigDecimal coverageRatio, long maximumObservedGapSeconds, String completeness,
            Instant calculationAsOf, Instant activityWatermark, String relationVersionId,
            String pointBindingVersionId, List<String> qualityPolicyVersions,
            List<String> meterEventVersions, List<String> correctionVersions,
            String integrationPolicyVersionId) {
    }

    public record EnergyAggregationApiError(
            int code, String errorCode, String msg, boolean success, String traceId) {
    }
}
