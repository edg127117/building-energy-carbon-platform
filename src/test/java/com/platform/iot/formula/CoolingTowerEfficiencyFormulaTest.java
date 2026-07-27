package com.platform.iot.formula;

import com.platform.config.FormulaProperties;
import com.platform.iot.formula.model.FormulaCalculation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CoolingTowerEfficiencyFormulaTest {

    private final FormulaProperties properties = new FormulaProperties();
    private final CoolingTowerEfficiencyFormula formula = new CoolingTowerEfficiencyFormula(
            new PsychrometricWetBulbCalculator(), properties);

    @Test
    void directWetBulbHasPriority() {
        FormulaCalculation result = formula.calculate(inputs(
                point(FormulaKeys.TOWER_T_IN, 35.0, 0),
                point(FormulaKeys.TOWER_T_OUT, 30.0, 0),
                point(FormulaKeys.TOWER_TWB, 25.0, 1),
                point(FormulaKeys.OUTDOOR_TDB, 30.0, 2),
                point(FormulaKeys.OUTDOOR_RH, 50.0, 2)));

        assertThat(result.status()).isEqualTo(FormulaCalculation.Status.SUCCESS);
        assertThat(result.value()).isCloseTo(50.0, within(1.0e-9));
        assertThat(result.dataQuality()).isEqualTo(1);
        assertThat(result.inputs()).extracting(FormulaCalculation.Input::key)
                .containsExactly(FormulaKeys.TOWER_T_IN, FormulaKeys.TOWER_T_OUT,
                        FormulaKeys.TOWER_TWB);
        assertThat(result.steps()).extracting(FormulaCalculation.Step::code)
                .containsExactly("TWB_DIRECT", "5-4");
    }

    @Test
    void derivesWetBulbOnlyWhenDirectPointIsAbsent() {
        FormulaCalculation result = formula.calculate(inputs(
                point(FormulaKeys.TOWER_T_IN, 35.0, 0),
                point(FormulaKeys.TOWER_T_OUT, 30.0, 0),
                point(FormulaKeys.OUTDOOR_TDB, 30.0, 1),
                point(FormulaKeys.OUTDOOR_RH, 50.0, 2)));

        assertThat(result.status()).isEqualTo(FormulaCalculation.Status.SUCCESS);
        assertThat(result.value()).isCloseTo(38.486, within(0.01));
        assertThat(result.dataQuality()).isEqualTo(2);
        assertThat(result.inputs()).extracting(FormulaCalculation.Input::key)
                .containsExactly(FormulaKeys.TOWER_T_IN, FormulaKeys.TOWER_T_OUT,
                        FormulaKeys.OUTDOOR_TDB, FormulaKeys.OUTDOOR_RH);
        assertThat(result.steps()).extracting(FormulaCalculation.Step::code)
                .containsExactly("TWB_DERIVED", "5-4");
        assertThat(result.steps()).allMatch(step -> Double.isFinite(step.value()));
    }

    @Test
    void invalidDirectWetBulbDoesNotUseDerivedInputs() {
        FormulaCalculation result = formula.calculate(inputs(
                point(FormulaKeys.TOWER_T_IN, 35.0, 0),
                point(FormulaKeys.TOWER_T_OUT, 30.0, 0),
                point(FormulaKeys.TOWER_TWB, Double.NaN, 1),
                point(FormulaKeys.OUTDOOR_TDB, 30.0, 0),
                point(FormulaKeys.OUTDOOR_RH, 50.0, 0)));

        assertThat(result.status()).isEqualTo(FormulaCalculation.Status.INVALID_INPUT);
        assertThat(result.reasonCode()).isEqualTo("TOWER_TWB_NON_FINITE");
        assertThat(result.inputs()).extracting(FormulaCalculation.Input::key)
                .containsExactly(FormulaKeys.TOWER_T_IN, FormulaKeys.TOWER_T_OUT,
                        FormulaKeys.TOWER_TWB);
    }

    @Test
    void rejectsDirectWetBulbAtOrAboveInletTemperature() {
        FormulaCalculation result = formula.calculate(inputs(
                point(FormulaKeys.TOWER_T_IN, 35.0, 0),
                point(FormulaKeys.TOWER_T_OUT, 30.0, 0),
                point(FormulaKeys.TOWER_TWB, 35.0, 0),
                point(FormulaKeys.OUTDOOR_TDB, 30.0, 0),
                point(FormulaKeys.OUTDOOR_RH, 50.0, 0)));

        assertThat(result.status()).isEqualTo(FormulaCalculation.Status.INVALID_INPUT);
        assertThat(result.reasonCode()).isEqualTo("TOWER_WET_BULB_NOT_BELOW_INLET");
    }

    @Test
    void rejectsInvalidDerivedRelativeHumidity() {
        assertThat(derivedWithRh(0.0).reasonCode())
                .isEqualTo("TOWER_RELATIVE_HUMIDITY_OUT_OF_RANGE");
        assertThat(derivedWithRh(100.1).reasonCode())
                .isEqualTo("TOWER_RELATIVE_HUMIDITY_OUT_OF_RANGE");
    }

    @Test
    void rejectsNonpositiveConfiguredPressure() {
        properties.setAtmosphericPressureKpa(0.0);

        FormulaCalculation result = derivedWithRh(50.0);

        assertThat(result.status()).isEqualTo(FormulaCalculation.Status.INVALID_INPUT);
        assertThat(result.reasonCode()).isEqualTo("TOWER_PRESSURE_NON_POSITIVE");
    }

    @Test
    void reportsDirectAndDerivedInputsWhenAllWetBulbSourcesAreMissing() {
        FormulaCalculation result = formula.calculate(inputs(
                point(FormulaKeys.TOWER_T_IN, 35.0, 0),
                point(FormulaKeys.TOWER_T_OUT, 30.0, 0)));

        assertThat(result.status()).isEqualTo(FormulaCalculation.Status.MISSING_INPUT);
        assertThat(result.reasonCode()).isEqualTo("TOWER_INPUT_MISSING");
        assertThat(result.missingInputs()).containsExactly(
                FormulaKeys.TOWER_TWB, FormulaKeys.OUTDOOR_TDB, FormulaKeys.OUTDOOR_RH);
    }

    @Test
    void reportsEveryMissingBaseAndWetBulbInputInStableOrder() {
        FormulaCalculation result = formula.calculate(inputs());

        assertThat(result.missingInputs()).containsExactly(
                FormulaKeys.TOWER_T_IN, FormulaKeys.TOWER_T_OUT,
                FormulaKeys.TOWER_TWB, FormulaKeys.OUTDOOR_TDB, FormulaKeys.OUTDOOR_RH);
    }

    @Test
    void rejectsInvalidTemperatureRelationsAndNonFiniteResult() {
        FormulaCalculation reverseRange = formula.calculate(inputs(
                point(FormulaKeys.TOWER_T_IN, 30.0, 0),
                point(FormulaKeys.TOWER_T_OUT, 35.0, 0),
                point(FormulaKeys.TOWER_TWB, 25.0, 0)));
        FormulaCalculation overflowedResult = formula.calculate(inputs(
                point(FormulaKeys.TOWER_T_IN, Double.MAX_VALUE, 0),
                point(FormulaKeys.TOWER_T_OUT, -Double.MAX_VALUE, 0),
                point(FormulaKeys.TOWER_TWB, 0.0, 0)));

        assertThat(reverseRange.reasonCode())
                .isEqualTo("TOWER_TEMPERATURE_DIFFERENCE_NON_POSITIVE");
        assertThat(overflowedResult.reasonCode()).isEqualTo("TOWER_RESULT_NON_FINITE");
    }

    @Test
    void rejectsEfficiencyAboveOneHundredInsteadOfClamping() {
        FormulaCalculation result = formula.calculate(inputs(
                point(FormulaKeys.TOWER_T_IN, 35.0, 0),
                point(FormulaKeys.TOWER_T_OUT, 20.0, 0),
                point(FormulaKeys.TOWER_TWB, 25.0, 0)));

        assertThat(result.status()).isEqualTo(FormulaCalculation.Status.INVALID_INPUT);
        assertThat(result.reasonCode()).isEqualTo("TOWER_EFFICIENCY_OUT_OF_RANGE");
    }

    private FormulaCalculation derivedWithRh(double relativeHumidity) {
        return formula.calculate(inputs(
                point(FormulaKeys.TOWER_T_IN, 35.0, 0),
                point(FormulaKeys.TOWER_T_OUT, 30.0, 0),
                point(FormulaKeys.OUTDOOR_TDB, 30.0, 0),
                point(FormulaKeys.OUTDOOR_RH, relativeHumidity, 0)));
    }

    private FormulaInputs inputs(FormulaCalculation.Input... points) {
        return new FormulaInputs(List.of(points));
    }

    private FormulaCalculation.Input point(String key, double value, int quality) {
        return new FormulaCalculation.Input(key, "P-" + key, key, value, null, quality);
    }
}
