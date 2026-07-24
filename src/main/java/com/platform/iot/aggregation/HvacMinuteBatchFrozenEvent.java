package com.platform.iot.aggregation;

import com.platform.iot.temporal.model.RawMinuteAggregate;

import java.util.List;

/**
 * 一个自然分钟已经完成持久化的通知。
 *
 * <p>后续 COP 公式可以直接使用 {@link #aggregates()} 中的正式分钟输入，
 * 正常计算链路不需要为了取得相同数据再次查询 TDengine。</p>
 *
 * @param minuteStart 该批分钟结果对应的设备时间分钟起点
 * @param finalizedAt 实际完成冻结的服务器时间
 * @param recovery 是否由启动恢复或低频补漏产生
 * @param aggregates 已成功写入 st_raw_minute 的分钟结果
 */
public record HvacMinuteBatchFrozenEvent(
        long minuteStart,
        long finalizedAt,
        boolean recovery,
        List<RawMinuteAggregate> aggregates
) {
    public HvacMinuteBatchFrozenEvent {
        // 防止监听器修改列表，确保公式模块看到的内容与已落盘批次一致。
        aggregates = List.copyOf(aggregates);
    }
}
