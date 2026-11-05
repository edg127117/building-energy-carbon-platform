package com.platform.alarm;

import com.platform.iot.temporal.model.RawTelemetryEvent;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.platform.alarm.AlarmModels.*;

/** 纯判定辅助方法；返回null表示当前证据不足以作出结论。 */
final class AlarmRuleEvaluator {
    private AlarmRuleEvaluator() { }

    static Boolean condition(ConditionNode node, Map<String, BigDecimal> values) {
        if (node == null) return null;
        if (node.condition() != null) {
            BigDecimal value = values.get(node.condition().pointId());
            return value == null ? null : compare(value, node.condition());
        }
        boolean anyUnknown = false;
        for (ConditionNode child : node.children()) {
            Boolean childValue = condition(child, values);
            if (childValue == null) anyUnknown = true;
            else if ("ALL".equals(node.logic()) && !childValue) return false;
            else if ("ANY".equals(node.logic()) && childValue) return true;
        }
        if (anyUnknown) return null;
        return "ANY".equals(node.logic()) ? false : true;
    }

    static boolean compare(BigDecimal value, Condition condition) {
        int cmp = value.compareTo(condition.value());
        return switch (condition.operator()) {
            case "GT" -> cmp > 0;
            case "GE" -> cmp >= 0;
            case "LT" -> cmp < 0;
            case "LE" -> cmp <= 0;
            case "EQ" -> cmp == 0;
            case "NE" -> cmp != 0;
            case "BETWEEN" -> condition.upperValue() != null && cmp >= 0 && value.compareTo(condition.upperValue()) <= 0;
            case "OUTSIDE" -> condition.upperValue() != null && (cmp < 0 || value.compareTo(condition.upperValue()) > 0);
            default -> false;
        };
    }

    static Boolean jump(List<RawTelemetryEvent> history, long now, long windowMillis, long intervalMillis,
            long allowedDelayMillis, long staleMillis, BigDecimal tolerance, String mode) {
        List<RawTelemetryEvent> samples = validHistory(history, now, windowMillis, intervalMillis, allowedDelayMillis, staleMillis);
        if (samples == null || samples.size() < 2) return null;
        BigDecimal baseline = BigDecimal.valueOf(samples.getFirst().value());
        BigDecimal current = BigDecimal.valueOf(samples.getLast().value());
        return exceeds(baseline, current, tolerance, mode);
    }

    static Boolean stuck(List<RawTelemetryEvent> values, List<RawTelemetryEvent> running, long now, long windowMillis,
            long intervalMillis, long allowedDelayMillis, long staleMillis, BigDecimal tolerance, String mode,
            BigDecimal runningValue) {
        return stuck(values,running,now,windowMillis,intervalMillis,allowedDelayMillis,staleMillis,tolerance,mode,runningValue,
                intervalMillis,allowedDelayMillis,staleMillis);
    }
    static Boolean stuck(List<RawTelemetryEvent> values,List<RawTelemetryEvent> running,long now,long windowMillis,
            long intervalMillis,long allowedDelayMillis,long staleMillis,BigDecimal tolerance,String mode,BigDecimal runningValue,
            long runningIntervalMillis,long runningDelayMillis,long runningStaleMillis) {
        List<RawTelemetryEvent> samples = validHistory(values, now, windowMillis, intervalMillis, allowedDelayMillis, staleMillis);
        List<RawTelemetryEvent> states = validHistory(running, now, windowMillis, runningIntervalMillis, runningDelayMillis, runningStaleMillis);
        if (samples == null || states == null || samples.size() < 2 || states.isEmpty()) return null;
        if (states.stream().anyMatch(sample -> BigDecimal.valueOf(sample.value()).compareTo(runningValue) != 0)) return false;
        BigDecimal baseline = BigDecimal.valueOf(samples.getFirst().value());
        for (RawTelemetryEvent sample : samples.subList(1, samples.size())) {
            Boolean changed = exceeds(baseline, BigDecimal.valueOf(sample.value()), tolerance, mode);
            if (changed == null) return null;
            if (changed) return false;
        }
        return true;
    }

    private static List<RawTelemetryEvent> validHistory(List<RawTelemetryEvent> history, long now, long windowMillis,
            long intervalMillis, long allowedDelayMillis, long staleMillis) {
        // 坏质量、迟到、重复或缺失样本都会打断连续窗口，不能先过滤后再宣称连续。
        if (history == null || intervalMillis <= 0 || windowMillis <= 0 || staleMillis <= 0) return null;
        long from = now - windowMillis;
        if (history.stream().filter(Objects::nonNull).anyMatch(sample -> sample.eventTime() > now)) return null;
        List<RawTelemetryEvent> samples = history.stream().filter(Objects::nonNull)
                .filter(sample -> sample.eventTime() >= from && sample.eventTime() <= now)
                .sorted(Comparator.comparingLong(RawTelemetryEvent::eventTime)).toList();
        if (samples.stream().anyMatch(sample -> sample.late() || sample.dataQuality() != 0 || !Double.isFinite(sample.value())
                || sample.receivedTime() < sample.eventTime() || sample.receivedTime() > now
                || sample.receivedTime() - sample.eventTime() > allowedDelayMillis)) return null;
        if (samples.size() < 2 || now - samples.getLast().eventTime() > staleMillis
                || samples.getFirst().eventTime() > from + intervalMillis) return null;
        for (int index = 1; index < samples.size(); index++) {
            long gap = samples.get(index).eventTime() - samples.get(index - 1).eventTime();
            if (gap <= 0 || gap > intervalMillis + allowedDelayMillis) return null;
        }
        if (samples.getLast().eventTime() - samples.getFirst().eventTime() < windowMillis - intervalMillis) return null;
        return samples;
    }

    private static Boolean exceeds(BigDecimal baseline, BigDecimal current, BigDecimal tolerance, String mode) {
        BigDecimal difference = current.subtract(baseline).abs();
        if ("ABS".equals(mode)) return difference.compareTo(tolerance) > 0;
        if (!"PERCENT".equals(mode) || baseline.signum() == 0) return null;
        BigDecimal percent = difference.multiply(BigDecimal.valueOf(100)).divide(baseline.abs(), 8, java.math.RoundingMode.HALF_UP);
        return percent.compareTo(tolerance) > 0;
    }
}
