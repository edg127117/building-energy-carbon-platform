package com.platform.iot.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
/**
 * HVAC 实时通道的有限协议编解码器。
 *
 * <p>客户端只能发送订阅和心跳两类小报文，避免端点演变成通用业务命令入口。所有服务端信封由
 * {@link ObjectMapper} 编码，不拼接用户字段，从而避免 Token、建筑编号等输入破坏 JSON 边界。该类由
 * Spring 以无状态单例装配，确保端点和测试外的业务组件使用同一份协议约束。</p>
 */
public class HvacRealtimeProtocol {

    public static final int MAX_CLIENT_MESSAGE_CHARS = 16_384;
    public static final int CLOSE_BAD_PROTOCOL = 4400;
    public static final int CLOSE_UNAUTHORIZED = 4401;
    public static final int CLOSE_FORBIDDEN = 4403;
    public static final int CLOSE_TIMEOUT = 4408;

    private static final Set<String> PUBLIC_ERROR_CODES = Set.of(
            "BAD_PROTOCOL",
            "UNAUTHORIZED",
            "FORBIDDEN_BUILDING",
            "REALTIME_AUTH_UNAVAILABLE",
            "REALTIME_INTERNAL_ERROR");

    private final ObjectMapper objectMapper;

    public HvacRealtimeProtocol(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析首帧订阅或已认证会话的心跳，不接受任意控制、查询或指标计算命令。
     *
     * <p>长度限制先于 JSON 解析执行，协议错误统一映射为 4400，不能把解析器内部异常或原始
     * 报文回显给客户端。</p>
     */
    public ClientMessage decodeClient(String payload) {
        if (payload == null || payload.length() > MAX_CLIENT_MESSAGE_CHARS) {
            throw badProtocol();
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root == null || !root.isObject()) {
                throw badProtocol();
            }
            String type = requiredText(root, "type");
            if ("PING".equals(type)) {
                return Ping.INSTANCE;
            }
            if ("SUBSCRIBE".equals(type)) {
                return new Subscribe(
                        requiredText(root, "token"),
                        requiredText(root, "buildingId"));
            }
            throw badProtocol();
        } catch (HvacRealtimeAccessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw badProtocol();
        }
    }

    /** 创建不包含认证材料的订阅成功信封。 */
    public String subscribed(String buildingId, long serverTime) {
        return encode(Map.of(
                "type", "SUBSCRIBED",
                "buildingId", buildingId,
                "serverTime", serverTime));
    }

    /** 创建心跳响应；{@code serverTime} 供客户端判断当前连接仍由服务端处理。 */
    public String pong(long serverTime) {
        return encode(Map.of("type", "PONG", "serverTime", serverTime));
    }

    /**
     * 创建由端点已选择的稳定错误信封。
     *
     * <p>这里故意不接收异常对象，防止 JWT 解析、SQL 或 Redis 异常文本被误传给浏览器。</p>
     */
    public String error(String code, String message) {
        if (!PUBLIC_ERROR_CODES.contains(code) || isBlank(message)) {
            throw new IllegalArgumentException("Unsupported realtime public error");
        }
        return encode(Map.of("type", "ERROR", "code", code, "message", message));
    }

    private String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || isBlank(value.textValue())) {
            throw badProtocol();
        }
        return value.textValue();
    }

    private String encode(Map<String, ?> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode realtime protocol envelope", exception);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static HvacRealtimeAccessException badProtocol() {
        return new HvacRealtimeAccessException(
                "BAD_PROTOCOL", CLOSE_BAD_PROTOCOL, "实时协议格式错误");
    }

    /** 客户端协议只允许订阅和心跳两种变体。 */
    public sealed interface ClientMessage permits Subscribe, Ping {
    }

    /** 首帧携带当前内存中的 JWT 与目标建筑；端点绝不记录该记录实例。 */
    public record Subscribe(String token, String buildingId) implements ClientMessage {

        @Override
        public String toString() {
            return "Subscribe[token=<redacted>, buildingId=" + buildingId + ']';
        }
    }

    /** 认证后的连接每 20 秒发送一次，用于重新校验登录态与建筑权限。 */
    public enum Ping implements ClientMessage {
        INSTANCE
    }
}
