package com.platform.iot.aggregation;

import com.platform.iot.temporal.model.RawMinuteAggregate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一个自然分钟已经完成持久化的通知。
 *
 * <p>后续 COP 公式可以直接使用 {@link #aggregates()} 中的正式分钟输入，
 * 正常计算链路不需要为了取得相同数据再次查询 TDengine。</p>
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

    /** 兼容仍由真实聚合行推导建筑范围的调用点。 */
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
