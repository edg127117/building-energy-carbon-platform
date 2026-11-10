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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;

import javax.net.ssl.HttpsURLConnection;

@Configuration
@ConditionalOnProperty(
        prefix = "mqtt",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
/**
 * 本地平台旧单点、标准多字段及V2 MQTT连接入口。
 *
 * <p>该配置连接指定Broker，并按精确Topic把旧单点报文交给
 * {@link HvacMqttMessageHandler}，把标准多字段报文交给
 * {@link StandardTelemetryMqttMessageHandler}。配置层只负责协议路由和 QoS 1 ACK，
 * 不解析设备归属，也不直接访问MySQL或TDengine。V2应用ACK在回调外发布，
 * 发布成功后才确认原始入站消息；失败保留冻结回执和重投条件。</p>
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
    private final AtomicLong connectionEpoch = new AtomicLong();

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

    /** 连接、订阅重试与应用 ACK 共用单线程；同步网络等待不占用 Paho 回调。 */
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
        var client=new MqttClient(brokerUrl, clientId, new MemoryPersistence());
        client.setTimeToWait(10000);return client;
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
            client.setCallback(callback(client,scheduler));
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
            restoreSubscriptions(client, scheduler, connectionEpoch.get());
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

    private MqttCallbackExtended callback(IMqttClient client,TaskScheduler scheduler) {
        return new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                if (!reconnect) {
                    return;
                }
                // 持久会话的积压消息可能先于 SUBACK 到达。回调内等待订阅会阻塞消费，
                // 队列满后接收线程也无法读取 SUBACK/PINGRESP，造成持续超时重连。
                long epoch = connectionEpoch.get();
                scheduler.schedule(() -> restoreSubscriptions(client, scheduler, epoch), Instant.now());
            }

            @Override
            public void connectionLost(Throwable cause) {
                connectionEpoch.incrementAndGet();
                log.warn("MQTT连接断开，等待自动重连: {}",
                        cause == null ? "unknown" : cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                boolean acknowledge = true;
                boolean deferredAck=false;
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
                        if (message.isDuplicate()) {
                            meterRegistry.counter("iot.telemetry.v2.broker_redelivery")
                                    .increment();
                        }
                        v2Result = telemetryV2MqttMessageHandler.handle(
                                payloadBytes, localReceivedTime);
                        acknowledge = !v2Result.retryable();
                        if (acknowledge && v2Result.requiresApplicationAck()) {
                            byte[] ackPayload = objectMapper.writeValueAsBytes(
                                    v2Result.applicationAck());
                            var result=v2Result;
                            // ACK同步发布也不能占用Paho回调；发布完成后才确认原始V2消息。
                            long epoch=connectionEpoch.get();
                            scheduler.schedule(()->publishApplicationAck(client,scheduler,topic,message,result,ackPayload,epoch,1),Instant.now());
                            deferredAck=true;acknowledge=false;
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
                        if (v2Result != null) {
                            meterRegistry.counter("iot.telemetry.v2.inbound_ack", "result",
                                    inboundAckObserved ? "completed" : "failed").increment();
                        }
                    } else if (v2Result != null&&!deferredAck) {
                        meterRegistry.counter("iot.telemetry.v2.inbound_ack", "result", "failed")
                                .increment();
                    }
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // 应用ACK的完成与入站确认由发布任务处理，此回调不重复确认。
            }
        };
    }

    private void restoreSubscriptions(IMqttClient client, TaskScheduler scheduler, long epoch) {
        // 断线后由新的 connectComplete 恢复订阅，旧连接的重试不能跨代继续排队。
        if (connectionEpoch.get() != epoch || !client.isConnected()) {
            return;
        }
        try {
            subscribeUpstream(client);
            if (connectionEpoch.get() != epoch) {
                return;
            }
            consecutiveFailures.set(0);
            log.info("MQTT上行订阅已确认: topics={}", configuredTopics());
        } catch (MqttException exception) {
            if (connectionEpoch.get() != epoch) {
                return;
            }
            MqttFailureCategory category = exception.getReasonCode() == 128
                    ? MqttFailureCategory.SUBSCRIBE_DENIED : failureClassifier.classify(exception);
            long retryMillis = retryDelay(category);
            meterRegistry.counter("iot.mqtt.subscription.failure", "category", category.name()).increment();
            // 先保留恢复任务，诊断落库失败不能使在线但未订阅的连接永久停留。
            scheduler.schedule(() -> restoreSubscriptions(client, scheduler, epoch),
                    Instant.now().plusMillis(retryMillis));
            log.warn("MQTT上行订阅失败，等待重试: category={}, retryMillis={}", category, retryMillis);
            failureEvidenceRecorder.record("PLATFORM", category, brokerUrl);
        }
    }

    private void publishApplicationAck(IMqttClient client,TaskScheduler scheduler,String topic,MqttMessage message,V2ProcessingResult result,byte[] payload,long epoch,int attempt) {
        // 重连可能重新使用包编号；旧连接的延后任务只能由Broker重投恢复，不能确认新连接中的其他消息。
        if(connectionEpoch.get()!=epoch)return;
        try {
            client.publish(result.ackTopic(),payload,1,false);
            meterRegistry.counter("iot.telemetry.v2.application_ack","result","published").increment();
            if(connectionEpoch.get()!=epoch)return;
            boolean observed=acknowledge(client,topic,message);
            meterRegistry.counter("iot.telemetry.v2.inbound_ack","result",observed?"completed":"failed").increment();
        } catch(MqttException|RuntimeException failure) {
            meterRegistry.counter("iot.telemetry.v2.application_ack","result","failed").increment();
            meterRegistry.counter("iot.telemetry.v2.inbound_ack","result","failed").increment();
            // 在线发布窗口暂满时Broker未必立即重投；复用既有调度器有限重试同一冻结ACK，不再执行接入业务。
            if(attempt<3&&connectionEpoch.get()==epoch)
                scheduler.schedule(()->publishApplicationAck(client,scheduler,topic,message,result,payload,epoch,attempt+1),
                        Instant.now().plusMillis(Math.max(1,initialRetryMillis)));
            telemetryV2MqttMessageHandler.recordApplicationAckFailure(result.canonicalMessageId(),"MQTT_QOS1_APPLICATION_ACK_PUBLISH_FAILED");
            log.warn("V2应用ACK发布失败，保留原消息: topic={}, attempt={}, mqttReason={}",topic,attempt,
                    failure instanceof MqttException mqtt?mqtt.getReasonCode():"RUNTIME");
        }
    }

    private void subscribeUpstream(IMqttClient client) throws MqttException {
        String[] topics = configuredTopics().toArray(String[]::new);
        int[] qos = new int[topics.length];
        Arrays.fill(qos, 1);
        client.subscribe(topics, qos);
        // Paho 的批量订阅把 SUBACK 授权值写回 qos；部分主题被拒绝不会自动抛异常。
        if (Arrays.stream(qos).anyMatch(granted -> granted == 128)) {
            throw new MqttException(128);
        }
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
