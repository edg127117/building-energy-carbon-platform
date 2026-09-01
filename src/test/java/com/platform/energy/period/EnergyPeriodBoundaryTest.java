package com.platform.energy.period;

import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnergyPeriodBoundaryTest {
    @Test
    void resolvesNaturalPeriodsAsHalfOpenEventTimeWindows() {
        var day = EnergyPeriodBoundary.resolve("DAY", LocalDate.of(2026, 8, 1), "Asia/Shanghai");
        var month = EnergyPeriodBoundary.resolve(
                "MONTH", LocalDate.of(2026, 8, 1), "Asia/Shanghai");
        var year = EnergyPeriodBoundary.resolve(
                "YEAR", LocalDate.of(2026, 1, 1), "Asia/Shanghai");

        assertThat(day.endExclusive()).isEqualTo(day.startInclusive().plusSeconds(86_400));
        assertThat(month.endExclusive()).isEqualTo(java.time.Instant.parse("2026-08-31T16:00:00Z"));
        assertThat(year.endExclusive()).isEqualTo(java.time.Instant.parse("2026-12-31T16:00:00Z"));
        assertThat(month.timezoneId()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void rejectsNonNaturalMonthAndYearStarts() {
        assertThatThrownBy(() -> EnergyPeriodBoundary.resolve(
                "MONTH", LocalDate.of(2026, 8, 2), "Asia/Shanghai"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(EnergyPeriodErrors.VALIDATION_FAILED);
    }
}
