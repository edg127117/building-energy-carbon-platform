package com.platform.modbus.protocol;

import com.platform.modbus.config.ModbusEdgeProperties.ReadFunction;

public interface ModbusSession extends AutoCloseable {

    ModbusReadValues read(ReadFunction function, int address, int quantity, int unitId);

    @Override
    void close();
}
