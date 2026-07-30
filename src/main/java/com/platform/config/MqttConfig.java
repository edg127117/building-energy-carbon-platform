package com.platform.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.ingest.HvacIngestionResult;
import com.platform.iot.ingest.HvacMqttMessageHandler;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/**
 * HVAC 19 测点 MQTT 连接与协议入口。
 *
 * <p>该配置只负责连接 EMQX、订阅可信上行主题、做载荷大小与 JSON 基础校验，
 * 再把业务载荷交给 {@link HvacMqttMessageHandler}。测点身份、质量判断和 TDengine
 * 写入不放在配置层，避免重新形成协议与业务耦合。</p>
 *
 * <p>QoS 1 使用手动确认：落库成功、重复数据和业务无效毒消息都会确认；
 * TDengine 存储失败不确认，从而保留由 EMQX 重新投递的机会。</p>
 *
 * <p>整个配置受 {@code mqtt.enabled} 控制。测试环境关闭后不会创建 Client 或执行
 * 连接任务，从而避免普通自动化测试接触真实 Broker；采集业务单元测试仍可直接
 * 构造本类验证协议和 ACK 语义。</p>
 */
@Configuration
@ConditionalOnProperty(
        prefix = "mqtt",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MqttConfig {

    private static final Logger log = LoggerFactory.getLogger(MqttConfig.class);
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private final ObjectMapper objectMapper;
    private final HvacMqttMessageHandler hvacMqttMessageHandler;

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

    public MqttConfig(
            ObjectMapper objectMapper,
            HvacMqttMessageHandler hvacMqttMessageHandler) {
        this.objectMapper = objectMapper;
        this.hvacMqttMessageHandler = hvacMqttMessageHandler;
    }

    /**
     * 创建独立 MQTT 客户端。V1 没有控制下行，因此该 Bean 只供上行连接任务使用。
     */
    @Bean
    public IMqttClient mqttClient() throws MqttException {
        return new MqttClient(brokerUrl, clientId, new MemoryPersistence());
    }

    /**
     * 应用启动后连接 EMQX 并订阅 HVAC 上行主题。
     *
     * <p>连接失败只影响 MQTT 采集，不拖垮登录、查询等无关模块；Paho 自动重连后
     * 会重新订阅相同上行主题。</p>
     */
    @Bean
    public CommandLineRunner initMqttClient(IMqttClient client) {
        return args -> {
            if (upstreamTopics == null || upstreamTopics.length == 0) {
                log.error("MQTT 客户端初始化失败：未配置 HVAC 上行主题");
                return;
            }

            try {
                MqttConnectOptions options = new MqttConnectOptions();
                options.setUserName(username);
                options.setPassword(password.toCharArray());
                options.setAutomaticReconnect(true);
                options.setCleanSession(false);
                options.setConnectionTimeout(10);

                // 手动确认把“Broker 已投递”和“时序数据已按业务语义处理”绑定起来。
                client.setManualAcks(true);
                client.setCallback(callback(client));
                client.connect(options);
                subscribeUpstream(client);
                log.info("MQTT HVAC 客户端已连接至 {}，上行主题={}",
                        brokerUrl, Arrays.toString(upstreamTopics));
            } catch (MqttException e) {
                // MQTT 暂时不可用时允许平台其他模块启动，Paho 连接恢复由后续运维处理。
                log.error("MQTT HVAC 客户端初始化失败", e);
            }
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
                    log.info("MQTT 自动重连后已恢复 HVAC 上行订阅: {}",
                            Arrays.toString(upstreamTopics));
                } catch (MqttException e) {
                    log.error("MQTT 自动重连后恢复 HVAC 上行订阅失败", e);
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                log.warn("MQTT 连接断开，等待自动重连: {}",
                        cause == null ? "unknown" : cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                boolean acknowledge = true;
                try {
                    byte[] payloadBytes = message.getPayload();
                    if (payloadBytes == null || payloadBytes.length == 0) {
                        log.warn("MQTT 报文为空，已确认丢弃: topic={}", topic);
                        return;
                    }
                    if (payloadBytes.length > MAX_PAYLOAD_BYTES) {
                        log.warn("MQTT 报文超过 64 KiB，已确认丢弃: topic={}, bytes={}",
                                topic, payloadBytes.length);
                        return;
                    }

                    String payloadText =
                            new String(payloadBytes, StandardCharsets.UTF_8);
                    Map<String, Object> payload = objectMapper.readValue(
                            payloadText, new TypeReference<>() {
                            });
                    if (!payload.containsKey("pointCode")) {
                        // 缺少 pointCode 的载荷属于已经下线的旧电表格式，不能再进入其他链路。
                        log.warn("拒绝旧格式 MQTT 报文：缺少 pointCode，已确认丢弃: topic={}",
                                topic);
                        return;
                    }

                    HvacIngestionResult result = hvacMqttMessageHandler.handle(
                            payload, System.currentTimeMillis());
                    acknowledge = result.shouldAcknowledge();
                    log.debug("HVAC MQTT 报文处理完成: pointCode={}, outcome={}",
                            payload.get("pointCode"), result.outcome());
                } catch (Exception e) {
                    // JSON 错误属于无法通过重投修复的毒消息，记录原因后确认，避免阻塞队列。
                    log.warn("MQTT HVAC 报文解析失败，已确认丢弃: topic={}, reason={}",
                            topic, e.getMessage());
                } finally {
                    if (acknowledge) {
                        acknowledge(client, topic, message);
                    }
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // V1 没有控制下行，保留空实现只为满足 Paho 回调契约。
            }
        };
    }

    private void subscribeUpstream(IMqttClient client) throws MqttException {
        int[] qos = new int[upstreamTopics.length];
        Arrays.fill(qos, 1);
        client.subscribe(upstreamTopics, qos);
    }

    private void acknowledge(
            IMqttClient client,
            String topic,
            MqttMessage message) {
        try {
            client.messageArrivedComplete(message.getId(), message.getQos());
        } catch (MqttException e) {
            log.error("MQTT 手动确认失败: topic={}, messageId={}",
                    topic, message.getId(), e);
        }
    }
}
