package com.platform.iot.aggregation;

import com.platform.iot.temporal.model.RawMinuteAggregate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一个自然分钟完成质量优先写入后的边界事件。
 *
 * <p>质量完成服务或质量旁路监听器消费该事件，并在 Q0/Q1/Q2 选择结束后发布公式
 * READY 事件。{@link #aggregates()} 只包含仓储已经接受的 Q0 行；正常链路可复用
 * 这份快照，恢复或修正链路则会回查 TDengine 的完整分钟，避免局部输入计算。</p>
 *
 * @param minuteStart 该批分钟结果对应的设备时间分钟起点
 * @param finalizedAt 实际完成冻结的服务器时间
 * @param recovery 是否由启动恢复或低频补漏产生
 * @param buildingIds 本轮活动计算测点所属建筑，即使没有真实行也必须保留
 * @param aggregates 已成功写入 st_raw_minute 的分钟结果
 */
public record HvacMinuteBatchFrozenEvent(
        long minuteStart,
        long finalizedAt,
        boolean recovery,
        Set<String> buildingIds,
        List<RawMinuteAggregate> aggregates
) {
    public HvacMinuteBatchFrozenEvent {
        // 防止监听器修改列表，确保公式模块看到的内容与已落盘批次一致。
        buildingIds = Set.copyOf(buildingIds);
        aggregates = List.copyOf(aggregates);
    }

    /**
     * 从已落盘聚合行推导建筑范围；空分钟无法推导建筑时应使用完整构造器显式传入。
     */
    public HvacMinuteBatchFrozenEvent(
            long minuteStart,
            long finalizedAt,
            boolean recovery,
            List<RawMinuteAggregate> aggregates) {
        this(minuteStart, finalizedAt, recovery, buildingIds(aggregates), aggregates);
    }

    private static Set<String> buildingIds(List<RawMinuteAggregate> aggregates) {
        return aggregates.stream()
                .map(RawMinuteAggregate::buildingId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
