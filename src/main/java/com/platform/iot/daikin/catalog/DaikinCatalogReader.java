package com.platform.iot.daikin.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.iot.daikin.mapping.DaikinDevicePageDecoder;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.model.DaikinDeviceObservation;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * 有界读取一个来源的一类设备目录。分页完成前不向调用方交付部分清单，也不写入设备目录。
 * 分页数量变化、重复身份或计数不符拒绝该轮，防止用不完整清单驱动设备删除或归属变更。
 */
public final class DaikinCatalogReader {
    private final DaikinDevicePageDecoder decoder;
    private final Clock clock;
    private final int maxPages;
    private final int maxDevices;

    public DaikinCatalogReader(DaikinDevicePageDecoder decoder, Clock clock, int maxPages, int maxDevices) {
        this.decoder = Objects.requireNonNull(decoder);
        this.clock = Objects.requireNonNull(clock);
        if (maxPages < 1 || maxPages > 1000 || maxDevices < 1 || maxDevices > 100_000) {
            throw new IllegalArgumentException("目录扫描上限无效");
        }
        this.maxPages = maxPages;
        this.maxDevices = maxDevices;
    }

    public List<DaikinDeviceObservation> read(String sourceId, DaikinDeviceKey.Kind kind,
                                              IntFunction<JsonNode> fetchPage) {
        return read(sourceId, kind, fetchPage, () -> { });
    }

    /** 每页请求前允许调用方核验/续期任务租约；回调失败立即终止，不请求该页或交付残页。 */
    public List<DaikinDeviceObservation> read(String sourceId, DaikinDeviceKey.Kind kind,
                                              IntFunction<JsonNode> fetchPage, Runnable beforeFetch) {
        Objects.requireNonNull(fetchPage);
        Objects.requireNonNull(beforeFetch);
        // 本地参数错误不得先触发外部请求；目录只允许明确的内机或外机类型。
        DaikinDeviceKey.requireIdentity(sourceId);
        Objects.requireNonNull(kind);
        Map<DaikinDeviceKey, DaikinDeviceObservation> devices = new LinkedHashMap<>();
        beforeFetch.run();
        DaikinDevicePageDecoder.Page first = decoder.decode(sourceId, kind, fetchPage.apply(1), clock.instant());
        if (first.currentPage() != 1 || first.totalPages() > maxPages || first.totalCount() > maxDevices) {
            throw invalid();
        }
        add(devices, first);
        for (int page = 2; page <= first.totalPages(); page++) {
            beforeFetch.run();
            DaikinDevicePageDecoder.Page next = decoder.decode(sourceId, kind, fetchPage.apply(page), clock.instant());
            if (next.currentPage() != page || next.totalCount() != first.totalCount()
                    || next.totalPages() != first.totalPages()) throw invalid();
            add(devices, next);
        }
        if (devices.size() != first.totalCount()) throw invalid();
        return List.copyOf(devices.values());
    }

    private void add(Map<DaikinDeviceKey, DaikinDeviceObservation> devices, DaikinDevicePageDecoder.Page page) {
        for (DaikinDeviceObservation item : page.devices()) {
            if (devices.size() >= maxDevices || devices.putIfAbsent(item.key(), item) != null) throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("DAIKIN_CATALOG_INCOMPLETE_OR_UNSTABLE");
    }
}
