package com.platform.iot.formula;

import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.formula.model.FormulaCalculation.Input;
import com.platform.iot.formula.model.FormulaCalculation.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ChillerCopFormula implements IndicatorFormula {

    private static final double WATER_DENSITY = 1000.0;
    private static final double WATER_SPECIFIC_HEAT = 4.18;
    private static final double SQRT_THREE = Math.sqrt(3.0);
    private static final String CODE = "WCR_COP";
    private static final String VERSION = "WCR_COP_V1";

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
        List<String> missing = new ArrayList<>();
        Input tIn = required(values, FormulaKeys.CHILLER_T_IN, missing);
        Input tOut = required(values, FormulaKeys.CHILLER_T_OUT, missing);
        Input flow = required(values, FormulaKeys.CHILLER_FLOW, missing);
        Optional<Input> ppe = values.find(FormulaKeys.CHILLER_PPE);
        Input voltage = null;
        Input current = null;
        Input powerFactor = null;
        if (ppe.isEmpty()) {
            List<String> fallbackMissing = new ArrayList<>();
            voltage = required(values, FormulaKeys.CHILLER_VOLTAGE, fallbackMissing);
            current = required(values, FormulaKeys.CHILLER_CURRENT, fallbackMissing);
            powerFactor = required(values, FormulaKeys.CHILLER_PF, fallbackMissing);
            if (!fallbackMissing.isEmpty()) {
                missing.add(FormulaKeys.CHILLER_PPE);
                missing.addAll(fallbackMissing);
            }
        }
        if (!missing.isEmpty()) {
            return failure(FormulaCalculation.Status.MISSING_INPUT, "CHILLER_INPUT_MISSING",
                    present(values), missing);
        }

        List<Input> used = new ArrayList<>(List.of(tIn, tOut, flow));
        if (!allFinite(tIn, tOut, flow)) {
            return invalid("CHILLER_INPUT_NON_FINITE", used);
        }
        if (flow.value() <= 0) {
            return invalid("CHILLER_FLOW_NON_POSITIVE", used);
        }
        if (tIn.value() <= tOut.value()) {
            return invalid("CHILLER_TEMPERATURE_DIFFERENCE_NON_POSITIVE", used);
        }

        double inputPower;
        String powerStep;
        if (ppe.isPresent()) {
            Input directPower = ppe.orElseThrow();
            used.add(directPower);
            if (!Double.isFinite(directPower.value())) {
                return invalid("CHILLER_POWER_NON_FINITE", used);
            }
            if (directPower.value() <= 0) {
                return invalid("CHILLER_POWER_NON_POSITIVE", used);
            }
            inputPower = directPower.value();
            powerStep = "NI_PPE";
        } else {
            used.add(voltage);
            used.add(current);
            used.add(powerFactor);
            if (!allFinite(voltage, current, powerFactor)) {
                return invalid("CHILLER_ELECTRICAL_INPUT_NON_FINITE", used);
            }
            if (voltage.value() <= 0 || current.value() <= 0) {
                return invalid("CHILLER_ELECTRICAL_INPUT_NON_POSITIVE", used);
            }
            if (powerFactor.value() <= 0 || powerFactor.value() > 1) {
                return invalid("CHILLER_POWER_FACTOR_OUT_OF_RANGE", used);
            }
            inputPower = voltage.value() * current.value() * powerFactor.value()
                    * SQRT_THREE / 1000.0;
            powerStep = "NI_ELECTRICAL";
        }
        if (!Double.isFinite(inputPower)) {
            return invalid("CHILLER_POWER_NON_FINITE", used);
        }

        double coolingCapacity = flow.value() * WATER_DENSITY * WATER_SPECIFIC_HEAT
                * (tIn.value() - tOut.value()) / 3600.0;
        double cop = coolingCapacity / inputPower;
        if (!Double.isFinite(coolingCapacity) || !Double.isFinite(cop)) {
            return invalid("CHILLER_RESULT_NON_FINITE", used);
        }
        List<Step> steps = List.of(
                new Step("5-1", "GW*1000*4.18*(TWin-TWout)/3600",
                        coolingCapacity, "kW"),
                new Step(powerStep, ppe.isPresent() ? "PPE" : "Voltage*Current*PF*sqrt(3)/1000",
                        inputPower, "kW"),
                new Step("5-2", "Q0/Ni", cop, null));
        return success(cop, used, steps);
    }

    private Input required(FormulaInputs inputs, String key, List<String> missing) {
        Input value = inputs.find(key).orElse(null);
        if (value == null) {
            missing.add(key);
        }
        return value;
    }

    private List<Input> present(FormulaInputs inputs) {
        return List.copyOf(inputs.asMap().values());
    }

    private boolean allFinite(Input... inputs) {
        for (Input input : inputs) {
            if (!Double.isFinite(input.value())) {
                return false;
            }
        }
        return true;
    }

    private FormulaCalculation success(double value, List<Input> used, List<Step> steps) {
        int quality = used.stream().mapToInt(Input::dataQuality).max().orElse(0);
        return new FormulaCalculation(FormulaCalculation.Status.SUCCESS, CODE, VERSION,
                value, quality, used, steps, null, List.of());
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
