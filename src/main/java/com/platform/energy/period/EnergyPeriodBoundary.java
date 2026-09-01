package com.platform.energy.period;

import com.platform.energy.period.EnergyPeriodModels.PeriodType;
import com.platform.energy.period.EnergyPeriodModels.PeriodWindow;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;

import static com.platform.energy.period.EnergyPeriodErrors.VALIDATION_FAILED;
import static com.platform.energy.period.EnergyPeriodErrors.error;

/** 以版本化时区生成自然日、月、年的半开事件时间范围。 */
public final class EnergyPeriodBoundary {
    private EnergyPeriodBoundary() {
    }

    public static PeriodWindow resolve(String type, LocalDate periodDate, String timezoneId) {
        if (periodDate == null) {
            throw error(400, VALIDATION_FAILED, "周期日期不能为空");
        }
        PeriodType periodType;
        ZoneId zone;
        try {
            periodType = PeriodType.valueOf(type == null ? "" : type.trim().toUpperCase());
            zone = ZoneId.of(timezoneId);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw error(400, VALIDATION_FAILED, "周期类型或时区无效");
        }
        LocalDate start = switch (periodType) {
            case DAY -> periodDate;
            case MONTH -> periodDate.withDayOfMonth(1);
            case YEAR -> periodDate.withDayOfYear(1);
        };
        if (!periodDate.equals(start)) {
            throw error(400, VALIDATION_FAILED, "月周期必须使用月初，年周期必须使用年初");
        }
        LocalDate end = switch (periodType) {
            case DAY -> start.plusDays(1);
            case MONTH -> start.plusMonths(1);
            case YEAR -> start.plusYears(1);
        };
        return new PeriodWindow(periodType, start.atStartOfDay(zone).toInstant(),
                end.atStartOfDay(zone).toInstant(), zone.getId());
    }
}
