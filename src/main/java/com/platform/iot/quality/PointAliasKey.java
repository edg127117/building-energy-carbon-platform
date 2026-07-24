package com.platform.iot.quality;

/** 建筑和可信来源范围内的外部测点地址。 */
public record PointAliasKey(
        String buildingId,
        String sourceSystem,
        String sourcePointCode
) {
}
