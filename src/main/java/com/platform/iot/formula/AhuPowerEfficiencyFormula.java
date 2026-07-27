package com.platform.iot.formula;

import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.formula.model.FormulaCalculation.Input;
import com.platform.iot.formula.model.FormulaCalculation.Step;

import java.util.List;

/**
 * 计算 AHU 风机单位风量功率指标，对应设计公式 5-7。
 *
 * <p>输入总效率在测点中以百分数保存，参与计算前必须除以 100；如果直接把
 * 百分数代入，结果会缩小 100 倍。该策略只做纯计算和输入校验，不访问数据库
 * 或缓存，失败时返回可审计状态而不是补默认值。</p>
 */
public final class AhuPowerEfficiencyFormula implements IndicatorFormula {

    private static final String CODE = "AHU_POW_EFF";
    private static final String VERSION = "AHU_POW_EFF_V1";
    private static final List<String> REQUIRED = List.of(
            FormulaKeys.AHU_TOTAL_PRESSURE, FormulaKeys.AHU_ETA_T);

    @Override
    public String indicatorCode() {
        return CODE;
    }

    @Override
    public String formulaVersion() {
        return VERSION;
    }

    @Override
    public FormulaCalculation calculate(FormulaInputs values) {
        List<String> missing = REQUIRED.stream().filter(key -> values.find(key).isEmpty()).toList();
        if (!missing.isEmpty()) {
            return failure(FormulaCalculation.Status.MISSING_INPUT,
                    "AHU_INPUT_MISSING", List.copyOf(values.asMap().values()), missing);
        }
        Input pressure = values.find(FormulaKeys.AHU_TOTAL_PRESSURE).orElseThrow();
        Input efficiencyPercent = values.find(FormulaKeys.AHU_ETA_T).orElseThrow();
        List<Input> used = List.of(pressure, efficiencyPercent);
        if (!Double.isFinite(pressure.value()) || !Double.isFinite(efficiencyPercent.value())) {
            return failure(FormulaCalculation.Status.INVALID_INPUT,
                    "AHU_INPUT_NON_FINITE", used, List.of());
        }
        if (pressure.value() <= 0) {
            return failure(FormulaCalculation.Status.INVALID_INPUT,
                    "AHU_TOTAL_PRESSURE_NON_POSITIVE", used, List.of());
        }
        if (efficiencyPercent.value() <= 0 || efficiencyPercent.value() > 100) {
            return failure(FormulaCalculation.Status.INVALID_INPUT,
                    "AHU_EFFICIENCY_OUT_OF_RANGE", used, List.of());
        }

        double normalizedEfficiency = efficiencyPercent.value() / 100.0;
        double powerEfficiency = pressure.value() / (3600.0 * normalizedEfficiency);
        if (!Double.isFinite(powerEfficiency)) {
            return failure(FormulaCalculation.Status.INVALID_INPUT,
                    "AHU_RESULT_NON_FINITE", used, List.of());
        }
        int quality = used.stream().mapToInt(Input::dataQuality).max().orElse(0);
        return new FormulaCalculation(FormulaCalculation.Status.SUCCESS, CODE, VERSION,
                powerEfficiency, quality, used, List.of(
                new Step("5-7", "TotalPress/(3600*(EtaT/100))",
                        powerEfficiency, "W/(m³/h)")), null, List.of());
    }

    private FormulaCalculation failure(
            FormulaCalculation.Status status, String reason, List<Input> used,
            List<String> missing) {
        return new FormulaCalculation(status, CODE, VERSION, null, null,
                used, List.of(), reason, missing);
    }
}
