package com.platform.iot.ingest.standard;

import java.math.BigDecimal;

/** 云端适配器完成单位转换后的单个标准指标。 */
public record StandardMetric(
        String code,
        BigDecimal value,
        String unit,
        String sourceField
) {
}
