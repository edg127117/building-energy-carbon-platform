package com.platform.iot.daikin.runtime;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/** 厂家自然日/月/年使用已确认的统计时区；结束时刻排他，夏令时不假定每天固定 24 小时。 */
public record DaikinRuntimePeriod(Granularity granularity, LocalDate start, ZoneId zone) {
    public enum Granularity { DAY, MONTH, YEAR }

    public DaikinRuntimePeriod {
        Objects.requireNonNull(granularity);
        Objects.requireNonNull(start);
        Objects.requireNonNull(zone);
        if (granularity == Granularity.MONTH && start.getDayOfMonth() != 1
                || granularity == Granularity.YEAR && start.getDayOfYear() != 1) {
            throw new IllegalArgumentException("DAIKIN_RUNTIME_INVALID_PERIOD");
        }
    }

    public LocalDate end() {
        return switch (granularity) {
            case DAY -> start.plusDays(1);
            case MONTH -> start.plusMonths(1);
            case YEAR -> start.plusYears(1);
        };
    }

    public long startMillis() { return start.atStartOfDay(zone).toInstant().toEpochMilli(); }
    public long endMillis() { return end().atStartOfDay(zone).toInstant().toEpochMilli(); }
}
