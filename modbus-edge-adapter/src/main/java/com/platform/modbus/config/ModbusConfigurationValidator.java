package com.platform.modbus.config;

import com.platform.modbus.config.ModbusEdgeProperties.Connection;
import com.platform.modbus.config.ModbusEdgeProperties.Device;
import com.platform.modbus.config.ModbusEdgeProperties.Point;
import com.platform.modbus.protocol.ReadPlanBuilder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/** 在启动采集前一次性拒绝不完整或存在歧义的现场配置。 */
@Component
public class ModbusConfigurationValidator {

    private final ReadPlanBuilder readPlanBuilder;

    public ModbusConfigurationValidator(ReadPlanBuilder readPlanBuilder) {
        this.readPlanBuilder = readPlanBuilder;
    }

    public void validate(ModbusEdgeProperties properties) {
        if (!properties.isEnabled()) {
            return;
        }
        require(properties.getWorkerThreads() > 0 && properties.getWorkerThreads() <= 64,
                "worker-threads必须在1到64之间");
        require(properties.getMaxAttempts() > 0 && properties.getMaxAttempts() <= 10,
                "max-attempts必须在1到10之间");
        require(nonNegative(properties.getRetryDelay()), "retry-delay不能为负数");
        validateMqtt(properties.getMqtt());

        Set<String> names = new HashSet<>();
        Set<String> identities = new HashSet<>();
        long enabledDevices = properties.getDevices().stream().filter(Device::isEnabled).count();
        require(enabledDevices > 0, "启用采集时至少需要一台启用设备");
        for (Device device : properties.getDevices()) {
            if (!device.isEnabled()) {
                continue;
            }
            requireText(device.getName(), "device.name");
            require(names.add(device.getName()), "设备名称重复: " + device.getName());
            requireText(device.getProfileCode(), "device.profile-code");
            require(device.getProfileVersion() > 0, "profile-version必须为正数");
            requireText(device.getIdentityType(), "device.identity-type");
            requireText(device.getIdentityValue(), "device.identity-value");
            String identity = device.getIdentityType() + '\u001f' + device.getIdentityValue();
            require(identities.add(identity), "设备身份重复: " + device.getName());
            require(positive(device.getPollInterval()), "poll-interval必须为正数");
            validateConnection(device.getName(), device.getConnection());
            validatePoints(device);
            readPlanBuilder.build(device.getPoints());
        }
    }

    private void validateMqtt(ModbusEdgeProperties.Mqtt mqtt) {
        require(mqtt.isEnabled(), "启用Modbus采集时必须启用MQTT发布");
        requireText(mqtt.getBrokerUrl(), "mqtt.broker-url");
        requireText(mqtt.getClientId(), "mqtt.client-id");
        requireText(mqtt.getStandardTopic(), "mqtt.standard-topic");
        requireText(mqtt.getApplicationAckTopic(), "mqtt.application-ack-topic");
        requireSecondBasedTimeout(mqtt.getConnectionTimeout(), "mqtt.connection-timeout");
        requireMillisTimeout(mqtt.getOperationTimeout(), "mqtt.operation-timeout");
        require(positive(mqtt.getAckTimeout()), "mqtt.ack-timeout必须为正数");
        if (mqtt.getTls().isEnabled()) {
            require(mqtt.getBrokerUrl().startsWith("ssl://")
                            || mqtt.getBrokerUrl().startsWith("wss://"),
                    "启用TLS时broker-url必须使用ssl://或wss://");
            requireText(mqtt.getTls().getTrustStore(), "mqtt.tls.trust-store");
            requireText(mqtt.getTls().getTrustStorePassword(),
                    "mqtt.tls.trust-store-password");
        } else {
            require(mqtt.getTls().isAllowPlaintextForTests(),
                    "明文MQTT只允许隔离自动化测试显式开启");
        }
    }

    private void validateConnection(String deviceName, Connection connection) {
        require(connection.getType() != null, "设备缺少connection.type: " + deviceName);
        require(connection.getUnitId() >= 0 && connection.getUnitId() <= 247,
                "unit-id必须在0到247之间: " + deviceName);
        requireIntMillisTimeout(connection.getTimeout(),
                "connection.timeout", deviceName);
        if (connection.getType() == ModbusEdgeProperties.TransportType.TCP) {
            requireText(connection.getHost(), "connection.host");
            require(connection.getPort() > 0 && connection.getPort() <= 65535,
                    "TCP端口必须在1到65535之间: " + deviceName);
            return;
        }
        requireText(connection.getSerialPort(), "connection.serial-port");
        require(connection.getBaudRate() > 0, "baud-rate必须为正数: " + deviceName);
        require(connection.getDataBits() >= 5 && connection.getDataBits() <= 8,
                "data-bits必须在5到8之间: " + deviceName);
        require(connection.getStopBits() == 1 || connection.getStopBits() == 2,
                "stop-bits只允许1或2: " + deviceName);
        require(connection.getParity() != null, "parity不能为空: " + deviceName);
    }

    private void validatePoints(Device device) {
        require(!device.getPoints().isEmpty(), "设备没有配置读取点: " + device.getName());
        Set<String> codes = new HashSet<>();
        for (Point point : device.getPoints()) {
            requireText(point.getCode(), "point.code");
            require(codes.add(point.getCode()), "测点编码重复: " + point.getCode());
            require(point.getFunction() != null, "测点缺少读取功能码: " + point.getCode());
            require(point.getDataType() != null, "测点缺少数据类型: " + point.getCode());
            require(point.getAddress() >= 0, "寄存器地址不能为负数: " + point.getCode());
            int width = point.getFunction().bitFunction()
                    ? 1 : point.getDataType().registerCount();
            require(point.getAddress() + width <= 65536,
                    "测点地址范围超出Modbus地址空间: " + point.getCode());
            require(point.getFunction().bitFunction()
                            == (point.getDataType() == ModbusEdgeProperties.DataType.BOOLEAN),
                    "位功能码只能使用BOOLEAN，寄存器功能码不能使用BOOLEAN: " + point.getCode());
            require(point.getByteOrder() != null, "byte-order不能为空: " + point.getCode());
            require(point.getWordOrder() != null, "word-order不能为空: " + point.getCode());
            require(point.getScale() != null, "scale不能为空: " + point.getCode());
            require(point.getOffset() != null, "offset不能为空: " + point.getCode());
            requireText(point.getUnit(), "point.unit");
        }
    }

    private boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private boolean nonNegative(Duration duration) {
        return duration != null && !duration.isNegative();
    }

    private void requireSecondBasedTimeout(Duration duration, String field) {
        require(positive(duration) && duration.toSeconds() >= 1
                        && duration.toSeconds() <= Integer.MAX_VALUE,
                field + "必须在1秒到整数秒上限之间");
    }

    private void requireMillisTimeout(Duration duration, String field) {
        require(positive(duration), field + "必须为正数");
        try {
            duration.toMillis();
        } catch (ArithmeticException exception) {
            throw new ModbusConfigurationException(field + "过大");
        }
    }

    private void requireIntMillisTimeout(
            Duration duration, String field, String deviceName) {
        requireMillisTimeout(duration, field);
        require(duration.toMillis() <= Integer.MAX_VALUE,
                field + "过大: " + deviceName);
    }

    private void requireText(String value, String field) {
        require(value != null && !value.isBlank(), field + "不能为空");
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new ModbusConfigurationException(message);
        }
    }
}
