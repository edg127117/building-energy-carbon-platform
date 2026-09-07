package com.platform.energy.efficiency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** 固定十进制证据序列化与稳定错误码，不把存储异常详情暴露给调用方。 */
@Component
public class EerpSupport {
    private final ObjectMapper mapper;
    public EerpSupport(ObjectMapper mapper) { this.mapper = mapper; }
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("EERp evidence serialization failed", e); }
    }
    public <T> T read(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("EERp evidence cannot be read", e); }
    }
    public static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static BusinessException error(int status, String code, String message) {
        return new BusinessException(status, "EERP_" + code, message);
    }
    public static void require(boolean condition, String code, String message) {
        if (!condition) throw error(400, code, message);
    }
    public static void text(String value, int max, String name) {
        require(value != null && !value.isBlank() && value.length() <= max, "INVALID_REQUEST", name + "无效");
    }
    public static void millis(Instant value) {
        require(value != null && value.getNano() % 1_000_000 == 0, "TIME_PRECISION", "时间必须为毫秒精度");
    }
}
