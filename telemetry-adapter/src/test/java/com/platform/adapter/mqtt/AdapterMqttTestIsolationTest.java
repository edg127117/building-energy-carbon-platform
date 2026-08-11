package com.platform.adapter.mqtt;

import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AdapterMqttTestIsolationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void testProfileDoesNotCreateRealMqttClient() {
        assertThat(context.getBeansOfType(IMqttClient.class)).isEmpty();
        assertThat(context.getBeansOfType(TelemetryAdapterMqttBridge.class)).isEmpty();
    }
}
