package com.platform.iot.dataquality.event;

import com.platform.iot.dataquality.model.QualityEventSource;
import com.platform.iot.temporal.model.RawMinuteAggregate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一个分钟已经完成质量选择，可以交给公式模块计算的唯一事件边界。
 *
 * <p>正常首次冻结的 {@code affectedPointIds} 为空，表示计算事件建筑内全部活动指标；
 * 恢复和历史修正携带真正变化的点位，公式层会先回读该建筑的完整分钟。</p>
 */
public record HvacMinuteQualityReadyEvent(
        long minuteStart,
        long finalizedAt,
        QualityEventSource source,
        Set<String> buildingIds,
        List<RawMinuteAggregate> aggregates,
        Set<String> affectedPointIds) {

    public HvacMinuteQualityReadyEvent {
        source = Objects.requireNonNull(source, "source 不能为空");
        buildingIds = Set.copyOf(buildingIds);
        aggregates = List.copyOf(aggregates);
        affectedPointIds = Set.copyOf(affectedPointIds);
    }

    /**
     * 兼容公式单元测试和显式恢复调用；生产事件应优先使用完整事件契约。
     */
    public HvacMinuteQualityReadyEvent(
            long minuteStart,
            long finalizedAt,
            boolean recovery,
            List<RawMinuteAggregate> aggregates) {
        this(
                minuteStart,
                finalizedAt,
                QualityEventSource.NORMAL_FREEZE,
                buildingIds(aggregates),
                aggregates,
                recovery ? pointIds(aggregates) : Set.of());
    }

    private static Set<String> buildingIds(List<RawMinuteAggregate> aggregates) {
        return aggregates.stream()
                .map(RawMinuteAggregate::buildingId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> pointIds(List<RawMinuteAggregate> aggregates) {
        return aggregates.stream()
                .map(RawMinuteAggregate::pointId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
