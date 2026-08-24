package com.platform.iot.formula.model;

/** TDengine 中按“指标 + 分钟”覆盖更新的轻量当前状态投影。 */
public record IndicatorMinuteState(
        String indicatorId,
        String indicatorCode,
        String buildingId,
        String systemGroupId,
        String equipId,
        long minuteStart,
        String currentStatus,
        String sourceFactId,
        String attemptId,
        long stateUpdatedAt,
        long configRevision) {
}
