package com.platform.iot.formula.model;

public record IndicatorMinuteResult(
        String indicatorId,
        String indicatorCode,
        String buildingId,
        String systemGroupId,
        String equipId,
        long minuteStart,
        double value,
        int dataQuality,
        String formulaVersion,
        long calculatedAt) {}
