package com.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.ingest.HvacIngestionResult;
import com.platform.iot.ingest.HvacMqttMessageHandler;
import com.platform.iot.ingest.IngestionOutcome;
import com.platform.iot.ingest.standard.StandardTelemetryMqttMessageHandler;
import com.platform.iot.ingest.standard.StandardTelemetryResult;
import com.platform.iot.ingest.v2.TelemetryV2MqttMessageHandler;
import com.platform.iot.ingest.v2.V2ProcessingResult;
import com.platform.iot.ingest.v2.PlatformApplicationAck;
import com.platform.iot.reliability.AckMode;
import com.platform.iot.reliability.ReceiptStatus;
import com.platform.iot.mqtt.MqttFailureClassifier;
import com.platform.iot.mqtt.MqttSslContextFactory;
import com.platform.iot.mqtt.MqttTlsProperties;
import com.platform.iot.mqtt.MqttFailureEvidenceRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.scheduling.TaskScheduler;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

/**
 * 锁定 HVAC MQTT 入口的订阅和手动确认语义，防止旧电表分流重新进入运行链路。
 */
class MqttConfigTest {

    @Test
    void acknowledgesAcceptedHvacMessage() throws Exception {
        IMqttClient client = mock(IMqttClient.class);
        HvacMqttMessageHandler handler = mock(HvacMqttMessageHandler.class);
        StandardTelemetryMqttMessageHandler standardHandler =
                mock(StandardTelemetryMqttMessageHandler.class);
        when(handler.handle(anyMap(), anyLong()))
                .thenReturn(HvacIngestionResult.of(IngestionOutcome.ACCEPTED));
        ConfigFixture fixture = configuredConfig(handler, standardHandler);

        fixture.config().initMqttClient(client, fixture.scheduler()).run();
        MqttCallback callback = capturedCallback(client);
        callback.messageArrived("device/data/up", mqttMessage(
                41,
                """
                {"deviceId":"hvac-gw-1","pointCode":"WCR1_TWin","value":12.3}
                """));

        verify(handler).handle(anyMap(), anyLong());
        verifyNoInteractions(standardHandler);
        verify(client).messageArrivedComplete(41, 1);
    }

    @Test
    void leavesStorageFailureUnacknowledgedForQosRetry() throws Exception {
        IMqttClient client = mock(IMqttClient.class);
        HvacMqttMessageHandler handler = mock(HvacMqttMessageHandler.class);
        StandardTelemetryMqttMessageHandler standardHandler =
                mock(StandardTelemetryMqttMessageHandler.class);
        when(handler.handle(anyMap(), anyLong()))
                .thenReturn(HvacIngestionResult.storageFailed(
                        "TDengine unavailable"));
        ConfigFixture fixture = configuredConfig(handler, standardHandler);

        fixture.config().initMqttClient(client, fixture.scheduler()).run();
        capturedCallback(client).messageArrived(
                "device/data/up",
                mqttMessage(
                        42,
                        """
                        {"deviceId":"hvac-gw-1","pointCode":"WCR1_TWin","value":12.3}
                        """));

        verify(client, never()).messageArrivedComplete(42, 1);
    }

    @Test
    void acknowledgesAndRejectsLegacyPayloadWithoutPointCode() throws Exception {
        IMqttClient client = mock(IMqttClient.class);
        HvacMqttMessageHandler handler = mock(HvacMqttMessageHandler.class);
        StandardTelemetryMqttMessageHandler standardHandler =
                mock(StandardTelemetryMqttMessageHandler.class);
        ConfigFixture fixture = configuredConfig(handler, standardHandler);

        fixture.config().initMqttClient(client, fixture.scheduler()).run();
        capturedCallback(client).messageArrived(
                "device/data/up",
                mqttMessage(
                        43,
                        """
                        {"deviceId":"old-meter","value":220.1}
                        """));

        verify(handler, never()).handle(anyMap(), anyLong());
        verify(client).messageArrivedComplete(43, 1);
    }

    @Test
    void routesCanonicalTopicOnlyToStandardHandler() throws Exception {
        IMqttClient client = mock(IMqttClient.class);
        HvacMqttMessageHandler legacyHandler = mock(HvacMqttMessageHandler.class);
        StandardTelemetryMqttMessageHandler standardHandler =
                mock(StandardTelemetryMqttMessageHandler.class);
        when(standardHandler.handle(any(byte[].class), anyLong()))
                .thenReturn(StandardTelemetryResult.accepted(2));
        ConfigFixture fixture = configuredConfig(legacyHandler, standardHandler);

        fixture.config().initMqttClient(client, fixture.scheduler()).run();
        capturedCallback(client).messageArrived(
                "device/telemetry/up", mqttMessage(44, "{}"));

        verify(standardHandler).handle(any(byte[].class), anyLong());
        verifyNoInteractions(legacyHandler);
        verify(client).messageArrivedComplete(44, 1);
    }

    @Test
    void standardRetryableFailureLeavesCanonicalPacketUnacknowledged() throws Exception {
        IMqttClient client = mock(IMqttClient.class);
        StandardTelemetryMqttMessageHandler standardHandler =
                mock(StandardTelemetryMqttMessageHandler.class);
        when(standardHandler.handle(any(byte[].class), anyLong()))
                .thenReturn(StandardTelemetryResult.retryable(1, "tdengine unavailable"));
        ConfigFixture fixture = configuredConfig(
                mock(HvacMqttMessageHandler.class), standardHandler);

        fixture.config().initMqttClient(client, fixture.scheduler()).run();
        capturedCallback(client).messageArrived(
                "device/telemetry/up", mqttMessage(45, "{}"));

        verify(client, never()).messageArrivedComplete(45, 1);
    }

    @Test
    void unknownTopicIsAcknowledgedWithoutCallingEitherHandler() throws Exception {
        IMqttClient client = mock(IMqttClient.class);
        HvacMqttMessageHandler legacyHandler = mock(HvacMqttMessageHandler.class);
        StandardTelemetryMqttMessageHandler standardHandler =
                mock(StandardTelemetryMqttMessageHandler.class);
        ConfigFixture fixture = configuredConfig(legacyHandler, standardHandler);

        fixture.config().initMqttClient(client, fixture.scheduler()).run();
        capturedCallback(client).messageArrived(
                "device/unexpected/up", mqttMessage(46, "{}"));

        verifyNoInteractions(legacyHandler, standardHandler);
        verify(client).messageArrivedComplete(46, 1);
    }

    @Test
    void initialConnectionFailureSchedulesBackgroundRetry() throws Exception {
        IMqttClient client = mock(IMqttClient.class);
        doThrow(new MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR))
                .when(client).connect(any());
        ConfigFixture fixture = configuredConfig(
                mock(HvacMqttMessageHandler.class),
                mock(StandardTelemetryMqttMessageHandler.class));

        fixture.config().initMqttClient(client, fixture.scheduler()).run();

        verify(fixture.scheduler()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void plaintextConnectionRequiresExplicitTestOnlyOverride() throws Exception {
        IMqttClient client = mock(IMqttClient.class);
        ConfigFixture fixture = configuredConfig(
                mock(HvacMqttMessageHandler.class),
                mock(StandardTelemetryMqttMessageHandler.class));
        MqttTlsProperties tls = (MqttTlsProperties) ReflectionTestUtils.getField(
                fixture.config(), "tlsProperties");
        tls.setAllowPlaintextForTests(false);

        fixture.config().initMqttClient(client, fixture.scheduler()).run();

        verify(client, never()).connect(any());
        verify(fixture.scheduler()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void publishesApplicationAckBeforeCompletingV2InboundMessage() throws Exception {
        IMqttClient client = mock(IMqttClient.class);
        ConfigFixture fixture = configuredConfig(
                mock(HvacMqttMessageHandler.class),
                mock(StandardTelemetryMqttMessageHandler.class));
        PlatformApplicationAck ack = new PlatformApplicationAck(
                "1.0", "C-1", ReceiptStatus.PLATFORM_PERSISTED,
                "PLATFORM_PERSISTED", 1_785_398_400_500L, 1_785_398_400_600L,
                AckMode.ADAPTER_PROXY, "ADAPTER_ONLY");
        when(fixture.v2Handler().handle(any(byte[].class), anyLong()))
                .thenReturn(new V2ProcessingResult("C-1",
                        ReceiptStatus.PLATFORM_PERSISTED, "PLATFORM_PERSISTED", 5,
                        false, AckMode.ADAPTER_PROXY, "platform/ack/adapter", ack, null));
        fixture.config().initMqttClient(client, fixture.scheduler()).run();
        MqttCallback callback = capturedCallback(client);

        callback.messageArrived("platform/telemetry/v2/up", mqttMessage(47, "{}"));

        var ordered = inOrder(client, fixture.v2Handler());
        ordered.verify(client).publish(
                org.mockito.ArgumentMatchers.eq("platform/ack/adapter"),
                any(byte[].class), org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(false));
        ordered.verify(client).messageArrivedComplete(47, 1);
        ordered.verify(fixture.v2Handler()).markDeliveryCompleted("C-1", true);
    }

    private ConfigFixture configuredConfig(
            HvacMqttMessageHandler handler,
            StandardTelemetryMqttMessageHandler standardHandler) {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        TelemetryV2MqttMessageHandler v2Handler =
                mock(TelemetryV2MqttMessageHandler.class);
        MqttTlsProperties tls = new MqttTlsProperties();
        tls.setEnabled(false);
        tls.setAllowPlaintextForTests(true);
        MqttConfig config = new MqttConfig(
                new ObjectMapper().findAndRegisterModules(), handler, standardHandler,
                v2Handler, tls,
                new MqttSslContextFactory(), new MqttFailureClassifier(),
                new SimpleMeterRegistry(), mock(MqttFailureEvidenceRecorder.class));
        ReflectionTestUtils.setField(config, "brokerUrl", "tcp://localhost:1883");
        ReflectionTestUtils.setField(config, "clientId", "test-client");
        ReflectionTestUtils.setField(config, "username", "test");
        ReflectionTestUtils.setField(config, "password", "test");
        ReflectionTestUtils.setField(
                config, "upstreamTopics", new String[]{"device/data/up"});
        ReflectionTestUtils.setField(
                config, "standardUpstreamTopics", new String[]{"device/telemetry/up"});
        ReflectionTestUtils.setField(
                config, "v2UpstreamTopics", new String[]{"platform/telemetry/v2/up"});
        ReflectionTestUtils.setField(config, "initialRetryMillis", 1000L);
        ReflectionTestUtils.setField(config, "maxRetryMillis", 10_000L);
        ReflectionTestUtils.setField(config, "securityRetryMillis", 30_000L);
        ReflectionTestUtils.setField(config, "retryMultiplier", 2.0);
        ReflectionTestUtils.setField(config, "retryJitterRatio", 0.0);
        return new ConfigFixture(config, scheduler, v2Handler);
    }

    private MqttCallback capturedCallback(IMqttClient client) throws Exception {
        ArgumentCaptor<MqttCallback> captor =
                ArgumentCaptor.forClass(MqttCallback.class);
        verify(client).setCallback(captor.capture());
        return captor.getValue();
    }

    private MqttMessage mqttMessage(int id, String payload) {
        MqttMessage message =
                new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        message.setId(id);
        return message;
    }

    private record ConfigFixture(
            MqttConfig config,
            TaskScheduler scheduler,
            TelemetryV2MqttMessageHandler v2Handler) {
    }
}
