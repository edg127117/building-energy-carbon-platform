package com.platform.modbus;

import com.platform.modbus.mqtt.MqttTelemetryPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "modbus-edge.enabled=false")
class ModbusEdgeApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void disabledByDefaultDoesNotCreateExternalMqttPublisher() {
        assertThat(context.getBeansOfType(MqttTelemetryPublisher.class)).isEmpty();
    }
}
