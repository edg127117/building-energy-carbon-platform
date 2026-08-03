package com.platform.iot.temporal;

import com.platform.iot.temporal.model.RawMinuteAggregate;
import com.platform.iot.temporal.model.HvacMinuteQueryRow;
import com.platform.iot.temporal.model.MinuteQualityWriteResult;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * HVAC 正式 Q0/Q1/Q2 分钟数据的存储边界。
 *
 * <p>正常聚合写入真实 Q0，质量服务按优先级补写或升级 Q1/Q2；公式引擎和补算任务
 * 读取完整冻结分钟，查询 API 则读取最新快照和历史趋势。业务层只依赖此契约，
 * 不感知 TDengine 超级表、子表、分区或降采样 SQL。</p>
 */
public interface HvacMinuteRepository {
    /**
     * 一次保存同一分钟的多个测点结果。
     *
     * @param aggregates 已完成质量处理、可以冻结保存的分钟聚合结果
     */
    default void saveAll(List<RawMinuteAggregate> aggregates) {
        saveAllWithQualityPriority(aggregates, null);
    }

    /**
     * 在一个批次内比较已有分钟质量并只写入合法升级。
     *
     * <p>质量数字越小可信度越高，即 {@code Q0 > Q1 > Q2}。相同任务可幂等重试，
     * 同质量不同任务只有明确指定被替代任务后才允许写入。</p>
     */
    List<MinuteQualityWriteResult> saveAllWithQualityPriority(
            List<RawMinuteAggregate> aggregates, String supersedesTaskId);

    Optional<RawMinuteAggregate> findPointMinute(String pointId, long minuteStart);

    /**
     * 一次读取多个测点在半开区间 {@code [fromInclusive, toExclusive)} 内的正式分钟。
     */
    List<RawMinuteAggregate> findRange(
            Set<String> pointIds, long fromInclusive, long toExclusive);

    List<RawMinuteAggregate> findByQualityTaskId(String qualityTaskId);

    /**
     * 在任务声明的点位和半开区间内有界读取来源分钟，供小时收口核对。
     */
    List<RawMinuteAggregate> findByQualityTaskId(
            String qualityTaskId,
            String pointId,
            long fromInclusive,
            long toExclusive,
            int limit);

    /**
     * 在 TDengine 侧筛选自动修正窗口内晚于正常冻结边界的 Q0 分钟。
     *
     * @param normalFinalizationDelaySeconds 分钟结束后的正常冻结等待秒数
     */
    List<RawMinuteAggregate> findLateRealMinutes(
            long fromInclusive,
            long toExclusive,
            Long afterMinuteStart,
            String afterPointId,
            int normalFinalizationDelaySeconds,
            int limit);

    /**
     * 仅在分钟仍由指定补全任务持有时删除，避免作废旧任务误删升级后的数据。
     *
     * @return 当前调用通过所有权核验并发出删除时返回 {@code true}；分钟已不存在
     * 或已由其他任务、Q0 持有时返回 {@code false}
     */
    boolean deleteIfOwnedByTask(String pointId, long minuteStart, String taskId);

    /**
     * 一次读取某分钟已经存在的全部内部测点ID，仅供恢复任务判断缺口。
     *
     * @param minuteStart 目标分钟起始时间（Unix 毫秒）
     * @return 该分钟已经写入的内部测点 ID 集合
     */
    Set<String> findExistingPointIds(long minuteStart);

    /**
     * 读取指定建筑在一个完整分钟内已经冻结的全部测点聚合。
     *
     * @param minuteStart 目标分钟起始时间（Unix 毫秒）
     * @param buildingIds 已完成访问范围校验的建筑 ID
     * @return 按建筑和测点排序的完整分钟聚合
     */
    List<RawMinuteAggregate> findByMinute(
            long minuteStart, Set<String> buildingIds);

    /**
     * 批量读取多个测点各自最新的一条冻结分钟数据。
     *
     * <p>返回值只包含实际存在分钟数据的测点；配置存在但没有数据的测点，
     * 由业务层补成 {@code NO_DATA}，从而保留测点元数据。</p>
     *
     * @param pointIds 测点内部 ID；调用方应当传入已完成建筑归属校验的列表
     * @return 各测点最新分钟数据，底层返回顺序不作为业务契约
     */
    List<HvacMinuteQueryRow> findLatestByPointIds(List<String> pointIds);

    /**
     * 批量读取多个测点在半开时间区间 {@code [fromInclusive, toExclusive)} 内的趋势数据。
     *
     * <p>{@code resolutionMinutes} 只接受 1、5、30。1 分钟表示直接读取冻结分钟行，
     * 5/30 分钟表示由 TDengine 按时间窗口降采样。</p>
     *
     * @param pointIds 测点内部 ID 列表
     * @param fromInclusive 包含的起始时间（Unix 毫秒）
     * @param toExclusive 不包含的结束时间（Unix 毫秒）
     * @param resolutionMinutes 返回数据的分钟分辨率
     * @return 查询到的趋势行；无数据的测点不会凭空生成记录
     */
    List<HvacMinuteQueryRow> findHistory(
            List<String> pointIds,
            long fromInclusive,
            long toExclusive,
            int resolutionMinutes);
}
