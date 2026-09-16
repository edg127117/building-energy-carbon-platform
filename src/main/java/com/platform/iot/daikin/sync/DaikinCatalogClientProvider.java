package com.platform.iot.daikin.sync;

import com.platform.iot.daikin.catalog.DaikinCatalogClient;

import java.util.Optional;

/**
 * 按已登记的稳定来源提供可信只读客户端。实现方负责来源到凭据、目标地址的受控映射，
 * 同步入口不接受 URL 或凭据，也不会在 wire 尚未确认时自行构造客户端。
 */
@FunctionalInterface
public interface DaikinCatalogClientProvider {
    Optional<DaikinCatalogClient> clientFor(String sourceId);
}
