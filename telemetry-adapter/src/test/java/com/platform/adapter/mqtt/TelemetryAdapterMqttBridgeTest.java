package com.platform.adapter.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.platform.adapter.parser.JsonTelemetryAdapter;
import com.platform.adapter.profile.ProtocolFieldMapping;
import com.platform.adapter.profile.ProtocolProfile;
import com.platform.adapter.profile.ProtocolProfileProvider;
import com.platform.adapter.profile.ProtocolProfileUnavailableException;
import com.platform.adapter.profile.ResolvedProtocolProfile;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelemetryAdapterMqttBridgeTest {

    private IMqttClient client;
    private ProtocolProfileProvider profileProvider;
    private TaskScheduler scheduler;
    private TelemetryAdapterMqttBridge bridge;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(IMqttClient.class);
        profileProvider = mock(ProtocolProfileProvider.class);
        scheduler = mock(TaskScheduler.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AdapterMqttProperties properties = new AdapterMqttProperties();
        properties.setBrokerUrl("tcp://localhost:1883");
        properties.setClientId("adapter-test");
        properties.setUsername("test");
        properties.setPassword("test");
        properties.setRawTopic("device/raw/energy/up");
        properties.setStandardTopic("device/telemetry/up");
        properties.setApplicationAckTopic("platform/telemetry/v2/ack/adapter");
        properties.setInitialRetryMillis(1000);
        properties.getTls().setEnabled(false);
        properties.getTls().setAllowPlaintextForTests(true);
        bridge = new TelemetryAdapterMqttBridge(
                client,
                scheduler,
                properties,
                objectMapper,
                new JsonTelemetryAdapter(objectMapper),
                profileProvider,
                new AdapterMqttSslContextFactory());
        when(profileProvider.resolve(anyString(), any())).thenReturn(resolved());
    }

    @Test
    void publishesCanonicalMessageBeforeAcknowledgingRawMessage() throws Exception {
        MqttMessage raw = message(41, """
                {"MAC":"123456789012345","current_energy":12.34}
                """);

        bridge.messageArrived("device/raw/energy/up", raw);

        ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(client).publish(
                org.mockito.ArgumentMatchers.eq("device/telemetry/up"),
                payloadCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(false));
        assertThat(new String(payloadCaptor.getValue(), StandardCharsets.UTF_8))
                .contains("\"CURRENT_ENERGY\"")
                .contains("\"123456789012345\"");
        verify(client).messageArrivedComplete(41, 1);
    }

    @Test
    void acknowledgesPoisonMessageWithoutPublishing() throws Exception {
        MqttMessage raw = message(42, "{" + "\"MAC\":\"123\"}");

        bridge.messageArrived("device/raw/energy/up", raw);

        verify(client, never()).publish(anyString(), any(byte[].class), anyInt(), any(Boolean.class));
        verify(client).messageArrivedComplete(42, 1);
    }

    @Test
    void publishFailureLeavesRawMessageUnacknowledged() throws Exception {
        doThrow(new MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION))
                .when(client).publish(anyString(), any(byte[].class), anyInt(), any(Boolean.class));
        MqttMessage raw = message(43, """
                {"MAC":"123456789012345","current_energy":12.34}
                """);

        bridge.messageArrived("device/raw/energy/up", raw);

        verify(client, never()).messageArrivedComplete(43, 1);
    }

    @Test
    void unavailableProfileSnapshotLeavesRawMessageUnacknowledged() throws Exception {
        when(profileProvider.resolve(anyString(), any()))
                .thenThrow(new ProtocolProfileUnavailableException("snapshot unavailable"));
        MqttMessage raw = message(44, """
                {"MAC":"123456789012345","current_energy":12.34}
                """);

        bridge.messageArrived("device/raw/energy/up", raw);

        verify(client, never()).publish(anyString(), any(byte[].class), anyInt(), any(Boolean.class));
        verify(client, never()).messageArrivedComplete(44, 1);
    }

    @Test
    void proxyModeAcknowledgesRawOnlyAfterCorrelatedPlatformAck() throws Exception {
        when(profileProvider.resolve(anyString(), any())).thenReturn(resolvedProxy());
        MqttMessage raw = message(45, """
                {"MAC":"123456789012345","messageId":"M-45","current_energy":12.34}
                """);

        bridge.messageArrived("device/raw/energy/up", raw);

        verify(client, never()).messageArrivedComplete(45, 1);
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(client).publish(anyString(), payload.capture(), anyInt(), any(Boolean.class));
        JsonNode canonical = new ObjectMapper().readTree(payload.getValue());
        String id = canonical.path("canonicalMessageId").asText();
        MqttMessage ack = message(46, """
                {"canonicalMessageId":"%s","deliveryScope":"ADAPTER_ONLY"}
                """.formatted(id));

        bridge.messageArrived("platform/telemetry/v2/ack/adapter", ack);

        verify(client).messageArrivedComplete(45, 1);
        verify(client).messageArrivedComplete(46, 1);
    }

    @Test
    void initialConnectionFailureSchedulesRetry() throws Exception {
        when(client.isConnected()).thenReturn(false);
        doThrow(new MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR))
                .when(client).connect(any());

        bridge.start();

        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void plaintextConnectionRequiresExplicitTestOnlyOverride() throws Exception {
        AdapterMqttProperties properties = (AdapterMqttProperties)
                ReflectionTestUtils.getField(bridge, "properties");
        properties.getTls().setAllowPlaintextForTests(false);

        bridge.start();

        verify(client, never()).connect(any());
        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    private ResolvedProtocolProfile resolved() {
        ProtocolProfile profile = new ProtocolProfile(
                "P1", "ENERGY_METER_V1", 1, "device/raw/energy/up",
                "MAC", "/MAC", null, null, null, null,
                null, null, null, null, "EVIDENCE_ONLY", "NONE", true);
        ProtocolFieldMapping mapping = new ProtocolFieldMapping(
                "M1", "P1", "/current_energy", "CURRENT_ENERGY", "DECIMAL",
                "kWh", "kWh", BigDecimal.ONE, BigDecimal.ZERO, true, true, 1);
        return new ResolvedProtocolProfile(profile, List.of(mapping));
    }

    private ResolvedProtocolProfile resolvedProxy() {
        ProtocolProfile profile = new ProtocolProfile(
                "P1", "ENERGY_METER_V1", 1, "device/raw/energy/up",
                "MAC", "/MAC", null, null, null, null,
                "/messageId", null, null, null,
                "ADAPTER_PROXY", "SOURCE_MESSAGE_ID", true);
        ProtocolFieldMapping mapping = new ProtocolFieldMapping(
                "M1", "P1", "/current_energy", "CURRENT_ENERGY", "DECIMAL",
                "kWh", "kWh", BigDecimal.ONE, BigDecimal.ZERO, true, true, 1);
        return new ResolvedProtocolProfile(profile, List.of(mapping));
    }

    private MqttMessage message(int id, String json) {
        MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
        message.setId(id);
        message.setQos(1);
        return message;
    }
}
