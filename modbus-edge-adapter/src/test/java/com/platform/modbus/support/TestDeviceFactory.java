package com.platform.modbus.support;

import com.platform.modbus.config.ModbusEdgeProperties;
import com.platform.modbus.config.ModbusEdgeProperties.DataType;
import com.platform.modbus.config.ModbusEdgeProperties.Device;
import com.platform.modbus.config.ModbusEdgeProperties.Point;
import com.platform.modbus.config.ModbusEdgeProperties.ReadFunction;
import com.platform.modbus.config.ModbusEdgeProperties.TransportType;

import java.util.List;

public final class TestDeviceFactory {

    private TestDeviceFactory() {
    }

    public static ModbusEdgeProperties validProperties() {
        ModbusEdgeProperties properties = new ModbusEdgeProperties();
        properties.setEnabled(true);
        properties.getMqtt().getTls().setEnabled(false);
        properties.getMqtt().getTls().setAllowPlaintextForTests(true);
        properties.setDevices(List.of(device()));
        return properties;
    }

    public static Device device() {
        Device device = new Device();
        device.setName("test-unit-1");
        device.setProfileCode("MODBUS_TEST_V1");
        device.setProfileVersion(1);
        device.setIdentityType("CONFIGURED_ID");
        device.setIdentityValue("test-device-1");
        device.getConnection().setType(TransportType.TCP);
        device.getConnection().setHost("127.0.0.1");
        device.getConnection().setPort(1502);
        device.getConnection().setUnitId(1);
        device.setPoints(List.of(
                point("TEMP", ReadFunction.HOLDING_REGISTER, 0, DataType.INT16),
                point("POWER", ReadFunction.HOLDING_REGISTER, 1, DataType.UINT16)));
        return device;
    }

    public static Point point(
            String code, ReadFunction function, int address, DataType dataType) {
        Point point = new Point();
        point.setCode(code);
        point.setFunction(function);
        point.setAddress(address);
        point.setDataType(dataType);
        point.setUnit(dataType == DataType.BOOLEAN ? "bool" : "test-unit");
        return point;
    }
}
