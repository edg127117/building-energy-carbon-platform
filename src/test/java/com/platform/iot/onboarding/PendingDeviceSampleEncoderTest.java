package com.platform.iot.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.identity.DeviceIdentityKey;
import com.platform.iot.ingest.standard.StandardMetric;
import com.platform.iot.ingest.standard.StandardTelemetryMessage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PendingDeviceSampleEncoderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsOnlyAllowedNormalizedFieldsAndDeterministicallyTruncates() throws Exception {
        PendingDeviceDiscoveryProperties properties = new PendingDeviceDiscoveryProperties();
        properties.setMaxMetricCount(2);
        properties.setMaxStringLength(8);
        properties.setMaxSampleBytes(512);
        PendingDeviceSampleEncoder encoder = new PendingDeviceSampleEncoder(
                objectMapper, properties);

        PendingDeviceSample sample = encoder.encode(message(List.of(
                metric("CURRENT_ENERGY_TOO_LONG", "12.34", "kWh"),
                metric("CURRENT_CO2", "7.8", "kgCO2e"),
                metric("EXTRA", "1", "V"))));

        JsonNode json = objectMapper.readTree(sample.json());
        assertThat(sample.truncated()).isTrue();
        assertThat(json.get("eventTime").asLong()).isEqualTo(1_785_398_400_000L);
        assertThat(json.get("receivedTime").asLong()).isEqualTo(1_785_398_400_100L);
        assertThat(json.get("timeSource").asText()).isEqualTo("DEVICE_REPORTED");
        assertThat(json.get("metrics")).hasSize(2);
        assertThat(json.get("metrics").get(0).get("code").asText())
                .isEqualTo("CURRENT_");
        assertThat(sample.json()).doesNotContain("sourceField", "secret/path");
        assertThat(sample.json().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(512);
    }

    @Test
    void dropsWholeMetricsUntilSerializedJsonFitsByteLimit() {
        PendingDeviceDiscoveryProperties properties = new PendingDeviceDiscoveryProperties();
        properties.setMaxMetricCount(64);
        properties.setMaxStringLength(128);
        properties.setMaxSampleBytes(512);
        PendingDeviceSampleEncoder encoder = new PendingDeviceSampleEncoder(
                objectMapper, properties);
        List<StandardMetric> metrics = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            metrics.add(metric("METRIC_" + index + "_" + "X".repeat(60),
                    "123456789.12345", "UNIT_" + "Y".repeat(40)));
        }

        PendingDeviceSample sample = encoder.encode(message(metrics));

        assertThat(sample.truncated()).isTrue();
        assertThat(sample.json().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(512);
        assertThatCodeIsValidJson(sample.json());
    }

    private void assertThatCodeIsValidJson(String json) {
        org.assertj.core.api.Assertions.assertThatCode(() -> objectMapper.readTree(json))
                .doesNotThrowAnyException();
    }

    private StandardTelemetryMessage message(List<StandardMetric> metrics) {
        return new StandardTelemetryMessage(
                "1.0", "ENERGY_METER_V1", 1,
                new DeviceIdentityKey("MAC", "123456789012345"),
                1_785_398_400_000L,
                1_785_398_400_100L,
                "DEVICE_REPORTED",
                1L,
                metrics);
    }

    private StandardMetric metric(String code, String value, String unit) {
        return new StandardMetric(code, new BigDecimal(value), unit, "/secret/path");
    }
}
