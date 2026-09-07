package com.platform.modbus.protocol;

import com.platform.modbus.config.ModbusConfigurationException;
import com.platform.modbus.config.ModbusEdgeProperties.Point;
import com.platform.modbus.config.ModbusEdgeProperties.ReadFunction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 将连续地址合并成只读请求，同时保留每个测点在响应中的偏移。 */
@Component
public class ReadPlanBuilder {

    public List<ReadBlock> build(List<Point> points) {
        List<Point> sorted = points.stream()
                .sorted(Comparator.comparing(Point::getFunction)
                        .thenComparingInt(Point::getAddress))
                .toList();
        List<ReadBlock> blocks = new ArrayList<>();
        MutableBlock current = null;
        for (Point point : sorted) {
            int width = width(point);
            if (current != null && current.function == point.getFunction()
                    && point.getAddress() < current.endExclusive) {
                throw new ModbusConfigurationException(
                        "读取地址重叠: " + point.getCode());
            }
            if (current == null || current.function != point.getFunction()
                    || point.getAddress() != current.endExclusive
                    || current.quantity() + width > point.getFunction().maximumQuantity()) {
                if (current != null) {
                    blocks.add(current.freeze());
                }
                current = new MutableBlock(point.getFunction(), point.getAddress());
            }
            current.items.add(new ReadItem(point, point.getAddress() - current.start));
            current.endExclusive = point.getAddress() + width;
        }
        if (current != null) {
            blocks.add(current.freeze());
        }
        return List.copyOf(blocks);
    }

    private int width(Point point) {
        return point.getFunction().bitFunction() ? 1 : point.getDataType().registerCount();
    }

    public record ReadBlock(
            ReadFunction function,
            int startAddress,
            int quantity,
            List<ReadItem> items) {

        public ReadBlock {
            items = List.copyOf(items);
        }
    }

    public record ReadItem(Point point, int offset) {
    }

    private static final class MutableBlock {
        private final ReadFunction function;
        private final int start;
        private int endExclusive;
        private final List<ReadItem> items = new ArrayList<>();

        private MutableBlock(ReadFunction function, int start) {
            this.function = function;
            this.start = start;
            this.endExclusive = start;
        }

        private int quantity() {
            return endExclusive - start;
        }

        private ReadBlock freeze() {
            return new ReadBlock(function, start, quantity(), items);
        }
    }
}
