package com.platform.modbus.protocol;

import com.ghgande.j2mod.modbus.procimg.SimpleDigitalOut;
import com.ghgande.j2mod.modbus.procimg.SimpleInputRegister;
import com.ghgande.j2mod.modbus.procimg.SimpleProcessImage;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;
import com.platform.modbus.config.ModbusEdgeProperties.Connection;
import com.platform.modbus.config.ModbusEdgeProperties.ReadFunction;
import com.platform.modbus.config.ModbusEdgeProperties.TransportType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class J2ModTransportFactoryTest {

    private ModbusSlave slave;

    @AfterEach
    void closeSlave() {
        ModbusSlaveFactory.close(slave);
    }

    @Test
    void readsRegistersAndBitsFromRealTcpSimulator() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        SimpleProcessImage image = new SimpleProcessImage(1);
        image.addRegister(new SimpleRegister(123));
        image.addRegister(new SimpleRegister(456));
        image.addInputRegister(new SimpleInputRegister(789));
        image.addDigitalOut(new SimpleDigitalOut(true));
        slave = ModbusSlaveFactory.createTCPSlave(
                InetAddress.getLoopbackAddress(), port, 2, false);
        slave.addProcessImage(1, image);
        slave.open();

        Connection connection = new Connection();
        connection.setType(TransportType.TCP);
        connection.setHost("127.0.0.1");
        connection.setPort(port);
        connection.setUnitId(1);

        try (ModbusSession session = new J2ModTransportFactory().open(connection)) {
            assertThat(session.read(ReadFunction.HOLDING_REGISTER, 0, 2, 1).registers())
                    .containsExactly(123, 456);
            assertThat(session.read(ReadFunction.INPUT_REGISTER, 0, 1, 1).registers())
                    .containsExactly(789);
            assertThat(session.read(ReadFunction.COIL, 0, 1, 1).bits())
                    .startsWith(true);
            assertThatThrownBy(() -> session.read(
                    ReadFunction.HOLDING_REGISTER, 10, 1, 1))
                    .isInstanceOfSatisfying(ModbusTransportException.class,
                            failure -> assertThat(failure.failureType())
                                    .isEqualTo(ModbusTransportException.FailureType.PROTOCOL));
        }
    }

    @Test
    void classifiesUnavailableTcpEndpointAsConnectionFailure() throws Exception {
        int unusedPort;
        try (ServerSocket reservation = new ServerSocket(0)) {
            unusedPort = reservation.getLocalPort();
        }
        Connection connection = new Connection();
        connection.setType(TransportType.TCP);
        connection.setHost("127.0.0.1");
        connection.setPort(unusedPort);

        assertThatThrownBy(() -> new J2ModTransportFactory().open(connection))
                .isInstanceOfSatisfying(ModbusTransportException.class,
                        failure -> assertThat(failure.failureType())
                                .isEqualTo(ModbusTransportException.FailureType.CONNECTION));
    }
}
