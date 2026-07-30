package com.platform.config;

import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证普通自动化测试不会创建 MQTT 基础设施，避免测试订阅并确认真实 HVAC 报文。
 */
@SpringBootTest
@ActiveProfiles("test")
class MqttTestProfileIsolationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void testProfileDoesNotRegisterMqttClientOrConnectionRunner() {
        assertThat(applicationContext.getBeansOfType(MqttConfig.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(IMqttClient.class)).isEmpty();
    }
}
