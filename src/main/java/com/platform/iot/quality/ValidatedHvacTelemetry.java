package com.platform.iot.quality;

/**
 * 已通过平台校验的 HVAC 真实遥测数据。
 *
 * <p>只有这个类型允许进入 TDengine 正常数据表，从类型边界上避免未校验报文污染计算链路。</p>
 */
public record ValidatedHvacTelemetry(
        String pointId,
        String pointCode,
        String sourceSystem,
        String sourcePointCode,
        String sourceDeviceId,
        String buildingId,
        String systemGroupId,
        String equipId,
        String equipCode,
        String familyCode,
        String componentCode,
        String suffixCode,
        double value,
        long eventTime,
        long receivedTime,
        int dataQuality,
        int isForCalc
) {
}
