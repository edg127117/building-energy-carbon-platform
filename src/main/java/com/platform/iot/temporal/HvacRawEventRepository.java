package com.platform.iot.temporal;

import com.platform.iot.temporal.model.RawEventWriteResult;
import com.platform.iot.temporal.model.RawTelemetryEvent;

import java.util.List;

/**
 * HVAC 逐条真实事件存储边界。
 */
public interface HvacRawEventRepository {
    RawEventWriteResult upsert(RawTelemetryEvent event);

    /**
     * 一次查询时间窗口内全部测点的原始事件。
     *
     * <p>分钟聚合使用超级表范围查询，而不是按 pointCode 循环查询子表，
     * 这样一个到期分钟只产生一次TDengine读取。</p>
     */
    List<RawTelemetryEvent> findWindow(
            long startInclusive, long endExclusive, boolean includeLate);

    void deleteBefore(long eventTimeExclusive);
}
