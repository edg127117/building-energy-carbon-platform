package com.platform.adapter.model;

import java.math.BigDecimal;

/** 单个标准指标及其来源字段，单位已经按协议映射完成转换。 */
public record StandardMetric(
        String code,
        BigDecimal value,
        String unit,
        String sourceField) {
}
