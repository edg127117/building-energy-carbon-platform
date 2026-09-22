package com.platform.iot.temporal;

import com.platform.iot.temporal.model.RawEventWriteResult;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import com.platform.iot.temporal.model.LateRawMinuteEvidence;
import com.platform.iot.temporal.model.PointMinuteKey;
import com.platform.iot.temporal.model.LatestRawReading;
import com.platform.iot.temporal.model.RawTrendBucket;

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

    /** 单测点有界历史分页，不扩大原有全平台分钟窗口查询；afterExclusive为上一页末条时间。 */
    default List<RawTelemetryEvent> findPointHistory(String buildingId, String equipmentId, String pointId,
            long startInclusive, long endExclusive, Long afterExclusive, int limit) {
        throw new UnsupportedOperationException("Point history query is unavailable");
    }

    /**
     * 写入或覆盖同一测点、同一设备采集时间的事件，并区分新行、完全重复和冲突更新。
     */
    RawEventWriteResult upsert(RawTelemetryEvent event);

    /**
     * V2 可靠链使用的不可覆盖写入；相同键不同值返回冲突，但保留原值。
     */
    default RawEventWriteResult insertImmutable(RawTelemetryEvent event) {
        return upsert(event);
    }

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
     * 按已由 MySQL 确认的设备、建筑和测点集合批量读取各测点最近一条原始事件。
     * 返回的是事件值，不是分钟平均值；调用方仍须执行展示场景的质量使用策略。
     */
    List<LatestRawReading> findLatestByEquipmentPoints(
            String buildingId, String equipmentId, Collection<String> pointIds);

    /**
     * 按已确认的建筑、设备和测点集合在 TDengine 侧执行秒级分桶，返回原始事件趋势。
     * 最差质量等级随桶返回，供调用方继续执行历史展示场景的质量使用策略。
     */
    default List<RawTrendBucket> findEquipmentTrend(
            String buildingId,
            String equipmentId,
            Collection<String> pointIds,
            long startInclusive,
            long endExclusive,
            int intervalSeconds) {
        throw new UnsupportedOperationException("Equipment trend query is unavailable");
    }

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

    /** 按pointId稳定分页列出TDengine子表，并标明页内点是否已有指定来源历史。 */
    default List<RetentionPoint> findRetentionPoints(String afterTableName, int limit, String protectedSource) {
        throw new UnsupportedOperationException("Point retention scan is unavailable");
    }

    /** 单测点按事件时间删除，不在DELETE中依赖普通来源列过滤。 */
    default void deletePointBefore(String tableName, long eventTimeExclusive) {
        throw new UnsupportedOperationException("Point retention delete is unavailable");
    }

    /**
     * 删除指定来源所在单个测点的一段历史时间窗；返回本轮是否执行了删除窗口。
     */
    default SourceDeletionScan deleteSourceBeforeInBoundedWindow(String sourceSystem, long eventTimeExclusive,
                                                                  long maximumWindowMillis,
                                                                  String afterPointId) {
        throw new UnsupportedOperationException("Bounded source retention is unavailable");
    }

    record RetentionPoint(String tableName, String pointId, boolean protectedSourcePresent) { }
    record SourceDeletionScan(boolean workPerformed, boolean endOfScan, String nextPointCursor) { }
}
