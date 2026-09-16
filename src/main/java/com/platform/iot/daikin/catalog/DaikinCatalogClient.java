package com.platform.iot.daikin.catalog;

import com.platform.iot.daikin.client.DaikinEndpoint;
import com.platform.iot.daikin.client.DaikinReadonlyClient;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.model.DaikinDeviceObservation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import com.platform.iot.daikin.mapping.DaikinDevicePageDecoder;

/**
 * 连接只读 HTTP 客户端与完整目录校验。结果仍是厂家观测，不会自动创建或绑定平台设备。
 * 不提供任意端点透传，内外机清单分别读取，避免同一设备在不同清单中被重复建档。
 */
public final class DaikinCatalogClient {
    private final DaikinReadonlyClient client;
    private final DaikinCatalogReader reader;

    public DaikinCatalogClient(DaikinReadonlyClient client, DaikinCatalogReader reader) {
        this.client = Objects.requireNonNull(client);
        this.reader = Objects.requireNonNull(reader);
    }

    public List<DaikinDeviceObservation> read(String sourceId, DaikinDeviceKey.Kind kind) {
        return read(sourceId, kind, () -> { });
    }

    public List<DaikinDeviceObservation> read(String sourceId, DaikinDeviceKey.Kind kind,
                                              Runnable beforeFetch) {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(beforeFetch);
        DaikinEndpoint endpoint = kind == DaikinDeviceKey.Kind.INDOOR
                ? DaikinEndpoint.INUNITS : DaikinEndpoint.OUTUNITS;
        return reader.read(sourceId, kind,
                page -> client.read(endpoint, null, Map.of("page", Integer.toString(page))), beforeFetch);
    }

    /** 仅用于运行监测的逐页观测入口，目录同步继续使用完整read契约。 */
    public void visitPages(String sourceId, DaikinDeviceKey.Kind kind, Runnable beforeFetch,
            Consumer<DaikinDevicePageDecoder.Page> acceptedPage) {
        Objects.requireNonNull(kind);
        DaikinEndpoint endpoint = kind == DaikinDeviceKey.Kind.INDOOR ? DaikinEndpoint.INUNITS : DaikinEndpoint.OUTUNITS;
        reader.visitPages(sourceId, kind,
                page -> client.read(endpoint, null, Map.of("page", Integer.toString(page))), beforeFetch, acceptedPage);
    }
}
