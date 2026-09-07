package com.platform.modbus.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.modbus.config.ModbusEdgeProperties;
import com.platform.modbus.model.StandardTelemetryMessage;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将一个采样批次以QoS 1发布到平台V2 Topic，并在内存中关联平台应用ACK。
 *
 * <p>当前没有本地持久化队列；进程退出或重试耗尽后的消息不能恢复，不能据此宣称
 * 已完成断网补传闭环。</p>
 */
@Component
@ConditionalOnProperty(prefix = "modbus-edge", name = "enabled", havingValue = "true")
public class MqttTelemetryPublisher implements TelemetryPublisher, MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(MqttTelemetryPublisher.class);

    private final ModbusEdgeProperties.Mqtt properties;
    private final MqttSslContextFactory sslContextFactory;
    private final MqttFailureClassifier failureClassifier;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Object connectionLock = new Object();
    private final Map<String, Long> pendingAcks = new ConcurrentHashMap<>();
    private volatile MqttClient client;

    public MqttTelemetryPublisher(
            ModbusEdgeProperties edgeProperties,
            MqttSslContextFactory sslContextFactory,
            MqttFailureClassifier failureClassifier,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.properties = edgeProperties.getMqtt();
        this.sslContextFactory = sslContextFactory;
        this.failureClassifier = failureClassifier;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    public void publish(StandardTelemetryMessage message) {
        try {
            MqttClient active = connectedClient();
            byte[] payload = objectMapper.writeValueAsBytes(message);
            pendingAcks.put(message.canonicalMessageId(), clock.millis());
            try {
                active.publish(properties.getStandardTopic(), payload, 1, false);
                meterRegistry.counter("modbus.edge.mqtt.publish", "result", "success")
                        .increment();
            } catch (MqttException exception) {
                pendingAcks.remove(message.canonicalMessageId());
                throw exception;
            }
        } catch (TelemetryPublishException exception) {
            recordPublishFailure(failureClassifier.classify(exception));
            throw exception;
        } catch (IOException | MqttException exception) {
            MqttFailureCategory category = failureClassifier.classify(exception);
            recordPublishFailure(category);
            throw new TelemetryPublishException(category, "V2 MQTT发布失败", exception);
        }
    }

    private MqttClient connectedClient() throws MqttException {
        MqttClient current = client;
        if (current != null && current.isConnected()) {
            return current;
        }
        synchronized (connectionLock) {
            current = client;
            if (current == null) {
                current = new MqttClient(
                        properties.getBrokerUrl(), properties.getClientId(), new MemoryPersistence());
                current.setTimeToWait(properties.getOperationTimeout().toMillis());
                current.setCallback(this);
                client = current;
            }
            if (!current.isConnected()) {
                current.connect(connectOptions());
                subscribeAck(current);
            }
            return current;
        }
    }

    private MqttConnectOptions connectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        if (hasText(properties.getUsername())) {
            options.setUserName(properties.getUsername());
        }
        if (properties.getPassword() != null) {
            options.setPassword(properties.getPassword().toCharArray());
        }
        options.setAutomaticReconnect(true);
        options.setCleanSession(false);
        options.setConnectionTimeout(Math.toIntExact(properties.getConnectionTimeout().toSeconds()));
        ModbusEdgeProperties.Tls tls = properties.getTls();
        if (tls.isEnabled()) {
            options.setSocketFactory(sslContextFactory.create(tls).getSocketFactory());
            options.setSSLHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
            options.setHttpsHostnameVerificationEnabled(true);
        }
        return options;
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        MqttClient current = client;
        if (current == null) {
            return;
        }
        try {
            subscribeAck(current);
            if (reconnect) {
                log.info("Modbus边缘适配器MQTT重连并恢复ACK订阅");
            }
        } catch (MqttException exception) {
            log.warn("Modbus边缘适配器恢复ACK订阅失败: reason={}", exception.getMessage());
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        MqttFailureCategory category = failureClassifier.classify(cause);
        meterRegistry.counter("modbus.edge.mqtt.connection", "result", "lost",
                "failure.category", category.name()).increment();
        log.warn("Modbus边缘适配器MQTT连接断开: failureCategory={}", category);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            PlatformAck ack = objectMapper.readValue(message.getPayload(), PlatformAck.class);
            if (!"ADAPTER_ONLY".equals(ack.deliveryScope())
                    || !hasText(ack.canonicalMessageId())) {
                log.warn("拒绝范围不匹配或无法关联的Modbus应用ACK");
                return;
            }
            Long sentAt = pendingAcks.remove(ack.canonicalMessageId());
            meterRegistry.counter("modbus.edge.application.ack", "result",
                    sentAt == null ? "unmatched" : "received").increment();
        } catch (IOException exception) {
            meterRegistry.counter("modbus.edge.application.ack", "result", "invalid")
                    .increment();
            log.warn("拒绝格式无效的Modbus应用ACK: reason={}", exception.getMessage());
        }
    }

    @Override
    public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
        // 同步publish已经等待QoS 1交付，本回调不再承载业务成功语义。
    }

    @Scheduled(fixedDelayString = "${modbus-edge.mqtt.ack-scan-delay:5s}")
    void expireAcks() {
        long cutoff = clock.millis() - properties.getAckTimeout().toMillis();
        pendingAcks.entrySet().removeIf(entry -> {
            if (entry.getValue() >= cutoff) {
                return false;
            }
            meterRegistry.counter("modbus.edge.application.ack", "result", "timeout")
                    .increment();
            log.warn("Modbus应用ACK超时: canonicalMessageId={}", entry.getKey());
            return true;
        });
    }

    private void subscribeAck(MqttClient current) throws MqttException {
        current.subscribe(properties.getApplicationAckTopic(), 1);
    }

    private void recordPublishFailure(MqttFailureCategory category) {
        meterRegistry.counter("modbus.edge.mqtt.publish", "result", "failure",
                "failure.category", category.name()).increment();
        log.warn("Modbus边缘适配器MQTT发布失败: failureCategory={}", category);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @PreDestroy
    public void close() {
        MqttClient current = client;
        if (current == null) {
            return;
        }
        try {
            if (current.isConnected()) {
                current.disconnect();
            }
            current.close();
        } catch (MqttException exception) {
            log.warn("关闭Modbus边缘适配器MQTT客户端失败: reason={}", exception.getMessage());
        }
    }

    private record PlatformAck(String canonicalMessageId, String deliveryScope) {
    }
}
