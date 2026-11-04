package com.platform.carbon;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 动态监测契约。因子属于明确时段和电量来源，CO2质量不扩展为其他温室气体覆盖。 */
public final class DynamicElectricityContracts {
    private DynamicElectricityContracts() { }

    @Schema(name = "DynamicElectricityObservation")
    public record Observation(
            @NotBlank @Size(max = 80) String pointId,
            @NotNull Instant startInclusive, @NotNull Instant endExclusive,
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 18)
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal factorKgCo2PerKwh) { }

    /** 星号表示该建筑边界共同适用的因子。具体测点因子一经提供，不以共同因子掩盖其缺口。 */
    @Schema(name = "DynamicElectricityImportRequest")
    public record ImportRequest(
            @NotBlank @Size(max = 100) String idempotencyKey,
            @NotBlank @Size(max = 500) String sourceReference,
            @NotBlank @Size(max = 64) String sourceRegion,
            @NotBlank @Size(max = 64) String scenario,
            @NotBlank @Pattern(regexp = "SOURCE_LABELLED|SIMULATED") String sourceNature,
            @NotBlank @Pattern(regexp = "kgCO2/kWh") String unit,
            @NotEmpty @Size(max = 3000) List<@NotNull @Valid Observation> observations) { }

    @Schema(name = "DynamicElectricityCalculationRequest")
    public record CalculationRequest(@NotBlank @Pattern(regexp = "HOUR|DAY|MONTH") String grain,
            @NotNull Instant startInclusive, @NotNull Instant endExclusive,
            @Pattern(regexp = "REVIEWED_INTERVAL|RAW_CUMULATIVE") String sourceKind) {
        public CalculationRequest {
            if (sourceKind == null) sourceKind = "REVIEWED_INTERVAL";
        }
        public CalculationRequest(String grain, Instant startInclusive, Instant endExclusive) {
            this(grain, startInclusive, endExclusive, "REVIEWED_INTERVAL");
        }
    }

    @Schema(name = "DynamicElectricityImportReceipt")
    public record ImportReceipt(String importId, int observations, String evidenceHash) { }

    @Schema(name = "DynamicElectricityPoint")
    public record Point(String period, Instant startInclusive, Instant endExclusive,
            String status, String reasonCode,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal electricityKwh,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal emissionTco2,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal factorKgCo2PerKwh,
            List<String> grains, List<String> resultIds, List<String> sourceNatures) { }

    @Schema(name = "DynamicElectricityView")
    public record View(String buildingId, String periodType, String period, String timezoneId,
            String emissionUnit, String factorUnit, String resultNature, List<Point> points) { }
}
