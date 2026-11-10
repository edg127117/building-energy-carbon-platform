package com.platform.iot.temperature;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class TemperaturePointTypesTest {
    @ParameterizedTest
    @ValueSource(strings = {"AI", "ANALOG"})
    void acceptsOnlySupportedAnalogRepresentations(String type) {
        assertThat(TemperaturePointTypes.isAnalog(type)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"DIGITAL", "ACCUMULATE", "DI", "AO", "analog", " AI", "ANALOG ", "UNKNOWN"})
    void doesNotGuessOrNormalizeUnsupportedTypes(String type) {
        assertThat(TemperaturePointTypes.isAnalog(type)).isFalse();
    }
}
