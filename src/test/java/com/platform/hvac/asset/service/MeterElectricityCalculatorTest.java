package com.platform.hvac.asset.service;

import com.platform.hvac.asset.api.MeterElectricityContracts.Day;
import com.platform.iot.qualityusage.QualityUsageModels.Decision;
import com.platform.iot.qualityusage.QualityUsageModels.PolicySource;
import com.platform.iot.qualityusage.QualityUsageModels.Resolution;
import com.platform.iot.qualityusage.QualityUsageModels.ResolutionContext;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MeterElectricityCalculatorTest {
    private final QualityUsagePolicyResolver quality = mock(QualityUsagePolicyResolver.class);
    private final ResolutionContext context = mock(ResolutionContext.class);
    private final LocalDate today = LocalDate.of(2026, 9, 30);

    MeterElectricityCalculatorTest() {
        when(quality.resolve(eq(context), eq("point-1"), eq("POINT_HISTORY_VIEW"), anyLong(), anyInt()))
                .thenAnswer(invocation -> new Resolution(Decision.ALLOW, invocation.getArgument(4),
                        "POINT_HISTORY_VIEW", PolicySource.SYSTEM_DEFAULT_Q0_ONLY, null, 1, "ALLOWED"));
    }

    @Test
    void calculatesSingleMeterDayAndOnlySameMeterComparison() {
        var days = MeterElectricityCalculator.calculate(List.of(
                event(2026, 9, 27, 23, 58, 8),
                event(2026, 9, 28, 23, 58, 10),
                event(2026, 9, 29, 12, 0, 11),
                event(2026, 9, 29, 23, 58, 13)), today, 1, "point-1", quality, context);
        assertThat(days).hasSize(1);
        assertThat(days.getFirst().status()).isEqualTo("AVAILABLE");
        assertThat(days.getFirst().kwh()).isEqualTo(3.0);
        assertThat(days.getFirst().changeKwh()).isEqualTo(1.0);
        assertThat(days.getFirst().changePercent()).isEqualTo(50.0);
    }

    @Test
    void doesNotInventBoundaryValueOrHideReset() {
        var missing = MeterElectricityCalculator.calculate(List.of(
                event(2026, 9, 29, 12, 0, 11), event(2026, 9, 29, 23, 58, 13)),
                today, 1, "point-1", quality, context);
        assertThat(missing.getFirst().kwh()).isNull();
        assertThat(missing.getFirst().reason()).isEqualTo("BOUNDARY_SAMPLE_MISSING");

        var reset = MeterElectricityCalculator.calculate(List.of(
                event(2026, 9, 28, 23, 58, 10), event(2026, 9, 29, 10, 0, 2),
                event(2026, 9, 29, 23, 58, 13)), today, 1, "point-1", quality, context);
        assertThat(reset.getFirst().kwh()).isNull();
        assertThat(reset.getFirst().reason()).isEqualTo("COUNTER_DECREASED");
    }

    @Test
    void sourceChangeAndBlockedQualityProduceExplicitStates() {
        var changed = MeterElectricityCalculator.calculate(List.of(
                event(2026, 9, 28, 23, 58, 10),
                event(2026, 9, 29, 12, 0, 11, "another", 0),
                event(2026, 9, 29, 23, 58, 13)), today, 1, "point-1", quality, context);
        assertThat(changed.getFirst().reason()).isEqualTo("SOURCE_CHANGED");

        when(quality.resolve(eq(context), eq("point-1"), eq("POINT_HISTORY_VIEW"), anyLong(), eq(2)))
                .thenReturn(new Resolution(Decision.BLOCK, 2, "POINT_HISTORY_VIEW",
                        PolicySource.SYSTEM_DEFAULT_Q0_ONLY, null, 1, "BLOCKED"));
        var blocked = MeterElectricityCalculator.calculate(List.of(
                event(2026, 9, 28, 23, 58, 10), event(2026, 9, 29, 12, 0, 11, "meter", 2),
                event(2026, 9, 29, 23, 58, 13)), today, 1, "point-1", quality, context);
        assertThat(blocked.getFirst().status()).isEqualTo("QUALITY_BLOCKED");
        assertThat(blocked.getFirst().kwh()).isNull();
    }

    @Test
    void sumsOnlyAvailableDaysAndDoesNotTurnMissingDaysIntoZero() {
        var summary = MeterElectricityCalculator.summarize(List.of(
                new Day(today.minusDays(3), 1.91, "AVAILABLE", "NEAR_MIDNIGHT_SAMPLES", 1L, 2L, null, null),
                new Day(today.minusDays(2), null, "MISSING", "BOUNDARY_SAMPLE_MISSING", null, null, null, null),
                new Day(today.minusDays(1), 2.09, "AVAILABLE", "NEAR_MIDNIGHT_SAMPLES", 3L, 4L, null, null)));
        assertThat(summary.measuredKwh()).isEqualTo(4.0);
        assertThat(summary.availableDays()).isEqualTo(2);
        assertThat(summary.requestedDays()).isEqualTo(3);

        var empty = MeterElectricityCalculator.summarize(List.of(
                new Day(today.minusDays(1), null, "MISSING", "BOUNDARY_SAMPLE_MISSING", null, null, null, null)));
        assertThat(empty.measuredKwh()).isNull();
        assertThat(empty.availableDays()).isZero();
    }

    private static RawTelemetryEvent event(int year, int month, int day, int hour, int minute, double value) {
        return event(year, month, day, hour, minute, value, "meter", 0);
    }

    private static RawTelemetryEvent event(int year, int month, int day, int hour, int minute,
                                            double value, String sourceDevice, int dataQuality) {
        long at = LocalDateTime.of(year, month, day, hour, minute).atZone(MeterElectricityCalculator.ZONE)
                .toInstant().toEpochMilli();
        return new RawTelemetryEvent("point-1", "IDU1_EPP", "MQTT_STANDARD_V1", "/EPP", sourceDevice,
                "BLD001", "GROUP001", "meter-1", "IDU1", "IDU", null, "EPP", value, at, at,
                dataQuality, 0, false);
    }
}
