package com.platform.iot.formula;

import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.formula.model.FormulaCalculation.Input;
import com.platform.iot.formula.model.FormulaCalculation.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 计算冷冻水泵扬程和效率，对应设计公式 5-6、5-5。
 *
 * <p>流量使用水泵组件自身的 {@code Pc/GW}，输入功率使用 {@code Pc/PPE}；
 * 两者不能与冷水机组主机的 GW、PPE 混用。任一输入缺失或不符合物理边界时
 * 返回失败结果，不自动套用额定值或上一分钟数据。</p>
 */
public final class PumpEfficiencyFormula implements IndicatorFormula {

    private static final double WATER_DENSITY = 1000.0;
    private static final double GRAVITY = 9.8;
    private static final String CODE = "PUMP_EFF";
    private static final String VERSION = "PUMP_EFF_V1";
    private static final List<String> REQUIRED = List.of(
            FormulaKeys.PUMP_FLOW, FormulaKeys.PUMP_P_OUT, FormulaKeys.PUMP_P_IN,
            FormulaKeys.PUMP_Z, FormulaKeys.PUMP_PPE);

    @Override
    public String indicatorCode() {
        return CODE;
    }

    @Override
    public String formulaVersion() {
        return VERSION;
    }

    @Override
    public Set<String> requiredInputKeys() {
        return Set.copyOf(REQUIRED);
    }

    @Override
    public FormulaCalculation calculate(FormulaInputs values) {
        List<String> missing = REQUIRED.stream().filter(key -> values.find(key).isEmpty()).toList();
        if (!missing.isEmpty()) {
            return failure(FormulaCalculation.Status.MISSING_INPUT,
                    "PUMP_INPUT_MISSING", List.copyOf(values.asMap().values()), missing);
        }
        Input flow = values.find(FormulaKeys.PUMP_FLOW).orElseThrow();
        Input pOut = values.find(FormulaKeys.PUMP_P_OUT).orElseThrow();
        Input pIn = values.find(FormulaKeys.PUMP_P_IN).orElseThrow();
        Input z = values.find(FormulaKeys.PUMP_Z).orElseThrow();
        Input power = values.find(FormulaKeys.PUMP_PPE).orElseThrow();
        List<Input> used = List.of(flow, pOut, pIn, z, power);
        if (used.stream().anyMatch(input -> !Double.isFinite(input.value()))) {
            return invalid("PUMP_INPUT_NON_FINITE", used);
        }
        if (flow.value() <= 0) {
            return invalid("PUMP_FLOW_NON_POSITIVE", used);
        }
        if (pOut.value() <= pIn.value()) {
            return invalid("PUMP_PRESSURE_DIFFERENCE_NON_POSITIVE", used);
        }
        if (power.value() <= 0) {
            return invalid("PUMP_POWER_NON_POSITIVE", used);
        }

        double head = (pOut.value() - pIn.value()) / (WATER_DENSITY * GRAVITY);
        if (head + z.value() <= 0) {
            return invalid("PUMP_TOTAL_HEAD_NON_POSITIVE", used);
        }
        double efficiency = flow.value() * WATER_DENSITY * GRAVITY
                * (head + z.value()) * 1.0e-6
                / (3.6 * power.value()) * 100.0;
        if (!Double.isFinite(head) || !Double.isFinite(efficiency)) {
            return invalid("PUMP_RESULT_NON_FINITE", used);
        }
        if (efficiency < 0 || efficiency > 100) {
            return invalid("PUMP_EFFICIENCY_OUT_OF_RANGE", used);
        }

        int quality = used.stream().mapToInt(Input::dataQuality).max().orElse(0);
        return new FormulaCalculation(FormulaCalculation.Status.SUCCESS, CODE, VERSION,
                efficiency, quality, used, List.of(
                new Step("5-6", "(Pout-Pin)/(1000*9.8)", head, "m"),
                new Step("5-5", "GW*1000*9.8*(head+Z)*1e-6/(3.6*PPE)*100",
                        efficiency, "%")), null, List.of());
    }

    private FormulaCalculation invalid(String reason, List<Input> used) {
        return failure(FormulaCalculation.Status.INVALID_INPUT, reason, used, List.of());
    }

    private FormulaCalculation failure(
            FormulaCalculation.Status status, String reason, List<Input> used,
            List<String> missing) {
        return new FormulaCalculation(status, CODE, VERSION, null, null,
                used, List.of(), reason, missing);
    }
}
