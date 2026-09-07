package com.platform.modbus.protocol;

import com.platform.modbus.config.ModbusEdgeProperties.ByteOrder;
import com.platform.modbus.config.ModbusEdgeProperties.DataType;
import com.platform.modbus.config.ModbusEdgeProperties.Point;
import com.platform.modbus.config.ModbusEdgeProperties.WordOrder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.ByteBuffer;

/**
 * 按点表声明解释16位寄存器，不猜测厂家字节序、字序、倍率或单位。
 */
@Component
public class RegisterValueDecoder {

    public BigDecimal decode(Point point, ModbusReadValues values, int offset) {
        BigDecimal raw = point.getFunction().bitFunction()
                ? decodeBit(point, values, offset)
                : decodeRegisters(point, values, offset);
        return raw.multiply(point.getScale()).add(point.getOffset());
    }

    private BigDecimal decodeBit(Point point, ModbusReadValues values, int offset) {
        boolean[] bits = values.bits();
        if (bits == null || offset < 0 || offset >= bits.length) {
            throw new IllegalArgumentException("位响应长度不足: " + point.getCode());
        }
        return bits[offset] ? BigDecimal.ONE : BigDecimal.ZERO;
    }

    private BigDecimal decodeRegisters(Point point, ModbusReadValues values, int offset) {
        int[] registers = values.registers();
        int count = point.getDataType().registerCount();
        if (registers == null || offset < 0 || offset + count > registers.length) {
            throw new IllegalArgumentException("寄存器响应长度不足: " + point.getCode());
        }
        byte[] bytes = new byte[count * 2];
        for (int index = 0; index < count; index++) {
            int source = point.getWordOrder() == WordOrder.HIGH_TO_LOW
                    ? offset + index : offset + count - 1 - index;
            int word = registers[source] & 0xffff;
            if (point.getByteOrder() == ByteOrder.BIG_ENDIAN) {
                bytes[index * 2] = (byte) (word >>> 8);
                bytes[index * 2 + 1] = (byte) word;
            } else {
                bytes[index * 2] = (byte) word;
                bytes[index * 2 + 1] = (byte) (word >>> 8);
            }
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        DataType type = point.getDataType();
        return switch (type) {
            case INT16 -> BigDecimal.valueOf(buffer.getShort());
            case UINT16 -> BigDecimal.valueOf(Short.toUnsignedInt(buffer.getShort()));
            case INT32 -> BigDecimal.valueOf(buffer.getInt());
            case UINT32 -> BigDecimal.valueOf(Integer.toUnsignedLong(buffer.getInt()));
            case FLOAT32 -> finite(buffer.getFloat(), point);
            case FLOAT64 -> finite(buffer.getDouble(), point);
            case BOOLEAN -> throw new IllegalArgumentException(
                    "BOOLEAN不能从寄存器响应解析: " + point.getCode());
        };
    }

    private BigDecimal finite(double value, Point point) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("浮点值无效: " + point.getCode());
        }
        return BigDecimal.valueOf(value);
    }
}
