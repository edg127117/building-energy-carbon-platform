package com.platform.modbus.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.modbus.config.ModbusEdgeProperties;
import com.platform.modbus.model.StandardTelemetryMessage;
import com.platform.modbus.model.TelemetryMessageFactory;
import com.platform.modbus.support.TestDeviceFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MqttTelemetryPublisherTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MqttClient client = mock(MqttClient.class);
    private MqttTelemetryPublisher publisher;
    private StandardTelemetryMessage telemetry;

    @BeforeEach
    void setUp() throws Exception {
        ModbusEdgeProperties properties = TestDeviceFactory.validProperties();
        publisher = new MqttTelemetryPublisher(
                properties,
                new MqttSslContextFactory(),
                new MqttFailureClassifier(),
                objectMapper,
                meterRegistry,
                CLOCK);
        ReflectionTestUtils.setField(publisher, "client", client);
        when(client.isConnected()).thenReturn(true);
        telemetry = new TelemetryMessageFactory(CLOCK).create(
                properties.getDevices().getFirst(),
                Map.of("TEMP", BigDecimal.TEN, "POWER", BigDecimal.ONE));
    }

    @Test
    void qos1PublishAndPlatformAckRemainSeparateEvidence() throws Exception {
        publisher.publish(telemetry);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(client).publish(
                eq("platform/telemetry/v2/up"), payload.capture(), eq(1), eq(false));
        assertThat(objectMapper.readTree(payload.getValue())
                .path("canonicalMessageId").asText())
                .isEqualTo(telemetry.canonicalMessageId());
        assertThat(counter("modbus.edge.mqtt.publish", "result", "success"))
                .isEqualTo(1.0);
        assertThat(counter("modbus.edge.application.ack", "result", "received"))
                .isZero();

        publisher.messageArrived("platform/telemetry/v2/ack/modbus-edge", ack(
                telemetry.canonicalMessageId(), "ADAPTER_ONLY"));

        assertThat(counter("modbus.edge.application.ack", "result", "received"))
                .isEqualTo(1.0);
    }

    @Test
    void rejectsAckWhoseDeliveryScopeDoesNotMatchProxyMode() throws Exception {
        publisher.publish(telemetry);

        publisher.messageArrived("platform/telemetry/v2/ack/modbus-edge", ack(
                telemetry.canonicalMessageId(), "DEVICE"));

        assertThat(counter("modbus.edge.application.ack", "result", "received"))
                .isZero();
    }

    @Test
    void recordsApplicationAckTimeoutWithoutCallingItPublishFailure() {
        publisher.publish(telemetry);
        @SuppressWarnings("unchecked")
        Map<String, Long> pending = (Map<String, Long>) ReflectionTestUtils.getField(
                publisher, "pendingAcks");
        pending.put(telemetry.canonicalMessageId(),
                CLOCK.millis() - TestDeviceFactory.validProperties()
                        .getMqtt().getAckTimeout().toMillis() - 1);

        publisher.expireAcks();

        assertThat(counter("modbus.edge.application.ack", "result", "timeout"))
                .isEqualTo(1.0);
        assertThat(counter("modbus.edge.mqtt.publish", "result", "success"))
                .isEqualTo(1.0);
    }

    @Test
    void classifiesAuthenticationFailureWithoutExposingCredentials() throws Exception {
        doThrow(new MqttException(MqttException.REASON_CODE_FAILED_AUTHENTICATION))
                .when(client).publish(anyString(), any(byte[].class), eq(1), eq(false));

        assertThatThrownBy(() -> publisher.publish(telemetry))
                .isInstanceOfSatisfying(TelemetryPublishException.class,
                        failure -> assertThat(failure.failureCategory())
                                .isEqualTo(MqttFailureCategory.BAD_CREDENTIALS));
        assertThat(meterRegistry.find("modbus.edge.mqtt.publish")
                .tag("result", "failure")
                .tag("failure.category", "BAD_CREDENTIALS")
                .counter()).isNotNull();
    }

    private MqttMessage ack(String canonicalMessageId, String deliveryScope) {
        String json = """
                {"canonicalMessageId":"%s","deliveryScope":"%s"}
                """.formatted(canonicalMessageId, deliveryScope);
        return new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
    }

    private double counter(String name, String tagName, String tagValue) {
        var counter = meterRegistry.find(name).tag(tagName, tagValue).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
