package com.platform.iot.quality;

/**
 * 已通过平台身份、设备归属、时间格式和数值边界校验的 HVAC 真实遥测。
 *
 * <p>标准身份来自 MySQL 快照，来源身份保留用于重复/冲突判断和追溯；接入服务只把
 * 该类型转换成 {@code RawTelemetryEvent}，从类型边界阻止未校验 Map 直接进入
 * TDengine。设备采集时间和服务器接收时间必须同时保留，分别决定分钟归属和迟到。</p>
 *
 * @param pointId 平台内部测点 ID
 * @param pointCode 平台标准点码
 * @param sourceSystem 服务端配置的来源命名空间
 * @param sourcePointCode 设备上报的外部点码
 * @param sourceDeviceId 设备上报的 deviceId
 * @param buildingId 标准测点所属建筑
 * @param systemGroupId 标准测点所属系统组，可为空
 * @param equipId 标准测点所属设备；环境点可为空
 * @param equipCode 标准设备编码；环境点可为空
 * @param familyCode 标准设备或环境族编码
 * @param componentCode 标准组件编码；环境点可为空
 * @param suffixCode 标准物理量后缀
 * @param value 已通过有限值及配置上下限校验的实测值
 * @param eventTime 设备采集时间，Unix 毫秒
 * @param receivedTime 平台接收时间，Unix 毫秒
 * @param dataQuality 固定为真实质量 0
 * @param isForCalc MySQL 配置的计算参与标记
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
