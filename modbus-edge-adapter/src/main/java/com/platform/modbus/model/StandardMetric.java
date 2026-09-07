package com.platform.modbus.model;

import java.math.BigDecimal;

public record StandardMetric(
        String code,
        BigDecimal value,
        String unit,
        String sourceField) {
}
