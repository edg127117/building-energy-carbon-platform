package com.platform.carbon;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 独立小时监测计算契约；它不是月度正式报告，也不把调用方声明的来源性质当作现场验收。 */
public final class HourlyCarbonContracts {
    private HourlyCarbonContracts() { }
    @Schema(name="HourlyCarbonCalculationRequest")
    public record Request(@NotBlank @Size(max=32) String buildingId, @NotNull LocalDate date,
            @NotNull Instant calculationAsOf, @NotBlank @Size(max=100) String idempotencyKey,
            @NotBlank @Pattern(regexp="RAW_CUMULATIVE|REVIEWED_INTERVAL") String sourceKind,
            @NotBlank @Pattern(regexp="SIMULATED|SOURCE_LABELLED") String declaredSourceNature,
            @NotBlank @Size(max=500) String sourceReference,
            @NotBlank @Pattern(regexp="FORMAL|DEVELOPMENT_REFERENCE") String factorUsageNature) { }

    @Schema(name="HourlyCarbonPoint")
    public record Point(int hour, Instant startInclusive, Instant endExclusive, String status,
            String reasonCode, String reason,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal electricityKwh,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal emissionKgCo2e,
            List<String> sourceSnapshotIds, List<String> factorVersionIds, List<String> formulaVersionIds,
            List<String> gwpVersionIds, List<String> roundingPolicyVersionIds, List<String> qualityPolicyVersions, List<String> projectVersionIds,
            List<String> relationVersionIds, List<String> gasCoverages, String evidenceHash) { }

    @Schema(name="HourlyCarbonQueryView")
    public record View(String buildingId, LocalDate date, String timezoneId, String unit,
            String scope, String sourceKind, String declaredSourceNature, String factorUsageNature,
            String resultNature, String state, String calculationBatchId, Integer revision,
            Instant calculationAsOf, Instant activityWatermark, String activityWatermarkMeaning,
            int dueHours, int availableHours,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal dailyTotal,
            List<Point> points, Instant capturedAt, String evidenceHash) { }

    static View identity(View v,String batchId,int revision) {
        return new View(v.buildingId(),v.date(),v.timezoneId(),v.unit(),v.scope(),v.sourceKind(),v.declaredSourceNature(),v.factorUsageNature(),
                v.resultNature(),v.state(),batchId,revision,v.calculationAsOf(),v.activityWatermark(),v.activityWatermarkMeaning(),
                v.dueHours(),v.availableHours(),v.dailyTotal(),v.points(),v.capturedAt(),v.evidenceHash());
    }
}
