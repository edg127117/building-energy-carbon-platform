package com.platform.adapter.profile;

import java.math.BigDecimal;

/** 一个原始 JSON 数值字段到平台标准指标的配置映射。 */
public record ProtocolFieldMapping(
        String mappingId,
        String profileId,
        String sourcePath,
        String metricCode,
        String valueType,
        String sourceUnit,
        String targetUnit,
        BigDecimal scale,
        BigDecimal offset,
        boolean required,
        boolean enabled,
        int sortOrder) {
}
