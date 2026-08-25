package com.platform.iot.deviceparameter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.UUID;

/** 设备参数治理共享的标识、哈希、时间和有界 JSON 工具。 */
public final class DeviceParameterSupport {
    public static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    private DeviceParameterSupport() {
    }

    public static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public static String json(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw DeviceParameterErrors.error(500, DeviceParameterErrors.VALIDATION_FAILED,
                    "设备参数审核快照无法生成");
        }
    }

    public static String requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw invalid(field + " 不能为空且长度不能超过 " + maxLength);
        }
        return value.trim();
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(PROJECT_ZONE);
    }

    public static BusinessException invalid(String message) {
        return DeviceParameterErrors.error(400, DeviceParameterErrors.VALIDATION_FAILED, message);
    }
}
