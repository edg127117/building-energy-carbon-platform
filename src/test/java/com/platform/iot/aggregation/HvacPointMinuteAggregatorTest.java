package com.platform.iot.aggregation;

import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HvacPointMinuteAggregatorTest {

    private static final long MINUTE = 1_800_000_000_000L;

    private final HvacPointMinuteAggregator aggregator =
            new HvacPointMinuteAggregator();

    @Test
    void aggregatesAllRealSamplesIncludingLateEvidenceIntoQualityZero() {
        RawMinuteAggregate result = aggregator.aggregate(
                point(),
                MINUTE,
                List.of(event(10.0, 1_000L, 2_000L, false),
                        event(14.0, 40_000L, 90_000L, true)),
                MINUTE + 95_000L);

        assertThat(result.averageValue()).isEqualTo(12.0);
        assertThat(result.minimumValue()).isEqualTo(10.0);
        assertThat(result.maximumValue()).isEqualTo(14.0);
        assertThat(result.sampleCount()).isEqualTo(2);
        assertThat(result.dataQuality()).isZero();
        assertThat(result.firstReceivedTime()).isEqualTo(MINUTE + 2_000L);
        assertThat(result.lastReceivedTime()).isEqualTo(MINUTE + 90_000L);
        assertThat(result.qualityTaskId()).isNull();
    }

    @Test
    void rejectsEvidenceFromAnotherPointOrMinute() {
        RawTelemetryEvent wrongPoint = new RawTelemetryEvent(
                "P2", "P2", "MQTT_FREEZE_V1", "P2", "D2",
                "B1", "G1", "E2", "E2", "WCR", "MAIN", "TWin",
                12.0, MINUTE + 1_000L, MINUTE + 2_000L, 0, 1, false);
        RawTelemetryEvent wrongMinute =
                event(12.0, 60_000L, 61_000L, true);

        assertThatThrownBy(() -> aggregator.aggregate(
                point(), MINUTE, List.of(wrongPoint), MINUTE + 90_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同一测点");
        assertThatThrownBy(() -> aggregator.aggregate(
                point(), MINUTE, List.of(wrongMinute), MINUTE + 90_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目标分钟");
    }

    private PointRuntimeConfig point() {
        return new PointRuntimeConfig(
                "P1", "WCR1_TWin", "冷冻水进水温度", "B1", "G1",
                "E1", "WCR1", "WCR", "MAIN", "TWin",
                "ANALOG", "℃", "ONLINE", 1, null, null);
    }

    private RawTelemetryEvent event(
            double value,
            long eventOffset,
            long receivedOffset,
            boolean late) {
        return new RawTelemetryEvent(
                "P1", "WCR1_TWin", "MQTT_FREEZE_V1",
                "WCR1_TWin", "WCR1", "B1", "G1", "E1", "WCR1",
                "WCR", "MAIN", "TWin", value,
                MINUTE + eventOffset, MINUTE + receivedOffset,
                0, 1, late);
    }
}
