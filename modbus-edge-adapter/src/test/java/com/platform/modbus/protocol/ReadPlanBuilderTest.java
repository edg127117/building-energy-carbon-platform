package com.platform.modbus.protocol;

import com.platform.modbus.config.ModbusConfigurationException;
import com.platform.modbus.config.ModbusEdgeProperties.DataType;
import com.platform.modbus.config.ModbusEdgeProperties.Point;
import com.platform.modbus.config.ModbusEdgeProperties.ReadFunction;
import com.platform.modbus.support.TestDeviceFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadPlanBuilderTest {

    private final ReadPlanBuilder builder = new ReadPlanBuilder();

    @Test
    void mergesAdjacentRegistersAndKeepsOffsets() {
        Point first = TestDeviceFactory.point(
                "A", ReadFunction.HOLDING_REGISTER, 10, DataType.INT16);
        Point second = TestDeviceFactory.point(
                "B", ReadFunction.HOLDING_REGISTER, 11, DataType.FLOAT32);

        List<ReadPlanBuilder.ReadBlock> blocks = builder.build(List.of(second, first));

        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst().startAddress()).isEqualTo(10);
        assertThat(blocks.getFirst().quantity()).isEqualTo(3);
        assertThat(blocks.getFirst().items())
                .extracting(ReadPlanBuilder.ReadItem::offset)
                .containsExactly(0, 1);
    }

    @Test
    void keepsGapsAndDifferentFunctionsInSeparateRequests() {
        List<ReadPlanBuilder.ReadBlock> blocks = builder.build(List.of(
                TestDeviceFactory.point("A", ReadFunction.HOLDING_REGISTER, 0, DataType.INT16),
                TestDeviceFactory.point("B", ReadFunction.HOLDING_REGISTER, 2, DataType.INT16),
                TestDeviceFactory.point("C", ReadFunction.INPUT_REGISTER, 3, DataType.INT16)));

        assertThat(blocks).hasSize(3);
    }

    @Test
    void rejectsOverlappingRegisterRanges() {
        assertThatThrownBy(() -> builder.build(List.of(
                TestDeviceFactory.point("A", ReadFunction.HOLDING_REGISTER, 0, DataType.FLOAT32),
                TestDeviceFactory.point("B", ReadFunction.HOLDING_REGISTER, 1, DataType.INT16))))
                .isInstanceOf(ModbusConfigurationException.class)
                .hasMessageContaining("读取地址重叠");
    }
}
