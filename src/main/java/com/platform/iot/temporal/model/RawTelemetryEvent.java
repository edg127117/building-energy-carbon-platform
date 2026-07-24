package com.platform.iot.temporal.model;

/**
 * TDengine 中的一条已校验真实事件。
 */
public record RawTelemetryEvent(
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
        int isForCalc,
        boolean late
) {
}
