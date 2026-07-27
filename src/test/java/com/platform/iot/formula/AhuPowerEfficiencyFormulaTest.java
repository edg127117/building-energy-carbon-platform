package com.platform.iot.formula;

import com.platform.iot.formula.model.FormulaCalculation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AhuPowerEfficiencyFormulaTest {

    private final AhuPowerEfficiencyFormula formula = new AhuPowerEfficiencyFormula();

    @Test
    void convertsEfficiencyPercentBeforeCalculating() {
        FormulaCalculation result = formula.calculate(inputs(1000.0, 60.0));

        assertThat(result.status()).isEqualTo(FormulaCalculation.Status.SUCCESS);
        assertThat(result.value()).isCloseTo(0.462962962963, within(1.0e-9));
        assertThat(result.steps()).extracting(FormulaCalculation.Step::code)
                .containsExactly("5-7");
    }

    @Test
    void reportsBothMissingInputsInStableOrder() {
        FormulaCalculation result = formula.calculate(new FormulaInputs(List.of()));

        assertThat(result.status()).isEqualTo(FormulaCalculation.Status.MISSING_INPUT);
        assertThat(result.missingInputs()).containsExactly(
                FormulaKeys.AHU_TOTAL_PRESSURE, FormulaKeys.AHU_ETA_T);
    }

    @Test
    void rejectsZeroAndAboveHundredEfficiencyAndNonFiniteValues() {
        assertThat(formula.calculate(inputs(1000, 0)).reasonCode())
                .isEqualTo("AHU_EFFICIENCY_OUT_OF_RANGE");
        assertThat(formula.calculate(inputs(1000, 101)).reasonCode())
                .isEqualTo("AHU_EFFICIENCY_OUT_OF_RANGE");
        assertThat(formula.calculate(inputs(Double.NaN, 60)).status())
                .isEqualTo(FormulaCalculation.Status.INVALID_INPUT);
        assertThat(formula.calculate(inputs(1000, Double.POSITIVE_INFINITY)).status())
                .isEqualTo(FormulaCalculation.Status.INVALID_INPUT);
    }

    private FormulaInputs inputs(double pressure, double efficiency) {
        return new FormulaInputs(List.of(
                point(FormulaKeys.AHU_TOTAL_PRESSURE, pressure, 0),
                point(FormulaKeys.AHU_ETA_T, efficiency, 2)));
    }

    private FormulaCalculation.Input point(String key, double value, int quality) {
        return new FormulaCalculation.Input(key, "P-" + key, key, value, null, quality);
    }
}
