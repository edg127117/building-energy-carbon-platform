package com.platform.iot.formula;

import com.platform.iot.formula.model.FormulaCalculation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ChillerCopFormulaTest {

    private final ChillerCopFormula formula = new ChillerCopFormula();

    @Test
    void calculatesChillerCopFromDirectPpe() {
        FormulaCalculation result = formula.calculate(inputs(
                point(FormulaKeys.CHILLER_T_IN, 12.0, 0),
                point(FormulaKeys.CHILLER_T_OUT, 7.0, 0),
                point(FormulaKeys.CHILLER_FLOW, 100.0, 0),
                point(FormulaKeys.CHILLER_PPE, 100.0, 1),
                point(FormulaKeys.CHILLER_VOLTAGE, 380.0, 2)));

        assertThat(result.status()).isEqualTo(FormulaCalculation.Status.SUCCESS);
        assertThat(result.value()).isCloseTo(5.805555555556, within(1.0e-9));
        assertThat(result.dataQuality()).isEqualTo(1);
        assertThat(result.steps()).extracting(FormulaCalculation.Step::code)
                .containsExactly("5-1", "NI_PPE", "5-2");
    }

    @Test
    void fallsBackOnlyWhenPpeIsAbsent() {
        FormulaCalculation result = formula.calculate(inputs(
                point(FormulaKeys.CHILLER_T_IN, 12.0, 0),
                point(FormulaKeys.CHILLER_T_OUT, 7.0, 0),
                point(FormulaKeys.CHILLER_FLOW, 100.0, 0),
                point(FormulaKeys.CHILLER_VOLTAGE, 380.0, 0),
                point(FormulaKeys.CHILLER_CURRENT, 100.0, 1),
                point(FormulaKeys.CHILLER_PF, 0.9, 2)));

        assertThat(result.value()).isCloseTo(9.800699014021, within(1.0e-9));
        assertThat(result.dataQuality()).isEqualTo(2);
        assertThat(result.steps()).extracting(FormulaCalculation.Step::code)
                .containsExactly("5-1", "NI_ELECTRICAL", "5-2");
    }

    @Test
    void invalidPresentPpeDoesNotUseFallback() {
        FormulaCalculation result = formula.calculate(inputs(
                point(FormulaKeys.CHILLER_T_IN, 12.0, 0),
                point(FormulaKeys.CHILLER_T_OUT, 7.0, 0),
                point(FormulaKeys.CHILLER_FLOW, 100.0, 0),
                point(FormulaKeys.CHILLER_PPE, 0.0, 0),
                point(FormulaKeys.CHILLER_VOLTAGE, 380.0, 0),
                point(FormulaKeys.CHILLER_CURRENT, 100.0, 0),
                point(FormulaKeys.CHILLER_PF, 0.9, 0)));

        assertThat(result.status()).isEqualTo(FormulaCalculation.Status.INVALID_INPUT);
        assertThat(result.reasonCode()).isEqualTo("CHILLER_POWER_NON_POSITIVE");
    }

    @Test
    void reportsEveryMissingBaseInputInStableOrder() {
        FormulaCalculation result = formula.calculate(inputs(
                point(FormulaKeys.CHILLER_T_IN, 12.0, 0)));

        assertThat(result.status()).isEqualTo(FormulaCalculation.Status.MISSING_INPUT);
        assertThat(result.missingInputs()).containsExactly(
                FormulaKeys.CHILLER_T_OUT, FormulaKeys.CHILLER_FLOW,
                FormulaKeys.CHILLER_PPE, FormulaKeys.CHILLER_VOLTAGE,
                FormulaKeys.CHILLER_CURRENT, FormulaKeys.CHILLER_PF);
    }

    @Test
    void reportsMissingFallbackPowerKey() {
        FormulaCalculation result = formula.calculate(inputs(
                point(FormulaKeys.CHILLER_T_IN, 12.0, 0),
                point(FormulaKeys.CHILLER_T_OUT, 7.0, 0),
                point(FormulaKeys.CHILLER_FLOW, 100.0, 0),
                point(FormulaKeys.CHILLER_CURRENT, 100.0, 0),
                point(FormulaKeys.CHILLER_PF, 0.9, 0)));

        assertThat(result.missingInputs()).containsExactly(
                FormulaKeys.CHILLER_PPE, FormulaKeys.CHILLER_VOLTAGE);
    }

    @Test
    void rejectsReverseTemperatureInvalidPowerFactorAndNonFiniteValues() {
        assertThat(formula.calculate(direct(7.0, 12.0, 100.0, 100.0)).reasonCode())
                .isEqualTo("CHILLER_TEMPERATURE_DIFFERENCE_NON_POSITIVE");
        assertThat(formula.calculate(fallback(1.1)).reasonCode())
                .isEqualTo("CHILLER_POWER_FACTOR_OUT_OF_RANGE");
        assertThat(formula.calculate(direct(12.0, 7.0, Double.NaN, 100.0)).status())
                .isEqualTo(FormulaCalculation.Status.INVALID_INPUT);
        assertThat(formula.calculate(direct(12.0, 7.0, 100.0, Double.POSITIVE_INFINITY)).status())
                .isEqualTo(FormulaCalculation.Status.INVALID_INPUT);
    }

    @Test
    void rejectsDuplicateSemanticKeys() {
        assertThatThrownBy(() -> inputs(
                point(FormulaKeys.CHILLER_FLOW, 1.0, 0),
                point(FormulaKeys.CHILLER_FLOW, 2.0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private FormulaInputs direct(double tIn, double tOut, double flow, double ppe) {
        return inputs(point(FormulaKeys.CHILLER_T_IN, tIn, 0),
                point(FormulaKeys.CHILLER_T_OUT, tOut, 0),
                point(FormulaKeys.CHILLER_FLOW, flow, 0),
                point(FormulaKeys.CHILLER_PPE, ppe, 0));
    }

    private FormulaInputs fallback(double pf) {
        return inputs(point(FormulaKeys.CHILLER_T_IN, 12.0, 0),
                point(FormulaKeys.CHILLER_T_OUT, 7.0, 0),
                point(FormulaKeys.CHILLER_FLOW, 100.0, 0),
                point(FormulaKeys.CHILLER_VOLTAGE, 380.0, 0),
                point(FormulaKeys.CHILLER_CURRENT, 100.0, 0),
                point(FormulaKeys.CHILLER_PF, pf, 0));
    }

    private FormulaInputs inputs(FormulaCalculation.Input... points) {
        return new FormulaInputs(List.of(points));
    }

    private FormulaCalculation.Input point(String key, double value, int quality) {
        return new FormulaCalculation.Input(key, "P-" + key, key, value, null, quality);
    }
}
