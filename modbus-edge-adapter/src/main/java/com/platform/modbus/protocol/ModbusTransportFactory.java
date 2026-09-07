package com.platform.modbus.protocol;

import com.platform.modbus.config.ModbusEdgeProperties.Connection;

public interface ModbusTransportFactory {

    ModbusSession open(Connection connection);
}
