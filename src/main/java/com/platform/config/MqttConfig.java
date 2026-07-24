package com.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.core.bus.IotMessagePublisher;
import com.platform.iot.core.model.DeviceMessage;
import com.platform.iot.ingest.HvacIngestionResult;
import com.platform.iot.ingest.HvacMqttMessageHandler;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
/**
 * MQTT 客户端配置与接收(支持双向收发)
 * 作用：Spring Boot 启动后自动连接本地的 EMQX，并持续监听电表发来的数据。
 * 收到数据后，扔给 IotMessagePublisher (内存总线)，实现完全解耦。
 */
@Configuration
public class MqttConfig {

    private static final Logger log = LoggerFactory.getLogger(MqttConfig.class);
    // 单条 MQTT 报文最大允许大小（防止超大包导致内存/CPU 压力，影响 Demo 稳定性）
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    // deviceId 白名单：避免异常/恶意 deviceId 把后续存储链路打挂
    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9:_-]{1,64}$");

    // EMQX 底层离线广播主题
    private static final String OFFLINE_TOPIC = "$SYS/brokers/+/clients/+/disconnected";
    // Demo 阶段：后端同时监听指令下行主题，充当虚拟设备自动回执 ACK
    private static final String CONTROL_DOWN_TOPIC = "device/control/down/+";

    @Value("${mqtt.broker-url}")
    private String brokerUrl;

    @Value("${mqtt.client-id}")
    private String clientId;

    @Value("${mqtt.username}")
    private String username;

    @Value("${mqtt.password}")
    private String password;

    /**
     * 存量 Demo 设备回执模拟器开关。
     * 本期只验收 19 个真实测点，默认关闭，避免虚拟设备数据混入现场数据。
     */
    @Value("${mqtt.demo-simulator-enabled:false}")
    private boolean demoSimulatorEnabled;

    @Value("${mqtt.topics.upstream}")
    private String[] upstreamTopics;

    @Autowired
    private IotMessagePublisher iotMessagePublisher;

    @Autowired
    private ObjectMapper objectMapper; // Spring Boot 自带的 JSON 解析

    @Autowired
    private HvacMqttMessageHandler hvacMqttMessageHandler;

    /**
     * 构建订阅主题。真实业务和设备离线主题始终订阅；
     * 只有显式开启 Demo 开关时，才让后端充当虚拟设备监听指令下行。
     */
    private String[] getAllSubscribeTopics() {
        int extraTopicCount = demoSimulatorEnabled ? 2 : 1;
        String[] allTopics = Arrays.copyOf(upstreamTopics, upstreamTopics.length + extraTopicCount);
        allTopics[upstreamTopics.length] = OFFLINE_TOPIC;
        if (demoSimulatorEnabled) {
            allTopics[upstreamTopics.length + 1] = CONTROL_DOWN_TOPIC;
        }
        return allTopics;
    }
    /**
     * 将 Client 实例暴露为 Spring Bean，供后续 MqttPublisher 下发指令时注入使用
     */
    @Bean
    public IMqttClient mqttClient() throws MqttException {
        return new MqttClient(brokerUrl, clientId, new MemoryPersistence());
    }

    /**
     * 使用 CommandLineRunner 确保 Spring Boot 核心框架启动完毕后，再连接 MQTT
     */
    @Bean
    public CommandLineRunner initMqttClient(IMqttClient client) {
        return args -> {
            try {
                // topics 未配置时直接退出，避免后续订阅阶段 NPE
                if (upstreamTopics == null || upstreamTopics.length == 0) {
                    log.error("💥 MQTT 客户端初始化失败：未配置 upstream topics");
                    return;
                }

                // 1. 初始化 MQTT 客户端
                //MqttClient client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());

                // 2. 配置连接参数
                MqttConnectOptions options = new MqttConnectOptions();
                options.setUserName(username);
                options.setPassword(password.toCharArray());
                options.setAutomaticReconnect(true); // 开启断线自动重连
                options.setCleanSession(false);      // 保持会话，离线时 EMQX 会帮忙暂存消息
                options.setConnectionTimeout(10);

                // 手动确认使“MQTT已收到”和“TDengine已落盘”保持一致。
                client.setManualAcks(true);

                // 3. 设置回调函数（相当于装了一个监听器）
                client.setCallback(new MqttCallbackExtended() {
                    @Override
                    public void connectComplete(boolean reconnect, String serverURI) {
                        if (reconnect) {
                            log.info("🎉 MQTT 自动重连成功！已恢复与 {} 的连接", serverURI);
                            try {
                                // 断线重连后，必须重新订阅“业务主题” + “离线主题”，防止在离线期间 EMQX 丢弃了订阅关系
                                String[] allTopics = getAllSubscribeTopics();
                                int[] qos = new int[allTopics.length];
                                Arrays.fill(qos, 1);
                                client.subscribe(allTopics, qos);
                                log.info("✅ 自动重连后：已重新订阅主题: {}", Arrays.toString(allTopics));
                            } catch (MqttException e) {
                                log.error("❌ 自动重连后：重新订阅主题失败", e);
                            }
                        }
                    }

                    @Override
                    public void connectionLost(Throwable cause) {
                        log.warn("🚨 MQTT 连接意外断开，正在尝试重连...");
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        boolean acknowledge = true;
                        try {
                            // 监听到设备掉线广播 (EMQX 系统主题)
                            // 必须放在 JSON 解析之前，因为 EMQX 默认发的离线消息结构和我们的业务格式不同
                            if (topic.endsWith("/disconnected")) {
                                String[] parts = topic.split("/");
                                if (parts.length >= 6) {
                                    String disconnectedDeviceId = parts[4]; // 提取出掉线的设备 ID

                                    // 忽略后端自身以及其他系统级客户端的掉线广播，只处理真正的硬件设备！
                                    if (clientId.equals(disconnectedDeviceId) || disconnectedDeviceId.startsWith("mqttjs_")) {
                                        log.debug("忽略非硬件设备的掉线广播: {}", disconnectedDeviceId);
                                        return;
                                    }
                                    log.warn("🔴 收到底层断开信号，设备掉线: {}", disconnectedDeviceId);
                                    DeviceMessage offlineMsg = DeviceMessage.builder()
                                            .messageId(UUID.randomUUID().toString())
                                            .deviceId(disconnectedDeviceId)
                                            .type("offline") // 特殊类型：离线事件
                                            .timestamp(System.currentTimeMillis())
                                            .build();
                                    iotMessagePublisher.publish(offlineMsg);
                                }
                                return; // 掉线报文处理完毕，直接 return，禁止往下走校验
                            }

                            // 【Demo 模拟器】：后端自身监听指令下行 topic，充当虚拟设备自动回复 ACK
                            // 当收到 device/control/down/{deviceId} 的消息时，模拟设备处理并回复
                            if (demoSimulatorEnabled && topic.startsWith("device/control/down/")) {
                                String simulatedDeviceId = topic.substring("device/control/down/".length());
                                if (simulatedDeviceId.isEmpty()) return;

                                try {
                                    byte[] cmdBytes = message.getPayload();
                                    if (cmdBytes == null || cmdBytes.length == 0) return;
                                    String cmdPayload = new String(cmdBytes, StandardCharsets.UTF_8);
                                    Map<String, Object> cmdMap = objectMapper.readValue(cmdPayload, Map.class);
                                    String commandId = (String) cmdMap.get("commandId");
                                    if (commandId == null) return;

                                    log.info("[Demo设备模拟] 虚拟设备 {} 收到指令 {}，1.5秒后自动回复 ACK", simulatedDeviceId, commandId);

                                    // 判断开关指令方向（action 可能是嵌套 Map）
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> actionMap = (Map<String, Object>) cmdMap.get("action");
                                    boolean isShutdown = false;
                                    boolean isPowerOn = false;
                                    if (actionMap != null) {
                                        Object sw = actionMap.get("switch");
                                        isShutdown = (sw instanceof Boolean && !((Boolean) sw))
                                                || "off".equalsIgnoreCase(String.valueOf(sw));
                                        isPowerOn = (sw instanceof Boolean && ((Boolean) sw))
                                                || "on".equalsIgnoreCase(String.valueOf(sw));
                                    }

                                    // ACK 回执（无论开关都先回复）：1.5 秒后发送
                                    java.util.concurrent.CompletableFuture
                                            .runAsync(() -> {
                                                DeviceMessage ackMsg = DeviceMessage.builder()
                                                        .messageId(UUID.randomUUID().toString())
                                                        .deviceId(simulatedDeviceId)
                                                        .type("reply")
                                                        .timestamp(System.currentTimeMillis())
                                                        .data(Map.of("commandId", commandId, "success", true,
                                                                "message", "指令已成功执行（Demo 模拟）"))
                                                        .build();
                                                iotMessagePublisher.publish(ackMsg);
                                                log.info("[Demo设备模拟] 虚拟设备 {} 已回执 ACK: {}", simulatedDeviceId, commandId);
                                            }, java.util.concurrent.CompletableFuture.delayedExecutor(1500, java.util.concurrent.TimeUnit.MILLISECONDS));

                                    // 关机：3 秒后发送 offline 事件 → MySQL 状态变 0 → 前端 Badge 变灰
                                    if (isShutdown) {
                                        log.info("[Demo设备模拟] 检测到关机指令，3秒后将模拟设备 {} 离线", simulatedDeviceId);
                                        java.util.concurrent.CompletableFuture
                                                .runAsync(() -> {
                                                    DeviceMessage offlineMsg = DeviceMessage.builder()
                                                            .messageId(UUID.randomUUID().toString())
                                                            .deviceId(simulatedDeviceId)
                                                            .type("offline")
                                                            .timestamp(System.currentTimeMillis())
                                                            .data(Map.of("reason", "关机指令执行"))
                                                            .build();
                                                    iotMessagePublisher.publish(offlineMsg);
                                                    log.info("[Demo设备模拟] 虚拟设备 {} 已离线（关机模拟）", simulatedDeviceId);
                                                }, java.util.concurrent.CompletableFuture.delayedExecutor(3000, java.util.concurrent.TimeUnit.MILLISECONDS));
                                    }

                                    // 开机：3 秒后发送 property 事件 → MySQL 状态变 1 → 前端 Badge 变绿 + 图表有数据
                                    if (isPowerOn) {
                                        log.info("[Demo设备模拟] 检测到开机指令，3秒后将模拟设备 {} 上线并上报模拟数据", simulatedDeviceId);
                                        java.util.concurrent.CompletableFuture
                                                .runAsync(() -> {
                                                    DeviceMessage propertyMsg = DeviceMessage.builder()
                                                            .messageId(UUID.randomUUID().toString())
                                                            .deviceId(simulatedDeviceId)
                                                            .type("property")
                                                            .timestamp(System.currentTimeMillis())
                                                            .data(Map.of("voltage_a", 220.0 + Math.random() * 5,
                                                                    "current_a", 10.0 + Math.random() * 3,
                                                                    "active_power", 2.2 + Math.random() * 0.5))
                                                            .build();
                                                    iotMessagePublisher.publish(propertyMsg);
                                                    log.info("[Demo设备模拟] 虚拟设备 {} 已上线并上报测点数据（开机模拟）", simulatedDeviceId);
                                                }, java.util.concurrent.CompletableFuture.delayedExecutor(3000, java.util.concurrent.TimeUnit.MILLISECONDS));
                                    }
                                } catch (Exception e) {
                                    log.error("[Demo设备模拟] 解析指令失败", e);
                                }
                                return; // 已处理，直接返回
                            }


                            // a. 拿到硬件发来的原始报文 (这里先假设是 JSON 格式方便测试)
                            byte[] payloadBytes = message.getPayload();
                            // 空包直接丢弃，避免后续 JSON 解析异常
                            if (payloadBytes == null || payloadBytes.length == 0) {
                                log.warn("⚠️ 收到空 MQTT 报文，已忽略: Topic={}", topic);
                                return;
                            }
                            // 超大包丢弃：避免 demo 现场因为误发大包导致服务卡顿/崩溃
                            if (payloadBytes.length > MAX_PAYLOAD_BYTES) {
                                log.warn("⚠️ MQTT 报文过大已丢弃: Topic={}, bytes={}", topic, payloadBytes.length);
                                return;
                            }

                            String payload = new String(payloadBytes, StandardCharsets.UTF_8);
                            // Debug 模式只打印截断预览，避免日志刷爆与敏感数据泄露
                            if (log.isDebugEnabled()) {
                                String preview = payload.length() <= 512 ? payload : payload.substring(0, 512) + "...(truncated)";
                                log.debug("📥 收到 MQTT 报文: Topic={}, PayloadPreview={}", topic, preview);
                            }

                            // b. 解析逻辑：将 JSON 转换为 Map 载荷
                            Map<String, Object> dataMap = objectMapper.readValue(payload, Map.class);
                            String deviceId = (String) dataMap.get("deviceId");
                            // deviceId 不合法直接丢弃，避免把异常数据传入存储与状态更新线程
                            if (deviceId == null || !DEVICE_ID_PATTERN.matcher(deviceId).matches()) {
                                log.warn("⚠️ MQTT 报文缺少或包含非法 deviceId，已忽略: Topic={}, deviceId={}", topic, deviceId);
                                return;
                            }

                            // 【HVAC 分流】如果报文中包含 pointCode 字段，走空调测点专有链路
                            if (dataMap.containsKey("pointCode")) {
                                HvacIngestionResult result =
                                        hvacMqttMessageHandler.handle(dataMap, System.currentTimeMillis());
                                acknowledge = result.shouldAcknowledge();
                                log.debug("空调测点处理完成: pointCode={}, outcome={}",
                                        dataMap.get("pointCode"), result.outcome());
                                return; // HVAC 报文处理完毕，不走后续电表链路
                            }

                            // 动态判断消息类型。如果包含 commandId，说明是硬件返回的指令 ACK
                            String msgType = dataMap.containsKey("commandId") ? "reply" : "property";
                            // c. 封装成系统统一的“物模型消息”标准件
                            DeviceMessage deviceMessage = DeviceMessage.builder()
                                    .messageId(UUID.randomUUID().toString())
                                    .deviceId(deviceId)
                                    .type(msgType) // 根据报文特征动态设置类型
                                    .data(dataMap)
                                    .timestamp(System.currentTimeMillis())
                                    .build();

                            // d. 丢给总线,后面的事情（存 TDengine、查 MySQL 状态）MQTT 统统不管
                            iotMessagePublisher.publish(deviceMessage);

                        } catch (Exception e) {
                            log.error("❌ MQTT 报文解析或分发失败: ", e);
                        } finally {
                            if (acknowledge) {
                                try {
                                    // 无效毒消息也要确认；只有存储故障才保留QoS 1重投机会。
                                    client.messageArrivedComplete(message.getId(), message.getQos());
                                } catch (MqttException ackError) {
                                    log.error("MQTT手动确认失败: topic={}, messageId={}",
                                            topic, message.getId(), ackError);
                                }
                            }
                        }
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {
                        // 主要是下发指令时用，这里暂不处理
                    }
                });

                // 4. 正式连接并订阅主题
                String[] allTopics = getAllSubscribeTopics();
                client.connect(options);
                //构造与主题数量匹配的 QoS 数组 (默认都为 1)
                int[] qos = new int[allTopics.length];
                Arrays.fill(qos, 1);// QoS=1 保证消息至少到达一次

                // 批量订阅所有的主题！
                client.subscribe(allTopics, qos);
                log.info("✅ MQTT 客户端启动成功！已连接至 {}，并订阅主题: {}", brokerUrl, Arrays.toString(allTopics));

            } catch (MqttException e) {
                log.error("💥 MQTT 客户端初始化失败", e);
            }
        };
    }
}
