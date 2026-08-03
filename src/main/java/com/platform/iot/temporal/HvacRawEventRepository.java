package com.platform.iot.temporal;

import com.platform.iot.temporal.model.RawEventWriteResult;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import com.platform.iot.temporal.model.LateRawMinuteEvidence;
import com.platform.iot.temporal.model.PointMinuteKey;

import java.util.List;
import java.util.Collection;
import java.util.Set;

/**
 * HVAC 已校验真实事件的 TDengine 存储边界。
 *
 * <p>接入服务通过 {@link #upsert(RawTelemetryEvent)} 按测点和设备采集时间幂等落盘；
 * 正常分钟冻结、迟到修正和人工历史重算再通过范围查询复用同一份原始证据。所有时间
 * 窗口均采用半开区间 {@code [startInclusive, endExclusive)}，避免相邻分钟重复取样。</p>
 */
public interface HvacRawEventRepository {

    /**
     * 写入或覆盖同一测点、同一设备采集时间的事件，并区分新行、完全重复和冲突更新。
     */
    RawEventWriteResult upsert(RawTelemetryEvent event);

    /**
     * 一次查询半开窗口内全部测点的原始事件。
     *
     * <p>分钟聚合使用超级表范围查询，而不是按 pointCode 循环查询子表，
     * 这样一个到期分钟只产生一次 TDengine 读取。正常冻结传 {@code false} 排除迟到
     * 样本；迟到修正和人工重算传 {@code true} 重建完整真实证据。</p>
     */
    List<RawTelemetryEvent> findWindow(
            long startInclusive, long endExclusive, boolean includeLate);

    /**
     * 在 TDengine 侧筛选迟到事件并按“测点 + 分钟”去重，供低频任务恢复遗漏通知。
     * 游标同样由分钟和测点组成，使有界分页能够稳定继续扫描。
     */
    List<LateRawMinuteEvidence> findLateMinuteEvidence(
            long startInclusive,
            long endExclusive,
            Long afterMinuteStart,
            String afterPointId,
            int limit);

    /**
     * 批量确认精确“测点 + 分钟”是否存在 {@code late_flag=1} 原始证据，避免把普通
     * 停机补算误判为迟到修正。
     */
    Set<PointMinuteKey> findLateEvidenceKeys(
            Collection<PointMinuteKey> candidates);

    /** 仅按设备采集时间删除保留期之前的逐条事件，不影响正式分钟和指标结果。 */
    void deleteBefore(long eventTimeExclusive);
}
