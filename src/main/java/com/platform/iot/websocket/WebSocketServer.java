package com.platform.iot.websocket;

import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * HVAC 指标实时广播端点。
 *
 * <p>该端点只承载公式模块生成的 {@code HVAC_INDICATOR} 消息。当前任务只清理
 * 业务边界；JWT 握手、建筑订阅和广播隔离由后续正式实时功能单独实现。</p>
 */
@ServerEndpoint("/ws/hvac")
@Component
public class WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketServer.class);

    // 线程安全集合，用于存放所有当前在线的浏览器客户端 Session
    private static final CopyOnWriteArraySet<Session> sessionPool = new CopyOnWriteArraySet<>();
    // 记录已正常关闭/异常的 SessionId，防止 remove 后残余线程往已关闭连接写数据
    private static final Set<String> closedSessionIds = ConcurrentHashMap.newKeySet();

    /**
     * 当 HVAC 实时客户端建立连接时触发。
     */
    @OnOpen
    public void onOpen(Session session) {
        closedSessionIds.remove(session.getId()); // 重连时清除关闭标记
        sessionPool.add(session);
        log.info("[WebSocket] HVAC 实时客户端接入: sessionId={}, online={}",
                session.getId(), sessionPool.size());
    }

    /**
     * 当 HVAC 实时客户端关闭或刷新时触发。
     */
    @OnClose
    public void onClose(Session session) {
        closedSessionIds.add(session.getId()); // 标记已关闭，防止 broadcastMessage 再往里写
        sessionPool.remove(session);
        log.info("[WebSocket] HVAC 实时客户端断开: sessionId={}, online={}",
                session.getId(), sessionPool.size());
    }

    /**
     * 发生网络异常时触发
     */
    @OnError
    public void onError(Session session, Throwable error) {
        log.error("[WebSocket] HVAC 实时连接异常", error);
        // 异常后也做清理，避免脏 Session 留在池子里继续报错
        if (session != null) {
            closedSessionIds.add(session.getId());
            sessionPool.remove(session);
        }
    }

    /**
     * 向所有在线 HVAC 实时客户端广播指标消息。
     *
     * 关键设计：synchronized + getBasicRemote() 必须配对使用。
     * - getBasicRemote().sendText() 是同步阻塞的，消息真正发完才返回
     * - synchronized(session) 保证同一时刻只有一个线程拿到锁
     * - 线程 A 拿到锁 → 阻塞发送 → 发完释放锁 → 线程 B 拿到锁
     * 这样才是真正的串行化，彻底杜绝 TEXT_FULL_WRITING。
     * 不能使用 getAsyncRemote()：它只是提交异步任务后立即返回、释放锁，
     * 锁形同虚设，Tomcat 后台仍在并发写，异常照抛不误。
     */
    public static void broadcastMessage(String message) {
        for (Session session : sessionPool) {
            // 已标记关闭的 Session 跳过，避免往已关闭连接写数据
            if (closedSessionIds.contains(session.getId())) continue;
            try {
                if (session.isOpen()) {
                    // 同步锁 + 同步发送 = 真正串行，彻底消除并发写冲突
                    synchronized (session) {
                        session.getBasicRemote().sendText(message);
                    }
                }
            } catch (Exception e) {
                log.error("[WebSocket] HVAC 指标消息推送失败", e);
                // 发送失败说明连接已坏，做清理
                closedSessionIds.add(session.getId());
                sessionPool.remove(session);
            }
        }
    }
}
