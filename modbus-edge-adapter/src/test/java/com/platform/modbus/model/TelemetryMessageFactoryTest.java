package com.platform.modbus.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.modbus.support.TestDeviceFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryMessageFactoryTest {

    @Test
    void createsCorrelatableV2EnvelopeWithoutInventingDeviceTime() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000L), ZoneOffset.UTC);
        TelemetryMessageFactory factory = new TelemetryMessageFactory(clock, "boot-1");

        StandardTelemetryMessage first = factory.create(
                TestDeviceFactory.device(),
                Map.of("TEMP", BigDecimal.TEN, "POWER", BigDecimal.valueOf(20)));
        StandardTelemetryMessage second = factory.create(
                TestDeviceFactory.device(),
                Map.of("TEMP", BigDecimal.TEN, "POWER", BigDecimal.valueOf(20)));

        assertThat(first.standardVersion()).isEqualTo("2.0");
        assertThat(first.sourceMessageId()).isNull();
        assertThat(first.collectedAt()).isNull();
        assertThat(first.adapterReceivedAt()).isEqualTo(1_800_000_000_000L);
        assertThat(first.bootId()).isEqualTo("boot-1");
        assertThat(first.sourceSeq()).isEqualTo(1L);
        assertThat(first.timeSource()).isEqualTo("ADAPTER_RECEIVED");
        assertThat(first.declaredAckMode()).isEqualTo("ADAPTER_PROXY");
        assertThat(first.correlationPolicy()).isEqualTo("BOOT_ID_AND_SEQ");
        assertThat(second.sourceSeq()).isEqualTo(2L);
        assertThat(second.canonicalMessageId()).isNotEqualTo(first.canonicalMessageId());

        JsonNode json = new ObjectMapper().valueToTree(first);
        assertThat(json.path("deviceIdentity").path("value").asText())
                .isEqualTo("test-device-1");
        assertThat(json.path("metrics").size()).isEqualTo(2);
        assertThat(json.path("metrics").get(0).path("sourceField").asText())
                .isEqualTo("HOLDING_REGISTER:0");
    }
}
