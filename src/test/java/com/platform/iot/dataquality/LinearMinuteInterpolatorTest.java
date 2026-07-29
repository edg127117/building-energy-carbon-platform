package com.platform.iot.dataquality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinearMinuteInterpolatorTest {

    private static final long MINUTE = 60_000L;
    private final LinearMinuteInterpolator interpolator =
            new LinearMinuteInterpolator();

    @Test
    void interpolatesOneMissingMinute() {
        assertThat(interpolator.interpolate(
                0L, 10.0, 2 * MINUTE, 14.0, 5))
                .containsExactly(
                        new LinearMinuteInterpolator.InterpolatedMinute(
                                MINUTE, 12.0));
    }

    @Test
    void interpolatesFiveMissingMinutesIncludingOldestQueryBoundary() {
        assertThat(interpolator.interpolate(
                0L, 0.0, 6 * MINUTE, 60.0, 5))
                .containsExactly(
                        minute(1, 10.0),
                        minute(2, 20.0),
                        minute(3, 30.0),
                        minute(4, 40.0),
                        minute(5, 50.0));
    }

    @Test
    void rejectsSixMissingMinutes() {
        assertThat(interpolator.interpolate(
                0L, 0.0, 7 * MINUTE, 70.0, 5))
                .isEmpty();
    }

    @Test
    void rejectsAdjacentEndpointsBecauseThereIsNoGap() {
        assertThat(interpolator.interpolate(
                0L, 0.0, MINUTE, 10.0, 5))
                .isEmpty();
    }

    @Test
    void rejectsUnalignedOrReversedEndpoints() {
        assertThatThrownBy(() -> interpolator.interpolate(
                1L, 0.0, 2 * MINUTE, 10.0, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分钟");
        assertThatThrownBy(() -> interpolator.interpolate(
                2 * MINUTE, 0.0, MINUTE, 10.0, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("晚于");
    }

    @Test
    void keepsConstantEndpointValueForEveryMissingMinute() {
        assertThat(interpolator.interpolate(
                0L, 12.5, 4 * MINUTE, 12.5, 5))
                .extracting(LinearMinuteInterpolator.InterpolatedMinute::value)
                .containsExactly(12.5, 12.5, 12.5);
    }

    private LinearMinuteInterpolator.InterpolatedMinute minute(
            int minuteIndex, double value) {
        return new LinearMinuteInterpolator.InterpolatedMinute(
                minuteIndex * MINUTE, value);
    }
}
