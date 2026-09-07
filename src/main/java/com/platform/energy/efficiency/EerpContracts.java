package com.platform.energy.efficiency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import io.swagger.v3.oas.annotations.media.Schema;

/** 冷站后端契约；配置版本同时固定专业映射、物性、时间策略和研发评价依据。 */
public final class EerpContracts {
    private EerpContracts() {}
    public enum EquipmentRole { CHILLER, CHILLED_WATER_PUMP, COOLING_WATER_PUMP, TOWER_FAN }
    public enum SourceMode { METER_CUMULATIVE, FLOW_TEMPERATURE }
    @Schema(name="EerpEquipment")
    public record Equipment(String deviceId, EquipmentRole role) {}
    @Schema(name="EerpPoint")
    public record Point(String pointId, String dataType, String unit) {}
    @Schema(name="EerpCoolingSource")
    public record CoolingSource(String sourceId, SourceMode mode, Set<String> coveredChillerIds,
            String waterCircuitId, String meteringPosition, String flowMeterSide,
            Point meter, Point flow, Point supply, Point returnTemperature,
            CoolingComputationCore.Rules rules, String mappingEvidence) {}
    @Schema(name="EerpElectricitySource")
    public record ElectricitySource(String sourceId, Point meter, Set<String> coveredDeviceIds,
            String ownershipEvidence) {}
    @Schema(name="EerpConfiguration")
    public record Configuration(String buildingId, String stationId, String boundaryId,
            String relationVersionId, String timezoneId, String timezoneVersion,
            Instant effectiveFrom, Instant effectiveTo, List<Equipment> equipment,
            List<CoolingSource> coolingSources, List<ElectricitySource> electricitySources,
            String professionalEvidence, String evaluationRuleVersion, String evaluationReference) {}
    @Schema(name="EerpConfigView")
    public record ConfigView(String versionId, long revision, String status, long createdBy,
            Long submittedBy, Long approvedBy, Configuration configuration, String relationEvidence) {}
    @Schema(name="EerpReview")
    public record Review(long expectedRevision, String reason) {}
    @Schema(name="EerpPeriodRequest")
    public record PeriodRequest(String idempotencyKey, String configVersionId,
            Instant fromInclusive, Instant toExclusive, Instant asOf, String predecessorTaskId) {}
    @Schema(name="EerpAnnualRequest")
    public record AnnualRequest(String idempotencyKey, String buildingId, String stationId,
            int year, String timezoneId, String timezoneVersion, List<String> periodTaskIds,
            String predecessorTaskId) {}
    @Schema(name="EerpIssue")
    public record Issue(String code, String sourceId, Instant fromInclusive, Instant toExclusive) {}
    @Schema(name="EerpMeasure")
    public record Measure(String sourceId, String quantityType, Set<String> coverage,
            BigDecimal quantityKwh, BigDecimal coverageRatio, boolean complete,
            String numericSnapshotId, List<String> reasons) {}
    @Schema(name="EerpPeriodResult")
    public record PeriodResult(String configVersionId, String buildingId, String stationId,
            Instant fromInclusive, Instant toExclusive, Instant asOf, String timezoneId,
            String timezoneVersion, BigDecimal coolingKwh, BigDecimal electricityKwh,
            boolean complete, List<Measure> measures, List<Issue> issues,
            String resultNature, String sourceNature) {}
    @Schema(name="EerpAnnualResult")
    public record AnnualResult(String buildingId, String stationId, int year, String timezoneId,
            String timezoneVersion, Instant fromInclusive, Instant toExclusive,
            BigDecimal coolingKwh, BigDecimal electricityKwh, BigDecimal eerp,
            String calculationStatus, String completeness, String evaluationStatus,
            String evaluationBand, BigDecimal guidanceValue, BigDecimal advancedValue,
            List<String> ruleVersions, List<String> references, String standardVerification,
            List<String> inputTaskIds, List<Issue> issues, String formulaVersion, String resultNature) {}
    @Schema(name="EerpTaskView")
    public record TaskView(String taskId, String kind, String buildingId, String stationId,
            String status, long revision, long createdBy, Long submittedBy, Long approvedBy, String predecessorTaskId,
            Instant createdAt, String traceId, String failureCode, String evidenceHash,
            @io.swagger.v3.oas.annotations.media.Schema(oneOf={PeriodResult.class,AnnualResult.class},nullable=true) Object result) {}
    @Schema(name="EerpTraceView")
    public record TraceView(TaskView task, String requestJson, String evidenceJson) {}
}
