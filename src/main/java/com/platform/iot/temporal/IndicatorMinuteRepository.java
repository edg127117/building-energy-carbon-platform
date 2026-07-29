package com.platform.iot.temporal;

import com.platform.iot.formula.model.FormulaCalculationException;
import com.platform.iot.formula.model.IndicatorMinuteKey;
import com.platform.iot.formula.model.IndicatorMinuteResult;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * HVAC 公式结果访问 TDengine 的仓储边界。
 *
 * <p>成功值与失败审计分开保存，但都以指标实例和来源分钟定位。Service 和
 * 公式引擎只依赖该接口，不直接拼接 TDengine SQL；所有时间范围统一采用
 * 半开区间 {@code [fromInclusive,toExclusive)}。</p>
 */
public interface IndicatorMinuteRepository {

    /** 批量写入成功指标；同一指标同一分钟重算应保持幂等。 */
    void saveSuccesses(List<IndicatorMinuteResult> results);

    /** 批量写入失败审计，使缺失或非法输入不会静默丢失。 */
    void saveExceptions(List<FormulaCalculationException> exceptions);

    /**
     * 删除明确指标分钟键对应的旧成功结果。
     *
     * <p>只用于质量修正后公式已不再成功的失效处理；不得按建筑或时间范围扩大删除。</p>
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

    /** 返回时间窗口内已成功的指标分钟键，供缺口恢复一次性判断。 */
    Set<IndicatorMinuteKey> findSuccessfulKeys(
            List<String> indicatorIds, long fromInclusive, long toExclusive);
}
