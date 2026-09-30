package com.platform.hvac.asset.service;

import com.platform.hvac.asset.api.MeterElectricityContracts.Day;
import com.platform.hvac.asset.api.MeterElectricityContracts.PeriodSummary;
import com.platform.iot.qualityusage.QualityUsageModels.Decision;
import com.platform.iot.qualityusage.QualityUsageModels.ResolutionContext;
import com.platform.iot.qualityusage.QualityUsagePolicyResolver;
import com.platform.iot.temporal.model.RawTelemetryEvent;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.platform.iot.qualityusage.QualityUsageModels.POINT_HISTORY_VIEW;

/** 从单表原始正向累计读数生成完整自然日用电量，不执行跨表或单台被测设备分摊。 */
final class MeterElectricityCalculator {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final long BOUNDARY_WINDOW_MS = 6 * 60_000L;

    private MeterElectricityCalculator() { }

    static PeriodSummary summarize(List<Day> days) {
        List<Day> available = days.stream()
                .filter(day -> "AVAILABLE".equals(day.status()) && day.kwh() != null)
                .toList();
        Double measuredKwh = available.isEmpty() ? null
                : available.stream().mapToDouble(Day::kwh).sum();
        return new PeriodSummary(measuredKwh, available.size(), days.size());
    }

    static List<Day> calculate(List<RawTelemetryEvent> events, LocalDate today, int days,
                               String pointId, QualityUsagePolicyResolver quality,
                               ResolutionContext context) {
        List<Day> result = new ArrayList<>();
        Day previous = evaluate(events, today.minusDays(days + 1L), pointId, quality, context);
        for (int offset = days; offset >= 1; offset--) {
            Day current = evaluate(events, today.minusDays(offset), pointId, quality, context);
            if (current.kwh() != null && previous.kwh() != null) {
                double change = current.kwh() - previous.kwh();
                current = new Day(current.date(), current.kwh(), current.status(), current.reason(),
                        current.startSampleTime(), current.endSampleTime(), change,
                        previous.kwh() > 0 ? change / previous.kwh() * 100 : null);
            }
            result.add(current);
            previous = current;
        }
        return List.copyOf(result);
    }

    private static Day evaluate(List<RawTelemetryEvent> events, LocalDate date, String pointId,
                                QualityUsagePolicyResolver quality, ResolutionContext context) {
        long start = date.atStartOfDay(ZONE).toInstant().toEpochMilli();
        long end = date.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        RawTelemetryEvent first = boundary(events, start);
        RawTelemetryEvent last = boundary(events, end);
        if (first == null || last == null) return missing(date, "BOUNDARY_SAMPLE_MISSING", first, last);
        if (first.eventTime() >= last.eventTime()) return missing(date, "BOUNDARY_SAMPLE_MISSING", first, last);
        String identity = identity(first);
        if (identity == null) return unresolved(date, "SOURCE_IDENTITY_MISSING", first, last);
        RawTelemetryEvent previous = null;
        for (RawTelemetryEvent event : events) {
            if (event.eventTime() < first.eventTime() || event.eventTime() > last.eventTime()) continue;
            if (!Objects.equals(identity, identity(event))) return unresolved(date, "SOURCE_CHANGED", first, last);
            if (!Double.isFinite(event.value()) || event.value() < 0) return unresolved(date, "INVALID_READING", first, last);
            var decision = quality.resolve(context, pointId, POINT_HISTORY_VIEW,
                    QualityUsagePolicyResolver.alignMinute(event.eventTime()), event.dataQuality());
            if (decision.decision() != Decision.ALLOW) return missing(date, "QUALITY_BLOCKED", first, last);
            if (previous != null && event.value() < previous.value()) {
                return unresolved(date, "COUNTER_DECREASED", first, last);
            }
            previous = event;
        }
        return new Day(date, last.value() - first.value(), "AVAILABLE", "NEAR_MIDNIGHT_SAMPLES",
                first.eventTime(), last.eventTime(), null, null);
    }

    private static RawTelemetryEvent boundary(List<RawTelemetryEvent> events, long midnight) {
        RawTelemetryEvent candidate = null;
        for (RawTelemetryEvent event : events) {
            if (event.eventTime() > midnight) break;
            if (event.eventTime() >= midnight - BOUNDARY_WINDOW_MS) candidate = event;
        }
        return candidate;
    }

    private static String identity(RawTelemetryEvent event) {
        if (event.sourceSystem() == null || event.sourceSystem().isBlank()
                || event.sourceDeviceId() == null || event.sourceDeviceId().isBlank()
                || event.sourcePointCode() == null || event.sourcePointCode().isBlank()) return null;
        return event.sourceSystem() + "\u001f" + event.sourceDeviceId() + "\u001f" + event.sourcePointCode();
    }

    private static Day missing(LocalDate date, String reason, RawTelemetryEvent first, RawTelemetryEvent last) {
        return new Day(date, null, reason.equals("QUALITY_BLOCKED") ? "QUALITY_BLOCKED" : "MISSING", reason,
                first == null ? null : first.eventTime(), last == null ? null : last.eventTime(), null, null);
    }

    private static Day unresolved(LocalDate date, String reason, RawTelemetryEvent first, RawTelemetryEvent last) {
        return new Day(date, null, "UNRESOLVED", reason, first.eventTime(), last.eventTime(), null, null);
    }
}
