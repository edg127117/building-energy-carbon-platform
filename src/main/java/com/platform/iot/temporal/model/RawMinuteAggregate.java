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
        long firstReceivedTime,
        long lastReceivedTime,
        long finalizedAt
) {
}
