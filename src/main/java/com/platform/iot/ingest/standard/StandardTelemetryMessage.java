package com.platform.iot.ingest.standard;

import com.platform.iot.identity.DeviceIdentityKey;

import java.util.List;
import java.math.BigDecimal;
import java.time.Instant;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 本地平台接收的标准多指标报文契约。
 *
 * <p>建筑和平台设备不在报文中传递，必须由本地预注册身份解析；eventTime 是设备事件时间，
 * receivedTime 是云端适配器接收时间，本地迟到判断另用 MQTT 本地接收时间。</p>
 */
public record StandardTelemetryMessage(
        String standardVersion,
        String profileCode,
        int profileVersion,
        DeviceIdentityKey deviceIdentity,
        long eventTime,
        long receivedTime,
        String timeSource,
        Long seq,
        List<StandardMetric> metrics,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ElectricityInterval electricityInterval,
        @JsonInclude(JsonInclude.Include.NON_NULL) String bootId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sourceMessageId
) {
    public StandardTelemetryMessage(String standardVersion,String profileCode,int profileVersion,DeviceIdentityKey deviceIdentity,long eventTime,long receivedTime,
            String timeSource,Long seq,List<StandardMetric> metrics,ElectricityInterval electricityInterval) {
        this(standardVersion,profileCode,profileVersion,deviceIdentity,eventTime,receivedTime,timeSource,seq,metrics,electricityInterval,null,null);
    }
    public StandardTelemetryMessage(String standardVersion,String profileCode,int profileVersion,
                                    DeviceIdentityKey deviceIdentity,long eventTime,long receivedTime,
                                    String timeSource,Long seq,List<StandardMetric> metrics) {
        this(standardVersion,profileCode,profileVersion,deviceIdentity,eventTime,receivedTime,timeSource,seq,metrics,null);
    }

    /** 周期电量与CO2因子共享明确区间，单位固定；此载荷与普通metrics互斥。 */
    public record ElectricityInterval(
            @NotBlank @Size(max=80) String pointId,
            Instant startInclusive,
            Instant endExclusive,
            @Min(0) long revision,
            @DecimalMin("0") @Digits(integer=12,fraction=12) BigDecimal electricityKwh,
            @DecimalMin("0") @Digits(integer=12,fraction=18) BigDecimal factorKgCo2PerKwh,
            @NotNull @Pattern(regexp="kWh") String electricityUnit,
            @NotNull @Pattern(regexp="kgCO2/kWh") String factorUnit,
            @NotBlank @Pattern(regexp="SOURCE_LABELLED|SIMULATED") String sourceNature,
            @NotBlank @Size(max=500) String sourceReference) { }
}
