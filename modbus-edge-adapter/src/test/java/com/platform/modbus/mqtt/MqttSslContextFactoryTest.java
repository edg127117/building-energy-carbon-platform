package com.platform.modbus.mqtt;

import com.platform.modbus.config.ModbusEdgeProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MqttSslContextFactoryTest {

    @Test
    void rejectsMissingTrustStoreWithoutLeakingPassword() {
        ModbusEdgeProperties.Tls tls = new ModbusEdgeProperties.Tls();
        tls.setTrustStore("missing-test-truststore.p12");
        tls.setTrustStorePassword("test-secret");

        assertThatThrownBy(() -> new MqttSslContextFactory().create(tls))
                .isInstanceOf(TelemetryPublishException.class)
                .hasMessage("MQTT TLS材料加载失败")
                .hasMessageNotContaining("test-secret");
    }
}
