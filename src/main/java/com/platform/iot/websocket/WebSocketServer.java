package com.platform.iot.websocket;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * HVAC WebSocket 的传输与有限协议入口。
 *
 * <p>HTTP 握手可以匿名完成，但业务数据权限只在首帧 {@code SUBSCRIBE} 通过 JWT、Redis 登录态和
 * 建筑范围校验后建立。端点不访问 Mapper、TDengine 或公式服务；它只把协议动作委托给会话注册表，
 * 因而 WebSocket 始终是指标的最佳努力通知，而不是完整状态的权威数据源。</p>
 */
@ServerEndpoint(
        value = "/ws/hvac",
        configurator = HvacRealtimeEndpointConfigurator.class)
@Component
public class WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketServer.class);

    private final HvacRealtimeProtocol protocol;
    private final HvacRealtimeSessionRegistry registry;

    public WebSocketServer(
            HvacRealtimeProtocol protocol,
            HvacRealtimeSessionRegistry registry) {
        this.protocol = protocol;
        this.registry = registry;
    }

    /** 建立空会话；认证成功前注册表不会让它接收任何建筑指标。 */
    @OnOpen
    public void onOpen(Session session) {
        registry.open(session);
        log.info("[WebSocket] HVAC 实时连接已建立: sessionId={}", sessionId(session));
    }

    /**
     * 分派订阅和心跳协议，并在拒绝时先发送脱敏错误帧再使用应用关闭码终止连接。
     *
     * <p>原始帧可能含 JWT，因此日志只记录会话 ID 和稳定错误码，不记录 payload、异常详情或查询串。</p>
     */
    @OnMessage
    public void onMessage(String payload, Session session) {
        try {
            HvacRealtimeProtocol.ClientMessage message = protocol.decodeClient(payload);
            if (message instanceof HvacRealtimeProtocol.Subscribe subscribe) {
                HvacRealtimeSubscription subscription =
                        registry.subscribe(session, subscribe.token(), subscribe.buildingId());
                registry.sendControl(session, protocol.subscribed(
                        subscription.buildingId(), System.currentTimeMillis()));
                return;
            }
            if (message instanceof HvacRealtimeProtocol.Ping) {
                registry.ping(session);
                registry.sendControl(session, protocol.pong(System.currentTimeMillis()));
            }
        } catch (HvacRealtimeAccessException exception) {
            reject(session, exception);
        } catch (RuntimeException exception) {
            reject(session, new HvacRealtimeAccessException(
                    "REALTIME_INTERNAL_ERROR", 1011, "实时服务暂不可用"));
        }
    }

    /** 正常关闭只清理内存索引，不把浏览器主动离开误报为服务端应用错误。 */
    @OnClose
    public void onClose(Session session) {
        registry.remove(session);
        log.info("[WebSocket] HVAC 实时连接已关闭: sessionId={}", sessionId(session));
    }

    /**
     * 容器报告传输异常时幂等清理会话。
     *
     * <p>异常可能携带底层网络或输入细节，日志不输出 Throwable，避免诊断链意外记录 Token。</p>
     */
    @OnError
    public void onError(Session session, Throwable error) {
        registry.remove(session);
        log.warn("[WebSocket] HVAC 实时连接异常: sessionId={}, reason=TRANSPORT_ERROR",
                sessionId(session));
    }

    private void reject(Session session, HvacRealtimeAccessException exception) {
        log.warn("[WebSocket] HVAC 实时协议被拒绝: sessionId={}, errorCode={}, closeCode={}",
                sessionId(session), exception.errorCode(), exception.closeCode());
        try {
            registry.sendControl(session,
                    protocol.error(exception.errorCode(), exception.publicMessage()));
        } finally {
            registry.close(session, exception.closeCode(), exception.publicMessage());
        }
    }

    private String sessionId(Session session) {
        return session == null ? "<none>" : session.getId();
    }
}
