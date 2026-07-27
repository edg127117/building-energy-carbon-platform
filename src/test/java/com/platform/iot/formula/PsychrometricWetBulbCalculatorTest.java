package com.platform.iot.formula;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PsychrometricWetBulbCalculatorTest {

    private final PsychrometricWetBulbCalculator calculator =
            new PsychrometricWetBulbCalculator();

    @Test
    void matchesAshraePsychrometricReferenceAtStandardPressure() {
        assertThat(calculator.calculate(30.0, 50.0, 101.325))
                .isCloseTo(22.0053, within(0.01));
    }

    @Test
    void returnsDryBulbAtSaturation() {
        assertThat(calculator.calculate(25.0, 100.0, 101.325))
                .isCloseTo(25.0, within(0.001));
    }

    @Test
    void rejectsRelativeHumidityOutsideOpenClosedRange() {
        assertThatThrownBy(() -> calculator.calculate(30.0, 0.0, 101.325))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(30.0, 100.1, 101.325))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonpositivePressure() {
        assertThatThrownBy(() -> calculator.calculate(30.0, 50.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(30.0, 50.0, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonFiniteInputs() {
        assertThatThrownBy(() -> calculator.calculate(Double.NaN, 50.0, 101.325))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(30.0, Double.POSITIVE_INFINITY, 101.325))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(30.0, 50.0, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
