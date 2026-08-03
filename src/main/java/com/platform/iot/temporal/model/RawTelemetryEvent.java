package com.platform.iot.temporal.model;

/**
 * 写入 {@code st_raw_event} 的一条已校验真实设备事件。
 *
 * <p>标准测点及设备身份来自 MySQL 配置，不直接信任 MQTT 外部点码；
 * {@code eventTime} 决定所属自然分钟，{@code receivedTime} 用于延迟审计，
 * {@code late} 表示到达时间已经越过该分钟的正常冻结边界。迟到标记不会降低真实
 * 数据的 Q0 质量，只决定由正常冻结还是迟到修正链路重新聚合。</p>
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
