package com.platform.iot.temporal.model;

/**
 * {@code st_raw_minute} 中一个标准测点的正式分钟值。
 *
 * <p>Q0 保存真实事件统计及首末接收时间；Q1/Q2 是质量补全值，样本数和接收时间
 * 为空语义并通过 {@code qualityTaskId} 关联生成任务。质量完成后，公式输入组装器
 * 按建筑、设备和测点语义键选择这些分钟值，供 COP、冷却塔、水泵和 AHU 指标计算。</p>
 */
public record RawMinuteAggregate(
        String pointId,
        String pointCode,
        String buildingId,
        String systemGroupId,
        String equipId,
        String equipCode,
        String familyCode,
        String componentCode,
        String suffixCode,
        int isForCalc,
        long minuteStart,
        double averageValue,
        double minimumValue,
        double maximumValue,
        int sampleCount,
        int dataQuality,
        Long firstReceivedTime,
        Long lastReceivedTime,
        long finalizedAt,
        String qualityTaskId
) {
    /**
     * 构造不绑定质量任务的真实分钟；生成的 Q1/Q2 必须使用完整构造器显式传入任务 ID。
     */
    public RawMinuteAggregate(
            String pointId,
            String pointCode,
            String buildingId,
            String systemGroupId,
            String equipId,
            String equipCode,
            String familyCode,
            String componentCode,
            String suffixCode,
            int isForCalc,
            long minuteStart,
            double averageValue,
            double minimumValue,
            double maximumValue,
            int sampleCount,
            int dataQuality,
            Long firstReceivedTime,
            Long lastReceivedTime,
            long finalizedAt) {
        this(pointId, pointCode, buildingId, systemGroupId, equipId, equipCode,
                familyCode, componentCode, suffixCode, isForCalc, minuteStart,
                averageValue, minimumValue, maximumValue, sampleCount, dataQuality,
                firstReceivedTime, lastReceivedTime, finalizedAt, null);
    }
}
