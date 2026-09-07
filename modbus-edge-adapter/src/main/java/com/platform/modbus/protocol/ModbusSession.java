package com.platform.modbus.protocol;

import com.platform.modbus.config.ModbusEdgeProperties.ReadFunction;

/** 表示一次已建立的 Modbus 主站会话，负责只读请求并由调用方显式关闭。 */
public interface ModbusSession extends AutoCloseable {

    ModbusReadValues read(ReadFunction function, int address, int quantity, int unitId);

    @Override
    void close();
}
