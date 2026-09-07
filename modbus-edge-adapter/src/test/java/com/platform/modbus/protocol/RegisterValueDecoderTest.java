package com.platform.modbus.protocol;

import com.platform.modbus.config.ModbusEdgeProperties.ByteOrder;
import com.platform.modbus.config.ModbusEdgeProperties.DataType;
import com.platform.modbus.config.ModbusEdgeProperties.Point;
import com.platform.modbus.config.ModbusEdgeProperties.ReadFunction;
import com.platform.modbus.config.ModbusEdgeProperties.WordOrder;
import com.platform.modbus.support.TestDeviceFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegisterValueDecoderTest {

    private final RegisterValueDecoder decoder = new RegisterValueDecoder();

    @Test
    void appliesSignedValueScaleAndOffset() {
        Point point = TestDeviceFactory.point(
                "TEMP", ReadFunction.HOLDING_REGISTER, 0, DataType.INT16);
        point.setScale(new BigDecimal("0.1"));
        point.setOffset(new BigDecimal("2"));

        BigDecimal value = decoder.decode(
                point, new ModbusReadValues(null, new int[]{0xff9c}), 0);

        assertThat(value).isEqualByComparingTo("-8");
    }

    @Test
    void decodesConfiguredWordOrder() {
        Point point = TestDeviceFactory.point(
                "POWER", ReadFunction.INPUT_REGISTER, 0, DataType.FLOAT32);
        point.setWordOrder(WordOrder.LOW_TO_HIGH);

        BigDecimal value = decoder.decode(
                point, new ModbusReadValues(null, new int[]{0x0000, 0x4148}), 0);

        assertThat(value).isEqualByComparingTo("12.5");
    }

    @Test
    void decodesConfiguredByteOrderWithinRegister() {
        Point point = TestDeviceFactory.point(
                "VALUE", ReadFunction.HOLDING_REGISTER, 0, DataType.UINT16);
        point.setByteOrder(ByteOrder.LITTLE_ENDIAN);

        BigDecimal value = decoder.decode(
                point, new ModbusReadValues(null, new int[]{0x3412}), 0);

        assertThat(value).isEqualByComparingTo("4660");
    }

    @Test
    void convertsReadOnlyBitToNumericMetric() {
        Point point = TestDeviceFactory.point(
                "RUNNING", ReadFunction.COIL, 0, DataType.BOOLEAN);

        assertThat(decoder.decode(
                point, new ModbusReadValues(new boolean[]{true}, null), 0))
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void rejectsNonFiniteDeviceFloat() {
        Point point = TestDeviceFactory.point(
                "VALUE", ReadFunction.HOLDING_REGISTER, 0, DataType.FLOAT32);

        assertThatThrownBy(() -> decoder.decode(
                point, new ModbusReadValues(null, new int[]{0x7fc0, 0}), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("浮点值无效");
    }
}
