package com.platform.iot.temporal.model;

/**
 * 已冻结的单测点分钟汇总，是后续 COP 公式直接读取的正式输入。
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
     * 兼容仍只构造真实分钟数据的调用点；生成数据必须使用完整构造器显式传入任务 ID。
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
