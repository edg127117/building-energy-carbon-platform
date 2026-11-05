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

    /** HTTP使用显式修订并可同版本拆包；MQTT内部可携带来源顺序，由平台分配周期业务版本。 */
    @Schema(name = "DynamicElectricityDeviceIntervalRequest")
    public record DeviceIntervalRequest(
            @NotNull @Valid com.platform.iot.identity.DeviceIdentityKey deviceIdentity,
            @NotBlank @Size(max=64) String profileCode,
            @NotBlank @Size(max=80) String pointId,
            @NotNull Instant startInclusive, @NotNull Instant endExclusive,
            @Min(1) long revision,
            @DecimalMin("0") @Digits(integer=12,fraction=12) BigDecimal electricityKwh,
            @DecimalMin("0") @Digits(integer=12,fraction=18) BigDecimal factorKgCo2PerKwh,
            @NotBlank @Pattern(regexp="SOURCE_LABELLED|SIMULATED") String sourceNature,
            @NotBlank @Size(max=500) String sourceReference,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) SourceOrder sourceOrder) {
        public DeviceIntervalRequest(com.platform.iot.identity.DeviceIdentityKey deviceIdentity,String profileCode,String pointId,Instant startInclusive,Instant endExclusive,
                long revision,BigDecimal electricityKwh,BigDecimal factorKgCo2PerKwh,String sourceNature,String sourceReference) {
            this(deviceIdentity,profileCode,pointId,startInclusive,endExclusive,revision,electricityKwh,factorKgCo2PerKwh,sourceNature,sourceReference,null);
        }
    }

    /** 来源顺序与平台修订分开保存；跨启动不可比较，适配器轮询序号不能当作设备更正顺序。 */
    public record SourceOrder(String bootId,Long seq,String messageId,String sequenceSource) {
        public boolean reliable() {return "DEVICE_FIELDS".equals(sequenceSource)&&bootId!=null&&!bootId.isBlank()&&seq!=null;}
        public boolean comparable(SourceOrder other) {
            return other!=null&&reliable()&&other.reliable()&&bootId.equals(other.bootId);
        }
    }

    public record DeviceIntervalReceipt(String buildingId, Instant startInclusive, Instant endExclusive,
                                        long revision, String state) { }

    @Schema(name = "DynamicElectricityImportReceipt")
    public record ImportReceipt(String importId, int observations, String evidenceHash) { }

    @Schema(name = "DynamicElectricityPoint")
    public record Point(String period, Instant startInclusive, Instant endExclusive,
            String status, String reasonCode,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal electricityKwh,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal emissionTco2,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal factorKgCo2PerKwh,
            List<String> grains, List<String> resultIds, List<String> sourceNatures, Instant computedThrough) {
        public Point(String period, Instant startInclusive, Instant endExclusive, String status, String reasonCode,
                     BigDecimal electricityKwh, BigDecimal emissionTco2, BigDecimal factorKgCo2PerKwh,
                     List<String> grains, List<String> resultIds, List<String> sourceNatures) {
            this(period,startInclusive,endExclusive,status,reasonCode,electricityKwh,emissionTco2,
                    factorKgCo2PerKwh,grains,resultIds,sourceNatures,null);
        }
    }

    @Schema(name = "DynamicElectricityView")
    public record View(String buildingId, String periodType, String period, String timezoneId,
            String emissionUnit, String factorUnit, String resultNature, List<Point> points) { }
}
