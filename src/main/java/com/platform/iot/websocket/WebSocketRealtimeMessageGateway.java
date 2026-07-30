package com.platform.iot.websocket;

import org.springframework.stereotype.Component;

/**
 * 将公式模块的指标消息转交给 HVAC WebSocket 端点。
 *
 * <p>该适配器不处理序列化、重试或指标业务；失败影响由上游实时发布器决定，
 * 防止公式模块直接依赖静态 WebSocket 实现。</p>
 */
@Component
public class WebSocketRealtimeMessageGateway implements RealtimeMessageGateway {

    @Override
    public void broadcast(String message) {
        WebSocketServer.broadcastMessage(message);
    }
}
