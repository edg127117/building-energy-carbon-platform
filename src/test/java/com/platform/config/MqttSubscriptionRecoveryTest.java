package com.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.ingest.HvacMqttMessageHandler;
import com.platform.iot.ingest.standard.StandardTelemetryMqttMessageHandler;
import com.platform.iot.ingest.standard.StandardTelemetryResult;
import com.platform.iot.ingest.v2.TelemetryV2MqttMessageHandler;
import com.platform.iot.mqtt.MqttFailureClassifier;
import com.platform.iot.mqtt.MqttFailureEvidenceRecorder;
import com.platform.iot.mqtt.MqttSslContextFactory;
import com.platform.iot.mqtt.MqttTlsProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.paho.client.mqttv3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅连接本机随机端口的协议替身，覆盖真实 Paho 队列与重连，不访问现场 Broker。 */
class MqttSubscriptionRecoveryTest {
    private final StandardTelemetryMqttMessageHandler handler = mock(StandardTelemetryMqttMessageHandler.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private MqttConfig config(String url) {
        MqttTlsProperties tls = new MqttTlsProperties();
        tls.setEnabled(false);
        tls.setAllowPlaintextForTests(true);
        MqttConfig config = new MqttConfig(new ObjectMapper(), mock(HvacMqttMessageHandler.class),
                handler, mock(TelemetryV2MqttMessageHandler.class), tls, new MqttSslContextFactory(),
                new MqttFailureClassifier(), registry, mock(MqttFailureEvidenceRecorder.class));
        ReflectionTestUtils.setField(config, "brokerUrl", url);
        ReflectionTestUtils.setField(config, "clientId", "isolated-subscription-recovery");
        ReflectionTestUtils.setField(config, "username", "test");
        ReflectionTestUtils.setField(config, "password", "test");
        ReflectionTestUtils.setField(config, "upstreamTopics", new String[]{"device/data/up"});
        ReflectionTestUtils.setField(config, "standardUpstreamTopics", new String[]{"device/telemetry/up"});
        ReflectionTestUtils.setField(config, "v2UpstreamTopics", new String[]{"platform/telemetry/v2/up"});
        ReflectionTestUtils.setField(config, "initialRetryMillis", 1000L);
        ReflectionTestUtils.setField(config, "maxRetryMillis", 10000L);
        ReflectionTestUtils.setField(config, "securityRetryMillis", 30000L);
        ReflectionTestUtils.setField(config, "retryMultiplier", 2.0);
        return config;
    }

    @Test
    void retriesInitialSubscriptionFailureWhileStillConnected() throws Exception {
        IMqttClient client = mock(IMqttClient.class);
        when(client.isConnected()).thenReturn(false, true);
        doThrow(new MqttException(MqttException.REASON_CODE_CLIENT_TIMEOUT)).doNothing()
                .when(client).subscribe(any(String[].class), any(int[].class));
        TaskScheduler scheduler = mock(TaskScheduler.class);
        config("tcp://127.0.0.1:1").initMqttClient(client, scheduler).run();
        ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(retry.capture(), any(Instant.class));
        retry.getValue().run();
        verify(client, times(2)).subscribe(any(String[].class), any(int[].class));
        verify(client, times(1)).connect(any());
        assertThat(registry.counter("iot.mqtt.subscription.failure", "category", "TIMEOUT").count()).isEqualTo(1);
    }

    @Test
    void reconnectReturnsBeforeSubscribeAndOldRetryStopsAfterDisconnect() throws Exception {
        IMqttClient client = mock(IMqttClient.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        List<Runnable> tasks = new ArrayList<>();
        doAnswer(call -> { tasks.add(call.getArgument(0)); return null; })
                .when(scheduler).schedule(any(Runnable.class), any(Instant.class));
        config("tcp://127.0.0.1:1").initMqttClient(client, scheduler).run();
        ArgumentCaptor<MqttCallback> captured = ArgumentCaptor.forClass(MqttCallback.class);
        verify(client).setCallback(captured.capture());
        MqttCallbackExtended callback = (MqttCallbackExtended) captured.getValue();
        when(client.isConnected()).thenReturn(true);
        callback.connectComplete(true, "tcp://127.0.0.1:1");
        verify(client, never()).subscribe(any(String[].class), any(int[].class));
        doThrow(new MqttException(MqttException.REASON_CODE_CLIENT_TIMEOUT))
                .when(client).subscribe(any(String[].class), any(int[].class));
        tasks.getFirst().run();
        assertThat(tasks).hasSize(2);
        callback.connectionLost(new MqttException(MqttException.REASON_CODE_CONNECTION_LOST));
        tasks.get(1).run();
        verify(client).subscribe(any(String[].class), any(int[].class));
        assertThat(tasks).hasSize(2);
    }

    @Test
    void partialSubscribeDenialUsesSecurityBackoff() throws Exception {
        IMqttClient client = mock(IMqttClient.class);
        when(client.isConnected()).thenReturn(false, true);
        doAnswer(call -> { ((int[]) call.getArgument(1))[1] = 128; return null; })
                .when(client).subscribe(any(String[].class), any(int[].class));
        TaskScheduler scheduler = mock(TaskScheduler.class);
        Instant before = Instant.now();
        config("tcp://127.0.0.1:1").initMqttClient(client, scheduler).run();
        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler).schedule(any(Runnable.class), at.capture());
        assertThat(at.getValue()).isAfterOrEqualTo(before.plusSeconds(30));
        assertThat(registry.counter("iot.mqtt.subscription.failure", "category", "SUBSCRIBE_DENIED").count()).isEqualTo(1);
    }

    @Test
    @Timeout(25)
    void realPahoReconnectDrainsBacklogBeforeSubackAcrossTwoDisconnects() throws Exception {
        CountDownLatch initialSubscribed = new CountDownLatch(1);
        CountDownLatch dropInitial = new CountDownLatch(1);
        CountDownLatch recovered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(handler.handle(any(byte[].class), anyLong())).thenReturn(StandardTelemetryResult.accepted(1));
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(15000);
            var broker = executor.submit(() -> {
                try {
                    for (int connection = 0; connection < 3; connection++) {
                        try (Socket socket = server.accept()) {
                            socket.setSoTimeout(10000);
                            var in = new DataInputStream(socket.getInputStream());
                            var out = new DataOutputStream(socket.getOutputStream());
                            assertThat(readPacket(in).type()).isEqualTo(1);
                            out.write(new byte[]{0x20, 2, (byte) (connection == 0 ? 0 : 1), 0});
                            out.flush();
                            Packet subscription = readPacket(in);
                            assertThat(subscription.type()).isEqualTo(8);
                            if (connection > 0) {
                                // Paho 回调队列容量为 10；先投递 12 条 QoS1 消息再发 SUBACK。
                                for (int id = 1; id <= 12; id++) publish(out, id);
                            }
                            out.write(new byte[]{(byte) 0x90, 5, subscription.body()[0], subscription.body()[1], 1, 1, 1});
                            out.flush();
                            if (connection == 0) {
                                initialSubscribed.countDown();
                                assertThat(dropInitial.await(5, TimeUnit.SECONDS)).isTrue();
                            } else {
                                for (int id = 1; id <= 12; id++) assertThat(readPacket(in).type()).isEqualTo(4);
                                if (connection == 2) {
                                    recovered.countDown();
                                    assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
                                }
                            }
                        }
                    }
                    return true;
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            MqttConfig config = config("tcp://127.0.0.1:" + server.getLocalPort());
            ThreadPoolTaskScheduler scheduler = config.mqttTaskScheduler();
            scheduler.initialize();
            MqttClient client = (MqttClient) config.mqttClient();
            try {
                config.initMqttClient(client, scheduler).run();
                assertThat(initialSubscribed.await(2, TimeUnit.SECONDS)).isTrue();
                dropInitial.countDown();
                assertThat(recovered.await(10, TimeUnit.SECONDS)).as("two reconnects consume and acknowledge all backlog").isTrue();
                verify(handler, times(24)).handle(any(byte[].class), anyLong());
                assertThat(client.isConnected()).isTrue();
            } finally {
                finish.countDown();
                dropInitial.countDown();
                client.disconnectForcibly(0, 1000, false);
                client.close(true);
                scheduler.shutdown();
            }
            assertThat(broker.get(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    private record Packet(int type, byte[] body) { }

    private static Packet readPacket(DataInputStream in) throws Exception {
        int header = in.readUnsignedByte();
        int length = 0;
        int multiplier = 1;
        int digit;
        do {
            digit = in.readUnsignedByte();
            length += (digit & 127) * multiplier;
            multiplier *= 128;
        } while ((digit & 128) != 0);
        byte[] body = new byte[length];
        in.readFully(body);
        return new Packet(header >> 4, body);
    }

    private static void publish(DataOutputStream out, int id) throws Exception {
        byte[] topic = "device/telemetry/up".getBytes(StandardCharsets.UTF_8);
        out.writeByte(0x32);
        out.writeByte(2 + topic.length + 2 + 2);
        out.writeShort(topic.length);
        out.write(topic);
        out.writeShort(id);
        out.writeBytes("{}");
    }
}
