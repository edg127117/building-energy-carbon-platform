package com.platform.adapter.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.adapter.model.StandardMetric;
import com.platform.adapter.model.StandardTelemetryMessage;
import com.platform.adapter.model.TimeSource;
import com.platform.adapter.profile.ProtocolFieldMapping;
import com.platform.adapter.profile.ProtocolProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTelemetryAdapterTest {

    private static final long RECEIVED_TIME = 1_785_398_400_000L;

    private JsonTelemetryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JsonTelemetryAdapter(new ObjectMapper());
    }

    @Test
    void convertsConfiguredFieldsIntoOneCanonicalBatch() {
        StandardTelemetryMessage message = adapter.adapt(
                "device/raw/energy-meter/v1/up",
                bytes("""
                        {
                          "MAC":"123456789012345",
                          "current_energy":12.34,
                          "current_co2":7.88,
                          "current_co2_factor":0.639,
                          "last_period_energy":0.25,
                          "last_period_co2":0.16,
                          "last_period_co2_factor":0.639
                        }
                        """),
                RECEIVED_TIME,
                profile(null, null),
                sixMappings());

        assertThat(message.deviceIdentity().type()).isEqualTo("MAC");
        assertThat(message.deviceIdentity().value()).isEqualTo("123456789012345");
        assertThat(message.profileCode()).isEqualTo("ENERGY_METER_V1");
        assertThat(message.eventTime()).isEqualTo(RECEIVED_TIME);
        assertThat(message.timeSource()).isEqualTo(TimeSource.SERVER_RECEIVED);
        assertThat(message.seq()).isNull();
        assertThat(message.metrics()).extracting(StandardMetric::code)
                .containsExactly(
                        "CURRENT_ENERGY",
                        "CURRENT_CO2",
                        "CURRENT_CO2_FACTOR",
                        "LAST_PERIOD_ENERGY",
                        "LAST_PERIOD_CO2",
                        "LAST_PERIOD_CO2_FACTOR");
        assertThat(message.metrics()).extracting(StandardMetric::value)
                .containsExactly(
                        new BigDecimal("12.34"),
                        new BigDecimal("7.88"),
                        new BigDecimal("0.639"),
                        new BigDecimal("0.25"),
                        new BigDecimal("0.16"),
                        new BigDecimal("0.639"));
    }

    @Test
    void supportsNestedJsonPointerAndScaleOffsetConversion() {
        ProtocolFieldMapping mapping = mapping(
                "M1", "/data/energy_wh", "CURRENT_ENERGY", "Wh", "kWh",
                "0.001", "0", true, 1);

        StandardTelemetryMessage message = adapter.adapt(
                "device/raw/energy-meter/v1/up",
                bytes("""
                        {"identity":{"mac":"ABC"},"data":{"energy_wh":12340}}
                        """),
                RECEIVED_TIME,
                profileWithIdentityPath("/identity/mac", null, null),
                List.of(mapping));

        assertThat(message.metrics().getFirst().value())
                .isEqualByComparingTo("12.340");
    }

    @Test
    void usesDeviceTimestampAndSequenceWhenConfigured() {
        StandardTelemetryMessage message = adapter.adapt(
                "device/raw/energy-meter/v1/up",
                bytes("""
                        {"MAC":"ABC","timestamp":1785398400123,"seq":10001,"current_energy":1.2}
                        """),
                RECEIVED_TIME,
                profile("/timestamp", "/seq"),
                List.of(mapping(
                        "M1", "/current_energy", "CURRENT_ENERGY", "kWh", "kWh",
                        "1", "0", true, 1)));

        assertThat(message.eventTime()).isEqualTo(1_785_398_400_123L);
        assertThat(message.timeSource()).isEqualTo(TimeSource.DEVICE_REPORTED);
        assertThat(message.seq()).isEqualTo(10_001L);
    }

    @Test
    void rejectsWholePacketWhenRequiredFieldIsMissing() {
        assertThatThrownBy(() -> adapter.adapt(
                "device/raw/energy-meter/v1/up",
                bytes("{" + "\"MAC\":\"ABC\"}"),
                RECEIVED_TIME,
                profile(null, null),
                List.of(mapping(
                        "M1", "/current_energy", "CURRENT_ENERGY", "kWh", "kWh",
                        "1", "0", true, 1))))
                .isInstanceOf(TelemetryAdaptationException.class)
                .hasMessageContaining("/current_energy");
    }

    @Test
    void rejectsDuplicateCanonicalMetricCodes() {
        List<ProtocolFieldMapping> mappings = List.of(
                mapping("M1", "/a", "CURRENT_ENERGY", "kWh", "kWh", "1", "0", true, 1),
                mapping("M2", "/b", "CURRENT_ENERGY", "kWh", "kWh", "1", "0", true, 2));

        assertThatThrownBy(() -> adapter.adapt(
                "device/raw/energy-meter/v1/up",
                bytes("{" + "\"MAC\":\"ABC\",\"a\":1,\"b\":2}"),
                RECEIVED_TIME,
                profile(null, null),
                mappings))
                .isInstanceOf(TelemetryAdaptationException.class)
                .hasMessageContaining("CURRENT_ENERGY");
    }

    @Test
    void rejectsFractionalDeviceTimestampAndSequence() {
        assertThatThrownBy(() -> adapter.adapt(
                "device/raw/energy-meter/v1/up",
                bytes("""
                        {"MAC":"ABC","timestamp":1785398400000.5,"seq":1,"current_energy":1}
                        """),
                RECEIVED_TIME,
                profile("/timestamp", "/seq"),
                List.of(mapping(
                        "M1", "/current_energy", "CURRENT_ENERGY", "kWh", "kWh",
                        "1", "0", true, 1))))
                .isInstanceOf(TelemetryAdaptationException.class)
                .hasMessageContaining("timestamp");
    }

    private ProtocolProfile profile(String timestampPath, String seqPath) {
        return profileWithIdentityPath("/MAC", timestampPath, seqPath);
    }

    private ProtocolProfile profileWithIdentityPath(
            String identityPath,
            String timestampPath,
            String seqPath) {
        return new ProtocolProfile(
                "PROFILE1",
                "ENERGY_METER_V1",
                1,
                "device/raw/energy-meter/v1/up",
                "MAC",
                identityPath,
                null,
                null,
                timestampPath,
                seqPath,
                true);
    }

    private List<ProtocolFieldMapping> sixMappings() {
        return List.of(
                mapping("M1", "/current_energy", "CURRENT_ENERGY", "kWh", "kWh", "1", "0", true, 1),
                mapping("M2", "/current_co2", "CURRENT_CO2", "kgCO2", "kgCO2", "1", "0", true, 2),
                mapping("M3", "/current_co2_factor", "CURRENT_CO2_FACTOR", "kgCO2/kWh", "kgCO2/kWh", "1", "0", true, 3),
                mapping("M4", "/last_period_energy", "LAST_PERIOD_ENERGY", "kWh", "kWh", "1", "0", true, 4),
                mapping("M5", "/last_period_co2", "LAST_PERIOD_CO2", "kgCO2", "kgCO2", "1", "0", true, 5),
                mapping("M6", "/last_period_co2_factor", "LAST_PERIOD_CO2_FACTOR", "kgCO2/kWh", "kgCO2/kWh", "1", "0", true, 6));
    }

    private ProtocolFieldMapping mapping(
            String id,
            String sourcePath,
            String metricCode,
            String sourceUnit,
            String targetUnit,
            String scale,
            String offset,
            boolean required,
            int sortOrder) {
        return new ProtocolFieldMapping(
                id,
                "PROFILE1",
                sourcePath,
                metricCode,
                "DECIMAL",
                sourceUnit,
                targetUnit,
                new BigDecimal(scale),
                new BigDecimal(offset),
                required,
                true,
                sortOrder);
    }

    private byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
