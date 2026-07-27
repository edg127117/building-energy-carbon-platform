package com.platform.iot.formula;

import com.platform.iot.formula.model.FormulaCalculation;

/**
 * 单一 HVAC 指标的纯计算策略。
 *
 * <p>实现类只校验输入并计算结果，不访问 MySQL、TDengine、Redis 或 WebSocket。
 * 外部资源编排由 {@link HvacFormulaEngine} 统一负责，保证公式可独立测试和重放。</p>
 */
public interface IndicatorFormula {

    /** 返回与 MySQL 指标配置一致的稳定指标编码。 */
    String indicatorCode();

    /** 返回写入历史结果的公式版本；历史解释必须按该版本匹配。 */
    String formulaVersion();

    /**
     * 使用一个冻结分钟的输入计算指标。
     *
     * @return 成功值或包含缺失项、失败原因的结构化结果，不返回 {@code null}
     */
    FormulaCalculation calculate(FormulaInputs inputs);
}
