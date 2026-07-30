package com.platform.iot.aggregation;

import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Objects;

/**
 * 把一个测点一个自然分钟内的真实事件聚合为正式 Q0 分钟。
 *
 * <p>正常冻结与迟到修正共用此纯计算组件，确保平均值、样本数和服务器接收时间
 * 采用完全相同的口径。该组件不访问数据库也不发布事件。</p>
 */
@Component
public class HvacPointMinuteAggregator {

    private static final long MINUTE_MILLIS = 60_000L;

    /**
     * 根据完整真实证据生成 Q0；迟到标记只影响进入聚合的时机，不影响统计口径。
     */
    public RawMinuteAggregate aggregate(
            PointRuntimeConfig point,
            long minuteStart,
            List<RawTelemetryEvent> events,
            long finalizedAt) {
        Objects.requireNonNull(point, "point 不能为空");
        Objects.requireNonNull(events, "events 不能为空");
        if (Math.floorMod(minuteStart, MINUTE_MILLIS) != 0L) {
            throw new IllegalArgumentException("minuteStart 必须对齐到分钟");
        }
        if (events.isEmpty()) {
            throw new IllegalArgumentException("真实分钟至少需要一条原始事件");
        }
        for (RawTelemetryEvent event : events) {
            if (!point.pointId().equals(event.pointId())) {
                throw new IllegalArgumentException("真实事件必须属于同一测点");
            }
            if (event.eventTime() < minuteStart
                    || event.eventTime() >= minuteStart + MINUTE_MILLIS) {
                throw new IllegalArgumentException("真实事件必须属于目标分钟");
            }
            if (event.dataQuality() != 0) {
                throw new IllegalArgumentException("真实分钟证据的数据质量必须为0");
            }
        }

        DoubleSummaryStatistics statistics = events.stream()
                .mapToDouble(RawTelemetryEvent::value)
                .summaryStatistics();
        long firstReceived = events.stream()
                .map(RawTelemetryEvent::receivedTime)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        long lastReceived = events.stream()
                .map(RawTelemetryEvent::receivedTime)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        return new RawMinuteAggregate(
                point.pointId(),
                point.pointCode(),
                point.buildingId(),
                point.systemGroupId(),
                point.equipId(),
                point.equipCode(),
                point.familyCode(),
                point.componentCode(),
                point.suffixCode(),
                point.isForCalc(),
                minuteStart,
                statistics.getAverage(),
                statistics.getMin(),
                statistics.getMax(),
                Math.toIntExact(statistics.getCount()),
                0,
                firstReceived,
                lastReceived,
                finalizedAt,
                null);
    }
}
