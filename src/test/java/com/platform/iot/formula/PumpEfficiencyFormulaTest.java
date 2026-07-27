package com.platform.iot.formula;

import com.platform.iot.formula.model.FormulaCalculation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PumpEfficiencyFormulaTest {

    private final PumpEfficiencyFormula formula = new PumpEfficiencyFormula();

    @Test
    void calculatesPumpHeadAndEfficiency() {
        FormulaCalculation result = formula.calculate(valid(100.0, 300_000.0, 100_000.0, 1.0, 10.0));

        assertThat(result.status()).isEqualTo(FormulaCalculation.Status.SUCCESS);
        assertThat(result.value()).isCloseTo(58.277777777778, within(1.0e-9));
        assertThat(result.steps()).extracting(FormulaCalculation.Step::code)
                .containsExactly("5-6", "5-5");
        assertThat(result.steps().getFirst().value())
                .isCloseTo(20.408163265306, within(1.0e-9));
    }

    @Test
    void reportsEveryMissingInputInStableOrder() {
        FormulaCalculation result = formula.calculate(new FormulaInputs(List.of()));

        assertThat(result.status()).isEqualTo(FormulaCalculation.Status.MISSING_INPUT);
        assertThat(result.missingInputs()).containsExactly(
                FormulaKeys.PUMP_FLOW, FormulaKeys.PUMP_P_OUT,
                FormulaKeys.PUMP_P_IN, FormulaKeys.PUMP_Z, FormulaKeys.PUMP_PPE);
    }

    @Test
    void propagatesWorstUsedQuality() {
        FormulaCalculation result = formula.calculate(inputs(
                point(FormulaKeys.PUMP_FLOW, 100, 0),
                point(FormulaKeys.PUMP_P_OUT, 300_000, 2),
                point(FormulaKeys.PUMP_P_IN, 100_000, 1),
                point(FormulaKeys.PUMP_Z, 1, 0),
                point(FormulaKeys.PUMP_PPE, 10, 1)));
        assertThat(result.dataQuality()).isEqualTo(2);
    }

    @Test
    void rejectsReversePressureZeroPowerExcessEfficiencyAndNonFiniteValues() {
        assertThat(formula.calculate(valid(100, 100_000, 300_000, 1, 10)).reasonCode())
                .isEqualTo("PUMP_PRESSURE_DIFFERENCE_NON_POSITIVE");
        assertThat(formula.calculate(valid(100, 300_000, 100_000, 1, 0)).reasonCode())
                .isEqualTo("PUMP_POWER_NON_POSITIVE");
        assertThat(formula.calculate(valid(1000, 300_000, 100_000, 1, 1)).reasonCode())
                .isEqualTo("PUMP_EFFICIENCY_OUT_OF_RANGE");
        assertThat(formula.calculate(valid(Double.NaN, 300_000, 100_000, 1, 10)).status())
                .isEqualTo(FormulaCalculation.Status.INVALID_INPUT);
        assertThat(formula.calculate(valid(100, 300_000, 100_000, Double.NEGATIVE_INFINITY, 10)).status())
                .isEqualTo(FormulaCalculation.Status.INVALID_INPUT);
    }

    private FormulaInputs valid(double flow, double pOut, double pIn, double z, double power) {
        return inputs(point(FormulaKeys.PUMP_FLOW, flow, 0),
                point(FormulaKeys.PUMP_P_OUT, pOut, 0),
                point(FormulaKeys.PUMP_P_IN, pIn, 0),
                point(FormulaKeys.PUMP_Z, z, 0),
                point(FormulaKeys.PUMP_PPE, power, 0));
    }

    private FormulaInputs inputs(FormulaCalculation.Input... points) {
        return new FormulaInputs(List.of(points));
    }

    private FormulaCalculation.Input point(String key, double value, int quality) {
        return new FormulaCalculation.Input(key, "P-" + key, key, value, null, quality);
    }
}
