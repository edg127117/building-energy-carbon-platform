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
        String dataType,
        String unit,
        String status,
        int isForCalc,
        BigDecimal valueMin,
        BigDecimal valueMax
) {
    /**
     * 兼容尚未关心数据类型和单位的旧调用点；质量补全只使用完整 MySQL 快照。
     */
    public PointRuntimeConfig(
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
            BigDecimal valueMax) {
        this(pointId, pointCode, pointName, buildingId, systemGroupId, equipId,
                equipCode, familyCode, componentCode, suffixCode, null, null,
                status, isForCalc, valueMin, valueMax);
    }
}
