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
    private final AtomicBoolean connecting = new AtomicBoolean();

    public TelemetryAdapterMqttBridge(
            IMqttClient client,
            @Qualifier("adapterMqttTaskScheduler") TaskScheduler scheduler,
            AdapterMqttProperties properties,
            ObjectMapper objectMapper,
            JsonTelemetryAdapter telemetryAdapter,
            ProtocolProfileProvider profileProvider) {
        this.client = client;
        this.scheduler = scheduler;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.telemetryAdapter = telemetryAdapter;
        this.profileProvider = profileProvider;
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
            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName(properties.getUsername());
            options.setPassword(properties.getPassword().toCharArray());
            options.setAutomaticReconnect(true);
            options.setCleanSession(false);
            options.setConnectionTimeout(10);
            client.connect(options);
            subscribeRawTopic();
            log.info("云端报文适配器已连接EMQX: rawTopic={}, standardTopic={}",
                    properties.getRawTopic(), properties.getStandardTopic());
        } catch (MqttException exception) {
            log.warn("云端报文适配器首次连接失败，将在{}毫秒后重试: {}",
                    properties.getInitialRetryMillis(), exception.getMessage());
            scheduler.schedule(
                    this::connectOrScheduleRetry,
                    Instant.now().plusMillis(properties.getInitialRetryMillis()));
        } finally {
            connecting.set(false);
        }
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
        boolean acknowledge = true;
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
            client.publish(properties.getStandardTopic(), canonicalPayload, 1, false);
            log.debug("设备报文转换完成: profile={}, identityType={}, metrics={}",
                    canonical.profileCode(),
                    canonical.deviceIdentity().type(),
                    canonical.metrics().size());
        } catch (MqttException exception) {
            acknowledge = false;
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
        client.subscribe(properties.getRawTopic(), 1);
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
}
