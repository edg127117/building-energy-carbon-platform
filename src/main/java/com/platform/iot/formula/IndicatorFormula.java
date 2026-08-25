package com.platform.iot.formula;

import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.deviceparameter.DeviceParameterModels.ResolvedParameters;

import java.util.Set;

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
     * 返回可能参与本公式的全部标准语义键。
     *
     * <p>存在主备输入时必须同时声明，例如冷机的实测功率和电参数估算输入、
     * 冷却塔的实测湿球与干球/相对湿度换算输入。该集合只用于缩小重算范围，
     * 实际采用哪组输入仍由公式自身决定。</p>
     */
    Set<String> requiredInputKeys();

    /**
     * 返回经能源专家确认的标准设备参数编码；现有公式默认不读取任何设计或额定参数。
     * 该声明同时用于追溯发布前的影响分析。
     */
    default Set<String> requiredParameterCodes() {
        return Set.of();
    }

    /**
     * 使用一个冻结分钟的输入计算指标。
     *
     * @return 成功值或包含缺失项、失败原因的结构化结果，不返回 {@code null}
     */
    FormulaCalculation calculate(FormulaInputs inputs);

    /**
     * 使用冻结测点和同一次批量解析的正式设备参数计算。
     * 未声明参数依赖的既有公式继续委托原方法，保持当前口径不变。
     */
    default FormulaCalculation calculate(
            FormulaInputs inputs, ResolvedParameters parameters) {
        return calculate(inputs);
    }
}
