package com.platform.iot.websocket;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;

@Component
/**
 * 单实例 HVAC 实时会话的认证状态、建筑索引和串行发送边界。
 *
 * <p>注册表只保存已认证会话的内存路由，不承担多实例同步或历史补发。业务消息由调用方明确提供
 * {@code buildingId}，不会重新解析 JSON 推断路由，从而防止序列化字段变化导致跨建筑误发。</p>
 */
public class HvacRealtimeSessionRegistry {

    static final Duration SUBSCRIBE_TIMEOUT = Duration.ofSeconds(5);
    static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(30);

    private static final Logger log =
            LoggerFactory.getLogger(HvacRealtimeSessionRegistry.class);

    private final HvacRealtimeAccessService accessService;
    private final TaskScheduler taskScheduler;
    private final ConcurrentMap<String, SessionState> sessions =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, SessionState>>
            sessionsByBuilding = new ConcurrentHashMap<>();

    public HvacRealtimeSessionRegistry(
            HvacRealtimeAccessService accessService,
            @Qualifier("hvacRealtimeTaskScheduler") TaskScheduler taskScheduler) {
        this.accessService = accessService;
        this.taskScheduler = taskScheduler;
    }

    /**
     * 注册尚未认证的传输连接，并安排首帧订阅期限。
     *
     * <p>认证成功前不会加入建筑索引，因此公式发布器无论何时发送都不会向 pending 会话泄露指标。</p>
     */
    public void open(Session session) {
        if (session == null || isBlank(session.getId())) {
            return;
        }
        String sessionId = session.getId();
        SessionState state = new SessionState(session);
        SessionState previous = sessions.put(sessionId, state);
        if (previous != null) {
            clearState(sessionId, previous);
        }
        scheduleSubscribeTimeout(sessionId, state);
    }

    /**
     * 完成首帧认证后把连接原子地移动到唯一目标建筑。
     *
     * <p>访问服务在更新索引前校验 JWT、Redis 和建筑权限；重订阅在同一发送锁内先移除旧建筑映射，
     * 再加入新映射，避免一个 Session 同时出现在两个建筑组。</p>
     */
    public HvacRealtimeSubscription subscribe(
            Session session, String token, String buildingId) {
        SessionState state = requireCurrentState(session);
        HvacRealtimeSubscription authenticated =
                accessService.authenticate(token, buildingId);
        String sessionId = session.getId();

        synchronized (state.sendLock) {
            ensureCurrent(sessionId, state);
            removeBuildingMapping(sessionId, state, state.subscription);
            cancelSubscribeTimeout(state);
            cancelHeartbeatTimeout(state);
            state.subscription = authenticated;
            sessionsByBuilding
                    .computeIfAbsent(authenticated.buildingId(), ignored -> new ConcurrentHashMap<>())
                    .put(sessionId, state);
            scheduleHeartbeatTimeout(sessionId, state);
        }
        return authenticated;
    }

    /**
     * 复核当前会话的登录态和建筑范围，并将新的 JWT 过期时间保存到同一建筑状态。
     *
     * <p>权限拒绝会先从建筑索引移除，再由端点发送脱敏 {@code ERROR} 帧并关闭；注册表不吞掉
     * 异常，避免失去协议层错误语义，同时避免拒绝窗口内继续收到指标。心跳超时和传输发送失败
     * 仍由本类直接清理。</p>
     */
    public HvacRealtimeSubscription ping(Session session) {
        SessionState state = requireCurrentState(session);
        HvacRealtimeSubscription current = state.subscription;
        if (current == null) {
            throw new HvacRealtimeAccessException(
                    "BAD_PROTOCOL", HvacRealtimeProtocol.CLOSE_BAD_PROTOCOL,
                    "请先订阅实时建筑");
        }

        HvacRealtimeSubscription refreshed;
        try {
            refreshed = accessService.revalidate(current);
        } catch (RuntimeException exception) {
            remove(session);
            throw exception;
        }
        synchronized (state.sendLock) {
            ensureCurrent(session.getId(), state);
            if (state.subscription == current) {
                state.subscription = refreshed;
                scheduleHeartbeatTimeout(session.getId(), state);
                return refreshed;
            }
            return state.subscription;
        }
    }

    /** 通过与业务消息相同的会话锁发送协议控制帧。 */
    public void sendControl(Session session, String message) {
        SessionState state = currentState(session);
        if (state == null) {
            sendWithoutState(session, message);
            return;
        }
        try {
            send(state, message);
        } catch (IOException | RuntimeException exception) {
            log.warn("[WebSocket] 实时控制帧发送失败: sessionId={}, reason=SEND_FAILED",
                    session.getId());
            remove(session);
        }
    }

    /**
     * 向已认证且仅订阅目标建筑的当前会话尽力发送指标。
     *
     * <p>每个 Session 使用同步基础发送和专用锁，避免并发调用触发 Jakarta WebSocket 的
     * {@code TEXT_FULL_WRITING}。单连接失败只移除该连接，不能影响同建筑其他浏览器或公式提交。</p>
     */
    public void sendToBuilding(String buildingId, String message) {
        if (isBlank(buildingId) || message == null) {
            return;
        }
        ConcurrentMap<String, SessionState> buildingSessions =
                sessionsByBuilding.get(buildingId);
        if (buildingSessions == null || buildingSessions.isEmpty()) {
            return;
        }
        for (SessionState state : List.copyOf(buildingSessions.values())) {
            try {
                sendToCurrentBuilding(state, buildingId, message);
            } catch (IOException | RuntimeException exception) {
                log.warn("[WebSocket] HVAC 指标发送失败: sessionId={}, buildingId={}, reason=SEND_FAILED",
                        state.session.getId(), buildingId);
                remove(state.session);
            }
        }
    }

    /**
     * 以应用关闭码主动终止会话并清理所有索引与超时任务。
     *
     * <p>端点在认证或协议拒绝时先调用 {@link #sendControl(Session, String)}，再调用本方法，保证
     * 客户端在传输仍可写时优先收到稳定错误码。</p>
     */
    public void close(Session session, int code, String publicReason) {
        SessionState state = currentState(session);
        if (state == null) {
            closeWithoutState(session, code, publicReason);
            return;
        }
        try {
            synchronized (state.sendLock) {
                if (state.session.isOpen()) {
                    state.session.close(closeReason(code, publicReason));
                }
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("[WebSocket] HVAC 会话关闭失败: sessionId={}, closeCode={}",
                    state.session.getId(), code);
        } finally {
            remove(state.session);
        }
    }

    /**
     * 幂等移除会话。使用 Session 实例和条件 remove 双重识别，避免旧连接迟到的关闭回调删除同 ID
     * 新建连接的建筑映射。
     */
    public void remove(Session session) {
        SessionState state = currentState(session);
        if (state == null) {
            return;
        }
        String sessionId = session.getId();
        if (sessions.remove(sessionId, state)) {
            clearState(sessionId, state);
        }
    }

    private void scheduleSubscribeTimeout(String sessionId, SessionState state) {
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> expirePendingSession(sessionId, state),
                Instant.now().plus(SUBSCRIBE_TIMEOUT));
        state.subscribeTimeout = future;
        cancelIfNoLongerCurrent(sessionId, state, future);
    }

    private void scheduleHeartbeatTimeout(String sessionId, SessionState state) {
        cancelHeartbeatTimeout(state);
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> expireHeartbeat(sessionId, state),
                Instant.now().plus(HEARTBEAT_TIMEOUT));
        state.heartbeatTimeout = future;
        cancelIfNoLongerCurrent(sessionId, state, future);
    }

    private void expirePendingSession(String sessionId, SessionState state) {
        synchronized (state.sendLock) {
            if (!isCurrent(sessionId, state) || state.subscription != null) {
                return;
            }
        }
        log.info("[WebSocket] HVAC 订阅超时: sessionId={}", sessionId);
        close(state.session, HvacRealtimeProtocol.CLOSE_TIMEOUT, "订阅超时");
    }

    private void expireHeartbeat(String sessionId, SessionState state) {
        synchronized (state.sendLock) {
            if (!isCurrent(sessionId, state) || state.subscription == null) {
                return;
            }
        }
        log.info("[WebSocket] HVAC 心跳超时: sessionId={}", sessionId);
        close(state.session, HvacRealtimeProtocol.CLOSE_TIMEOUT, "心跳超时");
    }

    private void clearState(String sessionId, SessionState state) {
        synchronized (state.sendLock) {
            HvacRealtimeSubscription subscription = state.subscription;
            state.subscription = null;
            cancelSubscribeTimeout(state);
            cancelHeartbeatTimeout(state);
            removeBuildingMapping(sessionId, state, subscription);
        }
    }

    private void removeBuildingMapping(
            String sessionId,
            SessionState state,
            HvacRealtimeSubscription subscription) {
        if (subscription == null) {
            return;
        }
        ConcurrentMap<String, SessionState> buildingSessions =
                sessionsByBuilding.get(subscription.buildingId());
        if (buildingSessions == null) {
            return;
        }
        buildingSessions.remove(sessionId, state);
        if (buildingSessions.isEmpty()) {
            sessionsByBuilding.remove(subscription.buildingId(), buildingSessions);
        }
    }

    private void cancelSubscribeTimeout(SessionState state) {
        ScheduledFuture<?> future = state.subscribeTimeout;
        state.subscribeTimeout = null;
        if (future != null) {
            future.cancel(false);
        }
    }

    private void cancelHeartbeatTimeout(SessionState state) {
        ScheduledFuture<?> future = state.heartbeatTimeout;
        state.heartbeatTimeout = null;
        if (future != null) {
            future.cancel(false);
        }
    }

    private void cancelIfNoLongerCurrent(
            String sessionId, SessionState state, ScheduledFuture<?> future) {
        if (!isCurrent(sessionId, state) && future != null) {
            future.cancel(false);
        }
    }

    private void send(SessionState state, String message) throws IOException {
        synchronized (state.sendLock) {
            if (!state.session.isOpen()) {
                throw new IOException("session closed");
            }
            state.session.getBasicRemote().sendText(message);
        }
    }

    /**
     * 在发送锁内再次确认会话仍订阅目标建筑。
     *
     * <p>建筑集合快照与重订阅可以并发发生；只在遍历快照时判断会让会话先离开 A 建筑、再收到
     * A 的迟到消息。锁内复核使重订阅完成后的连接不能接收旧建筑指标。</p>
     */
    private void sendToCurrentBuilding(
            SessionState state, String buildingId, String message) throws IOException {
        synchronized (state.sendLock) {
            HvacRealtimeSubscription subscription = state.subscription;
            if (!isCurrent(state.session.getId(), state)
                    || subscription == null
                    || !buildingId.equals(subscription.buildingId())) {
                return;
            }
            if (!state.session.isOpen()) {
                throw new IOException("session closed");
            }
            state.session.getBasicRemote().sendText(message);
        }
    }

    private void sendWithoutState(Session session, String message) {
        if (session == null || message == null) {
            return;
        }
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.getBasicRemote().sendText(message);
                }
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("[WebSocket] 无状态控制帧发送失败: reason=SEND_FAILED");
        }
    }

    private void closeWithoutState(Session session, int code, String publicReason) {
        if (session == null) {
            return;
        }
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.close(closeReason(code, publicReason));
                }
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("[WebSocket] 无状态会话关闭失败: closeCode={}", code);
        }
    }

    private SessionState requireCurrentState(Session session) {
        SessionState state = currentState(session);
        if (state == null) {
            throw new HvacRealtimeAccessException(
                    "REALTIME_INTERNAL_ERROR", 1011, "实时会话不可用");
        }
        return state;
    }

    private void ensureCurrent(String sessionId, SessionState state) {
        if (!isCurrent(sessionId, state)) {
            throw new HvacRealtimeAccessException(
                    "REALTIME_INTERNAL_ERROR", 1011, "实时会话不可用");
        }
    }

    private SessionState currentState(Session session) {
        if (session == null || isBlank(session.getId())) {
            return null;
        }
        SessionState state = sessions.get(session.getId());
        return state != null && state.session == session ? state : null;
    }

    private boolean isCurrent(String sessionId, SessionState state) {
        return sessions.get(sessionId) == state;
    }

    private CloseReason closeReason(int code, String publicReason) {
        CloseReason.CloseCode closeCode = () -> code;
        return new CloseReason(closeCode, publicReason);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class SessionState {
        private final Session session;
        private final Object sendLock = new Object();
        private volatile HvacRealtimeSubscription subscription;
        private volatile ScheduledFuture<?> subscribeTimeout;
        private volatile ScheduledFuture<?> heartbeatTimeout;

        private SessionState(Session session) {
            this.session = session;
        }
    }
}
