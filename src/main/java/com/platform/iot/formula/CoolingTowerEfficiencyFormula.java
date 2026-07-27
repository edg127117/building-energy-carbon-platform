package com.platform.iot.formula;

import com.platform.config.FormulaProperties;
import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.formula.model.FormulaCalculation.Input;
import com.platform.iot.formula.model.FormulaCalculation.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CoolingTowerEfficiencyFormula implements IndicatorFormula {

    private static final String CODE = "TOWER_EFF";
    private static final String VERSION = "TOWER_EFF_V1";

    private final PsychrometricWetBulbCalculator wetBulbCalculator;
    private final FormulaProperties properties;

    public CoolingTowerEfficiencyFormula(PsychrometricWetBulbCalculator wetBulbCalculator,
            FormulaProperties properties) {
        this.wetBulbCalculator = wetBulbCalculator;
        this.properties = properties;
    }

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
        Input inlet = required(values, FormulaKeys.TOWER_T_IN, missing);
        Input outlet = required(values, FormulaKeys.TOWER_T_OUT, missing);
        Optional<Input> directWetBulb = values.find(FormulaKeys.TOWER_TWB);
        Input dryBulb = null;
        Input relativeHumidity = null;
        if (directWetBulb.isEmpty()) {
            List<String> derivedMissing = new ArrayList<>();
            dryBulb = required(values, FormulaKeys.OUTDOOR_TDB, derivedMissing);
            relativeHumidity = required(values, FormulaKeys.OUTDOOR_RH, derivedMissing);
            if (!derivedMissing.isEmpty()) {
                missing.add(FormulaKeys.TOWER_TWB);
                missing.addAll(derivedMissing);
            }
        }
        if (!missing.isEmpty()) {
            return failure(FormulaCalculation.Status.MISSING_INPUT, "TOWER_INPUT_MISSING",
                    present(values), missing);
        }

        List<Input> used = new ArrayList<>(List.of(inlet, outlet));
        if (!allFinite(inlet, outlet)) {
            return invalid("TOWER_INPUT_NON_FINITE", used);
        }
        if (inlet.value() <= outlet.value()) {
            return invalid("TOWER_TEMPERATURE_DIFFERENCE_NON_POSITIVE", used);
        }

        double wetBulb;
        Step wetBulbStep;
        if (directWetBulb.isPresent()) {
            Input direct = directWetBulb.orElseThrow();
            used.add(direct);
            if (!Double.isFinite(direct.value())) {
                return invalid("TOWER_TWB_NON_FINITE", used);
            }
            wetBulb = direct.value();
            wetBulbStep = new Step("TWB_DIRECT", "TWB", wetBulb, "C");
        } else {
            used.add(dryBulb);
            used.add(relativeHumidity);
            if (!Double.isFinite(dryBulb.value())) {
                return invalid("TOWER_DRY_BULB_NON_FINITE", used);
            }
            if (!Double.isFinite(relativeHumidity.value())
                    || relativeHumidity.value() <= 0.0
                    || relativeHumidity.value() > 100.0) {
                return invalid("TOWER_RELATIVE_HUMIDITY_OUT_OF_RANGE", used);
            }
            double pressureKpa = properties.getAtmosphericPressureKpa();
            if (!Double.isFinite(pressureKpa) || pressureKpa <= 0.0) {
                return invalid("TOWER_PRESSURE_NON_POSITIVE", used);
            }
            try {
                wetBulb = wetBulbCalculator.calculate(
                        dryBulb.value(), relativeHumidity.value(), pressureKpa);
            } catch (IllegalArgumentException exception) {
                return invalid("TOWER_WET_BULB_DERIVATION_FAILED", used);
            }
            wetBulbStep = new Step("TWB_DERIVED", "ASHRAE(TDB,RH,P)", wetBulb, "C");
        }

        if (!Double.isFinite(wetBulb)) {
            return invalid("TOWER_TWB_NON_FINITE", used);
        }
        if (inlet.value() <= wetBulb) {
            return invalid("TOWER_WET_BULB_NOT_BELOW_INLET", used);
        }

        double efficiency = (inlet.value() - outlet.value())
                / (inlet.value() - wetBulb) * 100.0;
        if (!Double.isFinite(efficiency)) {
            return invalid("TOWER_RESULT_NON_FINITE", used);
        }
        if (efficiency < 0.0 || efficiency > 100.0) {
            return invalid("TOWER_EFFICIENCY_OUT_OF_RANGE", used);
        }

        List<Step> steps = List.of(
                wetBulbStep,
                new Step("5-4", "(TWin-TWout)/(TWin-TWB)*100", efficiency, "%"));
        return success(efficiency, used, steps);
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
