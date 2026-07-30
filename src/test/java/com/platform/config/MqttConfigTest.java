package com.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.ingest.HvacIngestionResult;
import com.platform.iot.ingest.HvacMqttMessageHandler;
import com.platform.iot.ingest.IngestionOutcome;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 锁定 HVAC MQTT 入口的订阅和手动确认语义，防止旧电表分流重新进入运行链路。
 */
class MqttConfigTest {

    @Test
    void acknowledgesAcceptedHvacMessage() throws Exception {
        IMqttClient client = mock(IMqttClient.class);
        HvacMqttMessageHandler handler = mock(HvacMqttMessageHandler.class);
        when(handler.handle(anyMap(), anyLong()))
                .thenReturn(HvacIngestionResult.of(IngestionOutcome.ACCEPTED));
        MqttConfig config = configuredConfig(handler);

        config.initMqttClient(client).run();
        MqttCallback callback = capturedCallback(client);
        callback.messageArrived("device/data/up", mqttMessage(
                41,
                """
                {"deviceId":"hvac-gw-1","pointCode":"WCR1_TWin","value":12.3}
                """));

        verify(handler).handle(anyMap(), anyLong());
        verify(client).messageArrivedComplete(41, 1);
    }

    @Test
    void leavesStorageFailureUnacknowledgedForQosRetry() throws Exception {
        IMqttClient client = mock(IMqttClient.class);
        HvacMqttMessageHandler handler = mock(HvacMqttMessageHandler.class);
        when(handler.handle(anyMap(), anyLong()))
                .thenReturn(HvacIngestionResult.storageFailed(
                        "TDengine unavailable"));
        MqttConfig config = configuredConfig(handler);

        config.initMqttClient(client).run();
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
        MqttConfig config = configuredConfig(handler);

        config.initMqttClient(client).run();
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

    private MqttConfig configuredConfig(HvacMqttMessageHandler handler) {
        MqttConfig config = new MqttConfig(new ObjectMapper(), handler);
        ReflectionTestUtils.setField(config, "brokerUrl", "tcp://localhost:1883");
        ReflectionTestUtils.setField(config, "clientId", "test-client");
        ReflectionTestUtils.setField(config, "username", "test");
        ReflectionTestUtils.setField(config, "password", "test");
        ReflectionTestUtils.setField(
                config, "upstreamTopics", new String[]{"device/data/up"});
        return config;
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
}
