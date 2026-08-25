package com.platform.iot.temporal;

import com.platform.iot.formula.model.FormulaCalculationException;
import com.platform.iot.formula.model.IndicatorMinuteKey;
import com.platform.iot.formula.model.IndicatorMinuteResult;
import com.platform.iot.formula.model.FormulaCalculationAttempt;
import com.platform.iot.formula.model.IndicatorMinuteState;
import com.platform.iot.formula.model.FormulaResultRevision;
import com.platform.iot.temporal.model.IndicatorTrendQueryRow;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * HVAC 公式结果访问 TDengine 的仓储边界。
 *
 * <p>成功值、失败审计、追加尝试和当前状态投影分开保存。Service 和公式引擎只依赖
 * 该接口，不直接拼接 TDengine SQL；查询先看当前状态，再决定旧成功事实是否仍有效。
 * 所有时间范围统一采用半开区间 {@code [fromInclusive,toExclusive)}。</p>
 */
public interface IndicatorMinuteRepository {

    /** 批量写入成功指标；同一指标同一分钟重算应保持幂等。 */
    void saveSuccesses(List<IndicatorMinuteResult> results);

    /** 批量写入失败审计，使缺失或非法输入不会静默丢失。 */
    void saveExceptions(List<FormulaCalculationException> exceptions);

    /**
     * 兼容旧流程的精确成功事实删除入口。
     *
     * <p>质量使用策略闭环不得调用该入口；策略变更通过追加尝试事实和覆盖当前状态投影
     * 使旧成功失效，保证历史可审计。</p>
     */
    void deleteSuccesses(Set<IndicatorMinuteKey> keys);

    /** 查询指定指标在来源分钟的成功结果。 */
    Optional<IndicatorMinuteResult> findSuccess(String indicatorId, long minuteStart);

    /** 查询指定指标在来源分钟最后一次失败审计。 */
    Optional<FormulaCalculationException> findException(String indicatorId, long minuteStart);

    /** 批量查询各指标最新的成功结果。 */
    List<IndicatorMinuteResult> findLatestSuccesses(List<String> indicatorIds);

    /** 批量查询各指标最新的失败状态。 */
    List<FormulaCalculationException> findLatestExceptions(List<String> indicatorIds);

    /** 查询单个指标在半开时间区间内的成功趋势。 */
    List<IndicatorMinuteResult> findHistory(
            String indicatorId, long fromInclusive, long toExclusive);

    /**
     * 批量查询指标图表趋势；1 分钟返回精确成功值，5/30 分钟返回 TDengine 窗口统计。
     *
     * <p>该入口只读取成功指标表，失败公式分钟自然形成缺口；调用方负责权限和指标归属校验。</p>
     */
    List<IndicatorTrendQueryRow> findTrends(
            List<String> indicatorIds,
            long fromInclusive,
            long toExclusive,
            int resolutionMinutes);

    /** 返回时间窗口内已成功的指标分钟键，供缺口恢复一次性判断。 */
    Set<IndicatorMinuteKey> findSuccessfulKeys(
            List<String> indicatorIds, long fromInclusive, long toExclusive);

    /**
     * 批量读取精确指标分钟键在成功表或异常表中的最新尝试时间。
     */
    Map<IndicatorMinuteKey, Long> findLatestAttemptAt(
            Set<IndicatorMinuteKey> keys);

    /** 追加计算尝试事实；实现必须按 attemptId 幂等。 */
    default void saveAttempts(List<FormulaCalculationAttempt> attempts) {
    }

    /** 追加成功结果修订；实现必须以 attemptId 幂等且不得覆盖旧结果值。 */
    default void saveResultRevisions(List<FormulaResultRevision> revisions) {
    }

    /** 按平台认知时间读取当时已存在的最后一个成功结果修订。 */
    default Optional<FormulaResultRevision> findResultRevisionAt(
            String indicatorId, long minuteStart, long knowledgeAt) {
        return Optional.empty();
    }

    /** 覆盖同一指标分钟的当前状态投影，不删除成功或失败历史事实。 */
    default void saveStates(List<IndicatorMinuteState> states) {
    }

    /** 批量读取当前状态投影；没有投影的旧数据由查询层兼容读取。 */
    default Map<IndicatorMinuteKey, IndicatorMinuteState> findStates(
            Set<IndicatorMinuteKey> keys) {
        return Map.of();
    }

    /** 批量读取各指标最新的当前状态，防止 Redis 旧分钟掩盖较新的失效投影。 */
    default Map<String, IndicatorMinuteState> findLatestStates(List<String> indicatorIds) {
        return Map.of();
    }
}
