package com.platform.modbus.model;

import java.math.BigDecimal;

/** 表示一个按点表解码后的标准测点值，不携带平台侧建筑或设备归属。 */
public record StandardMetric(
        String code,
        BigDecimal value,
        String unit,
        String sourceField) {
}
