package com.platform.iot.mqtt;

import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * MQTT下发工具
 */
@Component
public class MqttPublisher {

    private static final Logger log = LoggerFactory.getLogger(MqttPublisher.class);

    @Autowired
    private IMqttClient mqttClient;

    /**
     * 发送指令到指定的 Topic
     */
    public void publish(String topic, String payload) {
        try {
            MqttMessage mqttMessage = new MqttMessage(payload.getBytes());
            mqttMessage.setQos(1); // 保证至少到达一次
            mqttMessage.setRetained(false); // 控制指令一般不需要保留

            mqttClient.publish(topic, mqttMessage);
            log.info("📤 [MQTT下发] 成功发送到主题 {}, 报文: {}", topic, payload);
        } catch (MqttException e) {
            log.error("❌ [MQTT下发] 发送指令失败: ", e);
            throw new RuntimeException("MQTT指令下发网络异常");
        }
    }
}