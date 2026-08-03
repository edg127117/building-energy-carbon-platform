package com.platform.iot.quality;

/**
 * 唯一定位外部上报点码的复合地址。
 *
 * <p>同一个 {@code sourcePointCode} 可以在不同建筑或来源协议中复用，因此不能只按
 * 点码查 MySQL 别名；三个字段共同参与缓存键，防止跨建筑、跨协议串点。</p>
 *
 * @param buildingId MQTT 载荷声明且必须与别名配置一致的建筑 ID
 * @param sourceSystem 服务端选择的可信来源命名空间，不接受设备覆盖
 * @param sourcePointCode MQTT 上报的外部点码
 */
public record PointAliasKey(
        String buildingId,
        String sourceSystem,
        String sourcePointCode
) {
}
