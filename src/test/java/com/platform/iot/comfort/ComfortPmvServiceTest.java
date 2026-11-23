package com.platform.iot.comfort;

import com.platform.iot.comfort.ComfortPmvService.Sensation;
import com.platform.iot.comfort.ComfortPmvService.Status;
import com.platform.iot.comfort.ComfortQueryService.Overview;
import com.platform.iot.comfort.ComfortQueryService.Space;
import com.platform.iot.comfort.ComfortQueryService.TemperatureObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ComfortPmvServiceTest {
    private static final Space B314 = new Space("room-314", "B314", "B", "三层");
    private static final Instant SAMPLE = Instant.parse("2026-11-19T08:44:02Z");
    private static final Map<String, BigDecimal> INPUTS = Map.of(
            "metabolicRate", new BigDecimal("1.2"), "clothingInsulation", new BigDecimal("1"),
            "airVelocity", new BigDecimal("0.30"), "relativeHumidity", new BigDecimal("40"),
            "radiantTemperature", new BigDecimal("21"), "airTemperature", new BigDecimal("27.5"));
    private final ComfortParametersService parameters = mock(ComfortParametersService.class);
    private final ComfortPmvService service = new ComfortPmvService(parameters);

    @ParameterizedTest
    @CsvSource({
            "22, 22, .10, 60, 1.2, .5, -.75",
            "27, 27, .10, 60, 1.2, .5,  .77",
            "23.5, 25.5, .10, 60, 1.2, .5, -.01",
            "23, 21, .30, 40, 1.2, 1, -.16",
    })
    void agreesWithIso7730AnnexDReferenceCases(double airC, double radiantC, double speed, double humidity,
                                               double met, double clo, double expected) {
        assertThat(ComfortPmvCalculator.calculate(airC, radiantC, speed, humidity, met, clo).orElseThrow())
                .isCloseTo(expected, within(0.015));
    }

    @Test
    void usesMeasuredAirTemperatureAndReportsIncompleteInputsWithoutDefaults() {
        var overview = overview(new TemperatureObservation(23.0, SAMPLE, false), null);
        when(parameters.readManualInputs(B314)).thenReturn(INPUTS)
                .thenReturn(Map.of("metabolicRate", new BigDecimal("1.2"), "airVelocity", BigDecimal.ZERO));
        var measured = service.evaluate(overview);
        assertThat(measured.status()).isEqualTo(Status.AVAILABLE);
        assertThat(measured.value()).isEqualByComparingTo("-0.17");
        assertThat(measured.sensation()).isEqualTo(Sensation.COMFORTABLE);
        var incomplete = service.evaluate(overview);
        assertThat(incomplete.status()).isEqualTo(Status.PARAMETERS_MISSING);
        assertThat(incomplete.missingInputs()).containsExactly("clothingInsulation", "relativeHumidity", "radiantTemperature");
        assertThat(incomplete.value()).isNull();
    }

    @Test
    void suppressesMissingAndStaleTemperaturesBeforeLookingUpParameters() {
        var stale = new TemperatureObservation(23.0, SAMPLE, true);
        assertThat(service.evaluate(overview(null, stale)).status()).isEqualTo(Status.TEMPERATURE_STALE);
        assertThat(service.evaluate(overview(stale, null)).value()).isNull();
        assertThat(service.evaluate(overview(null, null)).status()).isEqualTo(Status.TEMPERATURE_MISSING);
        verifyNoInteractions(parameters);
    }

    @Test
    void returnsAnUnavailableStatusWhenParametersFailOrValuesExceedTheModelRange() {
        when(parameters.readManualInputs(B314))
                .thenReturn(INPUTS)
                .thenThrow(new DataAccessResourceFailureException("isolated test"));
        var outside = service.evaluate(overview(new TemperatureObservation(30.1, SAMPLE, false), null));
        assertThat(outside.status()).isEqualTo(Status.OUTSIDE_MODEL_RANGE);
        assertThat(outside.value()).isNull();
        var failure = service.evaluate(overview(new TemperatureObservation(23.0, SAMPLE, false), null));
        assertThat(failure.status()).isEqualTo(Status.PARAMETERS_UNAVAILABLE);
        assertThat(failure.value()).isNull();
    }

    private static Overview overview(TemperatureObservation current, TemperatureObservation last) {
        return new Overview(B314, List.of(), null, "ROOM_AVERAGE", null, null, current, last);
    }
}
