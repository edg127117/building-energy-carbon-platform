package com.platform.iot.temporal;

import com.platform.iot.temporal.model.RawEventWriteResult;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import com.platform.iot.temporal.model.LateRawMinuteEvidence;
import com.platform.iot.temporal.model.PointMinuteKey;

import java.util.List;
import java.util.Collection;
import java.util.Set;

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

    /**
     * 在 TDengine 侧筛选迟到事件并按 point+minute 去重，供低频恢复遗漏的接入通知。
     */
    List<LateRawMinuteEvidence> findLateMinuteEvidence(
            long startInclusive,
            long endExclusive,
            Long afterMinuteStart,
            String afterPointId,
            int limit);

    /**
     * 批量确认精确 point+minute 是否存在 late_flag=1 原始证据。
     */
    Set<PointMinuteKey> findLateEvidenceKeys(
            Collection<PointMinuteKey> candidates);

    void deleteBefore(long eventTimeExclusive);
}
