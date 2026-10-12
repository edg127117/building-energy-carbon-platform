package com.platform.iot.daikin.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;

/** 读取门面继续只接受原白名单；与控制客户端共享会话不扩张此入口的能力。 */
public final class DaikinReadonlyClient {
    private final DaikinSession session;
    public DaikinReadonlyClient(DaikinClientConfiguration configuration, DaikinWireCodec codec,
                                DaikinHttpTransport transport, ObjectMapper mapper, Clock clock) {
        this(new DaikinSession(configuration, codec, transport, mapper, clock));
    }
    public DaikinReadonlyClient(DaikinSession session) { this.session = session; }
    public JsonNode read(DaikinEndpoint endpoint, String resourceId, Map<String, String> parameters) {
        return session.read(endpoint, resourceId, parameters);
    }
}
