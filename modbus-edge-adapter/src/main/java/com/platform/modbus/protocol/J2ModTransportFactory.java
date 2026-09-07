package com.platform.modbus.protocol;

import com.ghgande.j2mod.modbus.Modbus;
import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.ModbusIOException;
import com.ghgande.j2mod.modbus.ModbusSlaveException;
import com.ghgande.j2mod.modbus.facade.AbstractModbusMaster;
import com.ghgande.j2mod.modbus.facade.ModbusSerialMaster;
import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster;
import com.ghgande.j2mod.modbus.procimg.InputRegister;
import com.ghgande.j2mod.modbus.util.BitVector;
import com.ghgande.j2mod.modbus.util.SerialParameters;
import com.platform.modbus.config.ModbusEdgeProperties.Connection;
import com.platform.modbus.config.ModbusEdgeProperties.ReadFunction;
import com.platform.modbus.config.ModbusEdgeProperties.TransportType;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.util.Locale;

/** 为每次采样创建独立主站会话，失败后由上层有限重试并重新建链。 */
@Component
public class J2ModTransportFactory implements ModbusTransportFactory {

    @Override
    public ModbusSession open(Connection connection) {
        try {
            AbstractModbusMaster master = connection.getType() == TransportType.TCP
                    ? tcpMaster(connection) : serialMaster(connection);
            master.setRetries(0);
            master.connect();
            return new J2ModSession(master);
        } catch (Exception exception) {
            throw failure("Modbus连接失败", exception);
        }
    }

    private AbstractModbusMaster tcpMaster(Connection connection) {
        return new ModbusTCPMaster(
                connection.getHost(),
                connection.getPort(),
                Math.toIntExact(connection.getTimeout().toMillis()),
                false);
    }

    private AbstractModbusMaster serialMaster(Connection connection) {
        SerialParameters serial = new SerialParameters();
        serial.setPortName(connection.getSerialPort());
        serial.setBaudRate(connection.getBaudRate());
        serial.setDatabits(connection.getDataBits());
        serial.setStopbits(connection.getStopBits());
        serial.setParity(connection.getParity().name().toLowerCase(Locale.ROOT));
        serial.setEncoding(Modbus.SERIAL_ENCODING_RTU);
        serial.setEcho(false);
        return new ModbusSerialMaster(
                serial,
                Math.toIntExact(connection.getTimeout().toMillis()),
                0);
    }

    private static final class J2ModSession implements ModbusSession {
        private final AbstractModbusMaster master;

        private J2ModSession(AbstractModbusMaster master) {
            this.master = master;
        }

        @Override
        public ModbusReadValues read(
                ReadFunction function, int address, int quantity, int unitId) {
            try {
                return switch (function) {
                    case COIL -> bits(master.readCoils(unitId, address, quantity));
                    case DISCRETE_INPUT -> bits(
                            master.readInputDiscretes(unitId, address, quantity));
                    case HOLDING_REGISTER -> registers(
                            master.readMultipleRegisters(unitId, address, quantity));
                    case INPUT_REGISTER -> registers(
                            master.readInputRegisters(unitId, address, quantity));
                };
            } catch (ModbusException exception) {
                throw failure("Modbus读取失败", exception);
            }
        }

        private ModbusReadValues bits(BitVector vector) {
            boolean[] values = new boolean[vector.size()];
            for (int index = 0; index < values.length; index++) {
                values[index] = vector.getBit(index);
            }
            return new ModbusReadValues(values, null);
        }

        private ModbusReadValues registers(InputRegister[] response) {
            int[] values = new int[response.length];
            for (int index = 0; index < response.length; index++) {
                values[index] = response[index].toUnsignedShort();
            }
            return new ModbusReadValues(null, values);
        }

        @Override
        public void close() {
            master.disconnect();
        }
    }

    private static ModbusTransportException failure(String message, Exception exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return new ModbusTransportException(
                        ModbusTransportException.FailureType.TIMEOUT, message, exception);
            }
            current = current.getCause();
        }
        ModbusTransportException.FailureType type = exception instanceof ModbusSlaveException
                ? ModbusTransportException.FailureType.PROTOCOL
                : exception instanceof ModbusIOException
                        ? ModbusTransportException.FailureType.CONNECTION
                        : exception instanceof ModbusException
                                ? ModbusTransportException.FailureType.PROTOCOL
                                : ModbusTransportException.FailureType.CONNECTION;
        return new ModbusTransportException(type, message, exception);
    }
}
