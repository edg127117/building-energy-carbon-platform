package com.platform.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.ingest.HvacIngestionResult;
import com.platform.iot.ingest.HvacMqttMessageHandler;
import com.platform.iot.ingest.standard.StandardTelemetryMqttMessageHandler;
import com.platform.iot.ingest.standard.StandardTelemetryResult;
import com.platform.iot.ingest.v2.TelemetryV2MqttMessageHandler;
import com.platform.iot.ingest.v2.V2ProcessingResult;
import com.platform.iot.mqtt.MqttFailureCategory;
import com.platform.iot.mqtt.MqttFailureClassifier;
import com.platform.iot.mqtt.MqttFailureEvidenceRecorder;
import com.platform.iot.mqtt.MqttSslContextFactory;
import com.platform.iot.mqtt.MqttTlsException;
import com.platform.iot.mqtt.MqttTlsProperties;
import com.platform.iot.quality.DataPointConfigSnapshotUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;

import javax.net.ssl.HttpsURLConnection;

@Configuration
@ConditionalOnProperty(
        prefix = "mqtt",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
/**
 * 本地平台旧单点与标准多字段 MQTT 连接入口。
 *
 * <p>该配置连接云端 EMQX，并按精确 Topic 把旧单点报文交给
 * {@link HvacMqttMessageHandler}，把标准多字段报文交给
 * {@link StandardTelemetryMqttMessageHandler}。配置层只负责协议路由和 QoS 1 ACK，
 * 不解析设备归属，也不直接访问 MySQL 或 TDengine。</p>
 *
 * <p>业务拒绝和无法通过重投修复的 JSON 毒消息会确认；本地配置快照不可用、
 * TDengine 写入失败或未分类运行时异常不确认。初次连接失败由独立调度器持续重试，
 * 首次连接成功后交给 Paho 自动重连并恢复订阅。</p>
 */
public class MqttConfig {

    private static final Logger log = LoggerFactory.getLogger(MqttConfig.class);
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private final ObjectMapper objectMapper;
    private final HvacMqttMessageHandler hvacMqttMessageHandler;
    private final StandardTelemetryMqttMessageHandler standardTelemetryMqttMessageHandler;
    private final TelemetryV2MqttMessageHandler telemetryV2MqttMessageHandler;
    private final MqttTlsProperties tlsProperties;
    private final MqttSslContextFactory sslContextFactory;
    private final MqttFailureClassifier failureClassifier;
    private final MeterRegistry meterRegistry;
    private final MqttFailureEvidenceRecorder failureEvidenceRecorder;
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    @Value("${mqtt.broker-url}")
    private String brokerUrl;

    @Value("${mqtt.client-id}")
    private String clientId;

    @Value("${mqtt.username}")
    private String username;

    @Value("${mqtt.password}")
    private String password;

    @Value("${mqtt.topics.upstream}")
    private String[] upstreamTopics;

    @Value("${mqtt.topics.standard-upstream:device/telemetry/up}")
    private String[] standardUpstreamTopics;

    @Value("${mqtt.topics.v2-upstream:platform/telemetry/v2/up}")
    private String[] v2UpstreamTopics;

    @Value("${mqtt.initial-retry-millis:5000}")
    private long initialRetryMillis;

    @Value("${mqtt.max-retry-millis:120000}")
    private long maxRetryMillis;

    @Value("${mqtt.security-retry-millis:300000}")
    private long securityRetryMillis;

    @Value("${mqtt.retry-multiplier:2.0}")
    private double retryMultiplier;

    @Value("${mqtt.retry-jitter-ratio:0.2}")
    private double retryJitterRatio;

    public MqttConfig(
            ObjectMapper objectMapper,
            HvacMqttMessageHandler hvacMqttMessageHandler,
            StandardTelemetryMqttMessageHandler standardTelemetryMqttMessageHandler,
            TelemetryV2MqttMessageHandler telemetryV2MqttMessageHandler,
            MqttTlsProperties tlsProperties,
            MqttSslContextFactory sslContextFactory,
            MqttFailureClassifier failureClassifier,
            MeterRegistry meterRegistry,
            MqttFailureEvidenceRecorder failureEvidenceRecorder) {
        this.objectMapper = objectMapper;
        this.hvacMqttMessageHandler = hvacMqttMessageHandler;
        this.standardTelemetryMqttMessageHandler = standardTelemetryMqttMessageHandler;
        this.telemetryV2MqttMessageHandler = telemetryV2MqttMessageHandler;
        this.tlsProperties = tlsProperties;
        this.sslContextFactory = sslContextFactory;
        this.failureClassifier = failureClassifier;
        this.meterRegistry = meterRegistry;
        this.failureEvidenceRecorder = failureEvidenceRecorder;
    }

    /** 首次连接失败后的单线程重试调度器，不承载 MQTT 消息业务处理。 */
    @Bean(name = "mqttTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler mqttTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("mqtt-connect-");
        return scheduler;
    }

    /** 创建拥有固定 clientId 的本地订阅客户端，稳定会话由 cleanSession=false 保留。 */
    @Bean
    public IMqttClient mqttClient() throws MqttException {
        return new MqttClient(brokerUrl, clientId, new MemoryPersistence());
    }

    /** 应用启动后注册回调并尝试连接；连接失败不阻断平台其他模块启动。 */
    @Bean
    public CommandLineRunner initMqttClient(
            IMqttClient client,
            @Qualifier("mqttTaskScheduler") TaskScheduler scheduler) {
        return args -> {
            if (!routingConfigurationValid()) {
                return;
            }
            client.setManualAcks(true);
            client.setCallback(callback(client));
            connectOrScheduleRetry(client, scheduler);
        };
    }

    private void connectOrScheduleRetry(IMqttClient client, TaskScheduler scheduler) {
        if (client.isConnected() || !connecting.compareAndSet(false, true)) {
            return;
        }
        try {
            MqttConnectOptions options = connectOptions();
            client.connect(options);
            subscribeUpstream(client);
            consecutiveFailures.set(0);
            log.info("本地MQTT客户端已连接云端EMQX: broker={}, topics={}",
                    brokerUrl, configuredTopics());
        } catch (MqttException | MqttTlsException exception) {
            MqttFailureCategory category = failureClassifier.classify(exception);
            meterRegistry.counter("iot.mqtt.connection.failure", "category", category.name())
                    .increment();
            failureEvidenceRecorder.record("PLATFORM", category, brokerUrl);
            long retryDelay = retryDelay(category);
            log.warn("本地MQTT连接失败: category={}, retryMillis={}, broker={}",
                    category, retryDelay, brokerUrl);
            scheduler.schedule(
                    () -> connectOrScheduleRetry(client, scheduler),
                    Instant.now().plusMillis(retryDelay));
        } finally {
            connecting.set(false);
        }
    }

    private MqttConnectOptions connectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(username);
        options.setPassword(password.toCharArray());
        options.setAutomaticReconnect(true);
        options.setCleanSession(false);
        options.setConnectionTimeout(10);
        if (tlsProperties.isEnabled()) {
            if (!(brokerUrl.startsWith("ssl://") || brokerUrl.startsWith("wss://"))) {
                throw new MqttTlsException("启用 TLS 时 broker-url 必须使用 ssl:// 或 wss://");
            }
            options.setSocketFactory(sslContextFactory.create(tlsProperties).getSocketFactory());
            options.setSSLHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
            options.setHttpsHostnameVerificationEnabled(true);
        } else if (!tlsProperties.isAllowPlaintextForTests()) {
            throw new MqttTlsException(
                    "禁止明文 MQTT；仅隔离自动化测试可显式开启 allow-plaintext-for-tests");
        }
        return options;
    }

    private long retryDelay(MqttFailureCategory category) {
        int failures = consecutiveFailures.getAndIncrement();
        if (isSecurityFailure(category)) {
            return securityRetryMillis;
        }
        double exponential = initialRetryMillis * Math.pow(retryMultiplier, Math.min(failures, 20));
        long capped = Math.min(maxRetryMillis, Math.max(initialRetryMillis, (long) exponential));
        double jitter = Math.max(0.0, Math.min(retryJitterRatio, 1.0));
        long delta = (long) (capped * jitter);
        return delta == 0 ? capped : ThreadLocalRandom.current().nextLong(
                Math.max(1L, capped - delta), capped + delta + 1L);
    }

    private boolean isSecurityFailure(MqttFailureCategory category) {
        return switch (category) {
            case CA_UNTRUSTED, CERTIFICATE_TIME_INVALID, HOSTNAME_MISMATCH,
                 CLIENT_CERTIFICATE_REJECTED, BAD_CREDENTIALS, NOT_AUTHORIZED,
                 SUBSCRIBE_DENIED, PUBLISH_DENIED, TLS_CONFIGURATION -> true;
            default -> false;
        };
    }

    private MqttCallbackExtended callback(IMqttClient client) {
        return new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                if (!reconnect) {
                    return;
                }
                try {
                    subscribeUpstream(client);
                    log.info("MQTT自动重连后已恢复上行订阅: {}", configuredTopics());
                } catch (MqttException exception) {
                    log.error("MQTT自动重连后恢复上行订阅失败", exception);
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                log.warn("MQTT连接断开，等待自动重连: {}",
                        cause == null ? "unknown" : cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                boolean acknowledge = true;
                boolean applicationAckPublished = false;
                V2ProcessingResult v2Result = null;
                try {
                    byte[] payloadBytes = message.getPayload();
                    if (payloadBytes == null || payloadBytes.length == 0) {
                        log.warn("MQTT报文为空，已确认丢弃: topic={}", topic);
                        return;
                    }
                    if (payloadBytes.length > MAX_PAYLOAD_BYTES) {
                        log.warn("MQTT报文超过64KiB，已确认丢弃: topic={}, bytes={}",
                                topic, payloadBytes.length);
                        return;
                    }

                    long localReceivedTime = System.currentTimeMillis();
                    if (containsTopic(v2UpstreamTopics, topic)) {
                        v2Result = telemetryV2MqttMessageHandler.handle(
                                payloadBytes, localReceivedTime);
                        acknowledge = !v2Result.retryable();
                        if (acknowledge && v2Result.requiresApplicationAck()) {
                            byte[] ackPayload = objectMapper.writeValueAsBytes(
                                    v2Result.applicationAck());
                            client.publish(v2Result.ackTopic(), ackPayload, 1, false);
                            applicationAckPublished = true;
                        }
                        log.debug("V2 MQTT 报文处理完成: resultCode={}, ackMode={}, metrics={}",
                                v2Result.resultCode(), v2Result.actualAckMode(),
                                v2Result.processedMetrics());
                        return;
                    }
                    if (containsTopic(standardUpstreamTopics, topic)) {
                        StandardTelemetryResult result = standardTelemetryMqttMessageHandler.handle(
                                payloadBytes, localReceivedTime);
                        acknowledge = result.shouldAcknowledge();
                        log.debug("标准多字段MQTT报文处理完成: outcome={}, metrics={}",
                                result.outcome(), result.processedMetrics());
                        return;
                    }
                    if (containsTopic(upstreamTopics, topic)) {
                        Map<String, Object> payload = objectMapper.readValue(
                                payloadBytes, new TypeReference<>() {
                                });
                        if (!payload.containsKey("pointCode")) {
                            // 缺少 pointCode 的载荷属于已经下线的旧电表格式，不能进入其他链路。
                            log.warn("拒绝旧格式MQTT报文：缺少pointCode，已确认丢弃: topic={}",
                                    topic);
                            return;
                        }
                        HvacIngestionResult result = hvacMqttMessageHandler.handle(
                                payload, localReceivedTime);
                        acknowledge = result.shouldAcknowledge();
                        log.debug("旧单点MQTT报文处理完成: pointCode={}, outcome={}",
                                payload.get("pointCode"), result.outcome());
                        return;
                    }
                    log.warn("拒绝未配置路由的MQTT主题并确认丢弃: topic={}", topic);
                } catch (MqttException exception) {
                    acknowledge = false;
                    if (v2Result != null) {
                        telemetryV2MqttMessageHandler.recordApplicationAckFailure(
                                v2Result.canonicalMessageId(), exception.getMessage());
                    }
                    log.error("V2 应用 ACK 发布失败，保留原消息重投: topic={}", topic);
                } catch (IOException | IllegalArgumentException exception) {
                    log.warn("MQTT报文JSON格式错误，已确认丢弃: topic={}, reason={}",
                            topic, exception.getMessage());
                } catch (DataPointConfigSnapshotUnavailableException exception) {
                    acknowledge = false;
                    log.warn("本地测点配置尚不可用，保留MQTT重投: topic={}", topic);
                } catch (RuntimeException exception) {
                    acknowledge = false;
                    log.error("MQTT业务处理异常，保留重投: topic={}, reason={}",
                            topic, exception.getMessage());
                } finally {
                    if (acknowledge) {
                        boolean inboundAckObserved = acknowledge(client, topic, message);
                        if (inboundAckObserved && v2Result != null) {
                            try {
                                telemetryV2MqttMessageHandler.markDeliveryCompleted(
                                        v2Result.canonicalMessageId(), applicationAckPublished);
                            } catch (RuntimeException exception) {
                                log.error("V2 投递证据回写失败，原消息已完成消费确认: messageId={}",
                                        v2Result.canonicalMessageId());
                            }
                        }
                    }
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // 本客户端只订阅设备上行，没有平台下行发布，空实现仅满足 Paho 回调契约。
            }
        };
    }

    private void subscribeUpstream(IMqttClient client) throws MqttException {
        String[] topics = configuredTopics().toArray(String[]::new);
        int[] qos = new int[topics.length];
        Arrays.fill(qos, 1);
        client.subscribe(topics, qos);
    }

    private boolean routingConfigurationValid() {
        Set<String> legacy = topicSet(upstreamTopics);
        Set<String> standard = topicSet(standardUpstreamTopics);
        Set<String> v2 = topicSet(v2UpstreamTopics);
        if (legacy.isEmpty() || standard.isEmpty() || v2.isEmpty()) {
            log.error("MQTT客户端初始化失败：旧单点、标准V1和V2上行主题都必须配置");
            return false;
        }
        Set<String> all = new LinkedHashSet<>();
        int configuredCount = legacy.size() + standard.size() + v2.size();
        all.addAll(legacy);
        all.addAll(standard);
        all.addAll(v2);
        if (all.size() != configuredCount) {
            log.error("MQTT客户端初始化失败：旧单点、标准V1与V2主题不能重叠");
            return false;
        }
        return true;
    }

    private Set<String> configuredTopics() {
        Set<String> topics = new LinkedHashSet<>(topicSet(upstreamTopics));
        topics.addAll(topicSet(standardUpstreamTopics));
        topics.addAll(topicSet(v2UpstreamTopics));
        return topics;
    }

    private Set<String> topicSet(String[] topics) {
        Set<String> result = new LinkedHashSet<>();
        if (topics != null) {
            Arrays.stream(topics)
                    .filter(topic -> topic != null && !topic.isBlank())
                    .map(String::trim)
                    .forEach(result::add);
        }
        return result;
    }

    private boolean containsTopic(String[] topics, String actualTopic) {
        return topicSet(topics).contains(actualTopic);
    }

    private boolean acknowledge(IMqttClient client, String topic, MqttMessage message) {
        try {
            client.messageArrivedComplete(message.getId(), message.getQos());
            return true;
        } catch (MqttException exception) {
            log.error("MQTT手动确认失败: topic={}, messageId={}",
                    topic, message.getId(), exception);
            return false;
        }
    }
}
