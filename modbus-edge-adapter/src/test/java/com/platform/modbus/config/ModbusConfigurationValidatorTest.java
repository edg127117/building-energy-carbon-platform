package com.platform.modbus.config;

import com.platform.modbus.config.ModbusEdgeProperties.DataType;
import com.platform.modbus.protocol.ReadPlanBuilder;
import com.platform.modbus.support.TestDeviceFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModbusConfigurationValidatorTest {

    private final ModbusConfigurationValidator validator =
            new ModbusConfigurationValidator(new ReadPlanBuilder());

    @Test
    void acceptsCompleteReadOnlyTestConfiguration() {
        assertThatCode(() -> validator.validate(TestDeviceFactory.validProperties()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnstableBlankIdentity() {
        ModbusEdgeProperties properties = TestDeviceFactory.validProperties();
        properties.getDevices().getFirst().setIdentityValue(" ");

        assertThatThrownBy(() -> validator.validate(properties))
                .isInstanceOf(ModbusConfigurationException.class)
                .hasMessageContaining("identity-value");
    }

    @Test
    void rejectsRegisterPointDeclaredAsBoolean() {
        ModbusEdgeProperties properties = TestDeviceFactory.validProperties();
        properties.getDevices().getFirst().getPoints().getFirst().setDataType(DataType.BOOLEAN);

        assertThatThrownBy(() -> validator.validate(properties))
                .isInstanceOf(ModbusConfigurationException.class)
                .hasMessageContaining("位功能码只能使用BOOLEAN");
    }

    @Test
    void rejectsPlaintextOutsideExplicitTestMode() {
        ModbusEdgeProperties properties = TestDeviceFactory.validProperties();
        properties.getMqtt().getTls().setAllowPlaintextForTests(false);

        assertThatThrownBy(() -> validator.validate(properties))
                .isInstanceOf(ModbusConfigurationException.class)
                .hasMessageContaining("明文MQTT只允许隔离自动化测试");
    }

    @Test
    void rejectsOverlappingRegisterRangesBeforePollingStarts() {
        ModbusEdgeProperties properties = TestDeviceFactory.validProperties();
        properties.getDevices().getFirst().getPoints().getFirst().setDataType(DataType.INT32);

        assertThatThrownBy(() -> validator.validate(properties))
                .isInstanceOf(ModbusConfigurationException.class)
                .hasMessageContaining("读取地址重叠");
    }

    @Test
    void rejectsSubsecondMqttConnectionTimeout() {
        ModbusEdgeProperties properties = TestDeviceFactory.validProperties();
        properties.getMqtt().setConnectionTimeout(Duration.ofMillis(500));

        assertThatThrownBy(() -> validator.validate(properties))
                .isInstanceOf(ModbusConfigurationException.class)
                .hasMessageContaining("mqtt.connection-timeout");
    }

    @Test
    void disabledModuleNeedsNoDeviceOrCredentials() {
        assertThatCode(() -> validator.validate(new ModbusEdgeProperties()))
                .doesNotThrowAnyException();
    }
}
