package com.platform.iot.websocket;

import org.springframework.stereotype.Component;

/**
 * 将公式模块的指标消息转交给 HVAC 建筑定向会话注册表。
 *
 * <p>该适配器不处理序列化、重试或指标业务；建筑 ID 由公式结果显式传入，而不是从 JSON 反解析，
 * 从而保持数据归属边界。失败影响由上游实时发布器决定，防止公式模块直接依赖端点实现。</p>
 */
@Component
public class WebSocketRealtimeMessageGateway implements RealtimeMessageGateway {

    private final HvacRealtimeSessionRegistry registry;

    public WebSocketRealtimeMessageGateway(HvacRealtimeSessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void sendToBuilding(String buildingId, String message) {
        registry.sendToBuilding(buildingId, message);
    }
}
