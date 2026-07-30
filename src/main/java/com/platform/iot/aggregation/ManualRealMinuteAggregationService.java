package com.platform.iot.aggregation;

import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 为人工历史重算批量生成内存中的真实 Q0 分钟结果。
 *
 * <p>该服务只对编排器给出的有界上下文执行一次原始事件查询，并复用正常冻结的
 * 单测点聚合口径。它不写 TDengine、不发布分钟事件，最终应用和公式重算由上层
 * 批次执行器统一控制，避免聚合阶段产生跨库副作用。</p>
 */
@Service
@RequiredArgsConstructor
public class ManualRealMinuteAggregationService {

    private static final long MINUTE_MILLIS = 60_000L;

    private final HvacRawEventRepository rawRepository;
    private final DataPointConfigProvider configProvider;
    private final HvacPointMinuteAggregator pointMinuteAggregator;

    /**
     * 在指定半开区间内按“测点 + 自然分钟”重新聚合真实数据。
     *
     * <p>人工修正允许处理超过迟到数据自动修正窗口的旧历史。这里不限制区间长度，
     * 上层分块编排器负责根据插值上下文计算每次查询的有界范围。</p>
     */
    public List<RawMinuteAggregate> aggregate(
            Set<String> pointIds,
            long contextFromInclusive,
            long contextToExclusive,
            long finalizedAt) {
        Objects.requireNonNull(pointIds, "pointIds 不能为空");
        validateContext(contextFromInclusive, contextToExclusive);

        Map<String, PointRuntimeConfig> eligiblePoints =
                eligiblePoints(pointIds);
        // 人工历史重算必须恢复完整 Q0 证据，迟到事件也属于真实采集样本；
        // 这里不沿用自动迟到修正的时间窗口限制。
        List<RawTelemetryEvent> events = rawRepository.findWindow(
                contextFromInclusive, contextToExclusive, true);
        Map<PointMinute, List<RawTelemetryEvent>> grouped =
                groupEligibleEvents(
                        events, eligiblePoints,
                        contextFromInclusive, contextToExclusive);

        List<RawMinuteAggregate> result = new ArrayList<>(grouped.size());
        for (Map.Entry<PointMinute, List<RawTelemetryEvent>> entry
                : grouped.entrySet()) {
            PointMinute key = entry.getKey();
            result.add(pointMinuteAggregator.aggregate(
                    eligiblePoints.get(key.pointId()),
                    key.minuteStart(),
                    entry.getValue(),
                    finalizedAt));
        }
        result.sort(Comparator
                .comparingLong(RawMinuteAggregate::minuteStart)
                .thenComparing(RawMinuteAggregate::pointId));
        return List.copyOf(result);
    }

    private void validateContext(long fromInclusive, long toExclusive) {
        if (Math.floorMod(fromInclusive, MINUTE_MILLIS) != 0L
                || Math.floorMod(toExclusive, MINUTE_MILLIS) != 0L) {
            throw new IllegalArgumentException("聚合上下文必须对齐到自然分钟");
        }
        if (fromInclusive >= toExclusive) {
            throw new IllegalArgumentException("聚合结束时间必须晚于开始时间");
        }
    }

    private Map<String, PointRuntimeConfig> eligiblePoints(
            Set<String> requestedPointIds) {
        Map<String, PointRuntimeConfig> result = new LinkedHashMap<>();
        for (PointRuntimeConfig point : configProvider.findAll()) {
            if (requestedPointIds.contains(point.pointId())
                    && "ONLINE".equalsIgnoreCase(point.status())
                    && point.isForCalc() == 1
                    && "ANALOG".equalsIgnoreCase(point.dataType())) {
                result.put(point.pointId(), point);
            }
        }
        return result;
    }

    private Map<PointMinute, List<RawTelemetryEvent>> groupEligibleEvents(
            List<RawTelemetryEvent> events,
            Map<String, PointRuntimeConfig> eligiblePoints,
            long fromInclusive,
            long toExclusive) {
        Objects.requireNonNull(events, "原始事件查询结果不能为空");
        Map<PointMinute, List<RawTelemetryEvent>> grouped =
                new LinkedHashMap<>();
        for (RawTelemetryEvent event : events) {
            if (!eligiblePoints.containsKey(event.pointId())
                    || event.eventTime() < fromInclusive
                    || event.eventTime() >= toExclusive) {
                continue;
            }
            long minuteStart = event.eventTime()
                    - Math.floorMod(event.eventTime(), MINUTE_MILLIS);
            grouped.computeIfAbsent(
                    new PointMinute(event.pointId(), minuteStart),
                    ignored -> new ArrayList<>()).add(event);
        }
        return grouped;
    }

    private record PointMinute(String pointId, long minuteStart) {
    }
}
