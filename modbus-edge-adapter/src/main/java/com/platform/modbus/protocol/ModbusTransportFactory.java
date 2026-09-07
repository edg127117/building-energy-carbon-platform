package com.platform.modbus.protocol;

import com.platform.modbus.config.ModbusEdgeProperties.Connection;

/** 根据已校验的 TCP 或 RTU 参数创建独立 Modbus 会话。 */
public interface ModbusTransportFactory {

    ModbusSession open(Connection connection);
}
