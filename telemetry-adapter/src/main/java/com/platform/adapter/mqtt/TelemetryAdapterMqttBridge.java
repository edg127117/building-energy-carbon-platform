package com.platform.adapter.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.adapter.model.StandardTelemetryMessage;
import com.platform.adapter.parser.JsonTelemetryAdapter;
import com.platform.adapter.parser.TelemetryAdaptationException;
import com.platform.adapter.profile.ProtocolProfileProvider;
import com.platform.adapter.profile.ProtocolProfileResolutionException;
import com.platform.adapter.profile.ProtocolProfileUnavailableException;
import com.platform.adapter.profile.ResolvedProtocolProfile;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/**
 * 云端原始设备 Topic 与标准多指标 Topic 之间的可靠桥接。
 *
 * <p>原始消息只有在标准消息以 QoS 1 发布成功后才手动确认。非法 JSON、配置不匹配
 * 和字段错误属于重投无法修复的毒消息，会记录固定原因后确认；发布失败不确认，交由
 * EMQX 重投。云端不在此链路保存正式时序值。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "adapter.mqtt",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class TelemetryAdapterMqttBridge implements MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(TelemetryAdapterMqttBridge.class);

    private final IMqttClient client;
    private final TaskScheduler scheduler;
    private final AdapterMqttProperties properties;
    private final ObjectMapper objectMapper;
    private final JsonTelemetryAdapter telemetryAdapter;
    private final ProtocolProfileProvider profileProvider;
    private final AdapterMqttSslContextFactory sslContextFactory;
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final Map<String, ConcurrentLinkedQueue<MqttMessage>> pendingProxyAcks =
            new ConcurrentHashMap<>();

    public TelemetryAdapterMqttBridge(
            IMqttClient client,
            @Qualifier("adapterMqttTaskScheduler") TaskScheduler scheduler,
            AdapterMqttProperties properties,
            ObjectMapper objectMapper,
            JsonTelemetryAdapter telemetryAdapter,
            ProtocolProfileProvider profileProvider,
            AdapterMqttSslContextFactory sslContextFactory) {
        this.client = client;
        this.scheduler = scheduler;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.telemetryAdapter = telemetryAdapter;
        this.profileProvider = profileProvider;
        this.sslContextFactory = sslContextFactory;
    }

    @PostConstruct
    public void start() throws MqttException {
        client.setManualAcks(true);
        client.setCallback(this);
        connectOrScheduleRetry();
    }

    private void connectOrScheduleRetry() {
        if (client.isConnected() || !connecting.compareAndSet(false, true)) {
            return;
        }
        try {
            MqttConnectOptions options = connectOptions();
            client.connect(options);
            subscribeRawTopic();
            consecutiveFailures.set(0);
            log.info("云端报文适配器已连接EMQX: rawTopic={}, standardTopic={}",
                    properties.getRawTopic(), properties.getStandardTopic());
        } catch (MqttException | AdapterMqttTlsException exception) {
            long retryDelay = retryDelay(isSecurityFailure(exception));
            log.warn("云端报文适配器连接失败: failureType={}, retryMillis={}, broker={}",
                    exception.getClass().getSimpleName(), retryDelay, properties.getBrokerUrl());
            scheduler.schedule(
                    this::connectOrScheduleRetry,
                    Instant.now().plusMillis(retryDelay));
        } finally {
            connecting.set(false);
        }
    }

    private MqttConnectOptions connectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(properties.getUsername());
        options.setPassword(properties.getPassword().toCharArray());
        options.setAutomaticReconnect(true);
        options.setCleanSession(false);
        options.setConnectionTimeout(10);
        AdapterMqttProperties.Tls tls = properties.getTls();
        if (tls != null && tls.isEnabled()) {
            String brokerUrl = properties.getBrokerUrl();
            if (!(brokerUrl.startsWith("ssl://") || brokerUrl.startsWith("wss://"))) {
                throw new AdapterMqttTlsException(
                        "启用 TLS 时 adapter.mqtt.broker-url 必须使用 ssl:// 或 wss://");
            }
            options.setSocketFactory(sslContextFactory.create(tls).getSocketFactory());
            options.setSSLHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
            options.setHttpsHostnameVerificationEnabled(true);
        } else if (tls == null || !tls.isAllowPlaintextForTests()) {
            throw new AdapterMqttTlsException(
                    "禁止明文 MQTT；仅隔离自动化测试可显式开启 allow-plaintext-for-tests");
        }
        return options;
    }

    private long retryDelay(boolean securityFailure) {
        if (securityFailure) {
            consecutiveFailures.incrementAndGet();
            return properties.getSecurityRetryMillis();
        }
        int failures = consecutiveFailures.getAndIncrement();
        double raw = properties.getInitialRetryMillis()
                * Math.pow(properties.getRetryMultiplier(), Math.min(failures, 20));
        long capped = Math.min(properties.getMaxRetryMillis(),
                Math.max(properties.getInitialRetryMillis(), (long) raw));
        double jitter = Math.max(0.0, Math.min(properties.getRetryJitterRatio(), 1.0));
        long delta = (long) (capped * jitter);
        return delta == 0 ? capped : ThreadLocalRandom.current().nextLong(
                Math.max(1L, capped - delta), capped + delta + 1L);
    }

    private boolean isSecurityFailure(Throwable exception) {
        String message = exception.getMessage();
        return exception instanceof AdapterMqttTlsException
                || message != null && (message.contains("授权") || message.contains("认证"));
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        try {
            subscribeRawTopic();
            if (reconnect) {
                log.info("云端报文适配器自动重连后已恢复订阅: {}", properties.getRawTopic());
            }
        } catch (MqttException exception) {
            log.error("云端报文适配器恢复订阅失败", exception);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("云端报文适配器连接断开，等待自动重连: {}",
                cause == null ? "unknown" : cause.getMessage());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        if (topic.equals(properties.getApplicationAckTopic())) {
            handleApplicationAck(message);
            return;
        }
        boolean acknowledge = true;
        String pendingCanonicalId = null;
        try {
            byte[] rawPayload = message.getPayload();
            if (rawPayload == null || rawPayload.length == 0
                    || rawPayload.length > properties.getMaxPayloadBytes()) {
                log.warn("拒绝空包或超限设备报文: topic={}, bytes={}",
                        topic, rawPayload == null ? 0 : rawPayload.length);
                return;
            }
            JsonNode root = objectMapper.readTree(rawPayload);
            ResolvedProtocolProfile resolved = profileProvider.resolve(topic, root);
            StandardTelemetryMessage canonical = telemetryAdapter.adapt(
                    topic,
                    rawPayload,
                    System.currentTimeMillis(),
                    resolved.profile(),
                    resolved.mappings());
            byte[] canonicalPayload = objectMapper.writeValueAsBytes(canonical);
            if ("ADAPTER_PROXY".equals(canonical.declaredAckMode())) {
                pendingCanonicalId = canonical.canonicalMessageId();
                pendingProxyAcks.computeIfAbsent(pendingCanonicalId,
                        ignored -> new ConcurrentLinkedQueue<>()).add(message);
                acknowledge = false;
            }
            client.publish(properties.getStandardTopic(), canonicalPayload, 1, false);
            log.debug("设备报文转换完成: profile={}, identityType={}, metrics={}",
                    canonical.profileCode(),
                    canonical.deviceIdentity().type(),
                    canonical.metrics().size());
        } catch (MqttException exception) {
            acknowledge = false;
            removePending(pendingCanonicalId, message);
            log.error("标准报文发布失败，保留原始消息重投: topic={}, reason={}",
                    topic, exception.getMessage());
        } catch (ProtocolProfileUnavailableException exception) {
            acknowledge = false;
            log.warn("协议配置尚不可用，保留原始消息重投: topic={}, reason={}",
                    topic, exception.getMessage());
        } catch (TelemetryAdaptationException | ProtocolProfileResolutionException
                | IOException exception) {
            log.warn("设备报文解析拒绝并确认丢弃: topic={}, reason={}",
                    topic, exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("设备报文处理异常并确认丢弃: topic={}, reason={}",
                    topic, exception.getMessage());
        } finally {
            if (acknowledge) {
                acknowledge(message);
            }
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // 标准消息的同步 publish 已决定原始消息 ACK，本回调不承载额外状态。
    }

    private void subscribeRawTopic() throws MqttException {
        client.subscribe(
                new String[]{properties.getRawTopic(), properties.getApplicationAckTopic()},
                new int[]{1, 1});
    }

    private void handleApplicationAck(MqttMessage ackMessage) {
        try {
            PlatformAck ack = objectMapper.readValue(ackMessage.getPayload(), PlatformAck.class);
            if (ack.canonicalMessageId() == null || ack.canonicalMessageId().isBlank()
                    || !"ADAPTER_ONLY".equals(ack.deliveryScope())) {
                log.warn("拒绝无法关联或范围不匹配的应用ACK");
                return;
            }
            ConcurrentLinkedQueue<MqttMessage> waiting =
                    pendingProxyAcks.remove(ack.canonicalMessageId());
            if (waiting != null) {
                MqttMessage raw;
                while ((raw = waiting.poll()) != null) {
                    acknowledge(raw);
                }
            }
        } catch (IOException exception) {
            log.warn("拒绝格式无效的应用ACK: reason={}", exception.getMessage());
        } finally {
            acknowledge(ackMessage);
        }
    }

    private void removePending(String canonicalMessageId, MqttMessage message) {
        if (canonicalMessageId == null) {
            return;
        }
        pendingProxyAcks.computeIfPresent(canonicalMessageId, (ignored, waiting) -> {
            waiting.remove(message);
            return waiting.isEmpty() ? null : waiting;
        });
    }

    private void acknowledge(MqttMessage message) {
        try {
            client.messageArrivedComplete(message.getId(), message.getQos());
        } catch (MqttException exception) {
            log.error("原始设备消息手动确认失败: messageId={}", message.getId(), exception);
        }
    }

    @PreDestroy
    public void stop() {
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException exception) {
            log.warn("关闭云端报文适配器MQTT客户端失败: {}", exception.getMessage());
        }
    }

    private record PlatformAck(String canonicalMessageId, String deliveryScope) {
    }
}
