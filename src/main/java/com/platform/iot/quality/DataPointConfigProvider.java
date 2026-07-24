package com.platform.iot.quality;

import java.util.Collection;
import java.util.Optional;

/**
 * 测点配置读取边界。
 *
 * <p>校验器只依赖该接口，不关心配置来自 MySQL 还是缓存，便于独立测试。</p>
 */
public interface DataPointConfigProvider {
    Optional<PointRuntimeConfig> find(PointAliasKey aliasKey);

    default Optional<PointRuntimeConfig> findByPointId(String pointId) {
        return Optional.empty();
    }

    /**
     * 返回当前有效缓存中的全部测点，供分钟聚合逐点处理。
     */
    default Collection<PointRuntimeConfig> findAll() {
        return java.util.List.of();
    }
}
