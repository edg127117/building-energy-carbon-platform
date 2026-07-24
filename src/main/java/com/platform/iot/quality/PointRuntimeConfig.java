package com.platform.iot.quality;

import java.math.BigDecimal;

/** 采集与分钟聚合共用的不可变标准测点运行配置。 */
public record PointRuntimeConfig(
        String pointId,
        String pointCode,
        String pointName,
        String buildingId,
        String systemGroupId,
        String equipId,
        String equipCode,
        String familyCode,
        String componentCode,
        String suffixCode,
        String status,
        int isForCalc,
        BigDecimal valueMin,
        BigDecimal valueMax
) {
}
