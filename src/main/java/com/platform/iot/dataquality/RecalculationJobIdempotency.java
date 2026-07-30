package com.platform.iot.dataquality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.HexFormat;

/**
 * 为人工重算任务生成确定性的业务幂等键。
 *
 * <p>范围任务先规范化建筑、测点集合和原因，再序列化为字段顺序固定的 JSON。
 * SHA-256 使包含中文原因的完整审计请求仍可安全放入 MySQL 唯一键。</p>
 */
public final class RecalculationJobIdempotency {

    private static final long MINUTE_MILLIS = 60_000L;
    private static final int MAX_POINT_COUNT = 100;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_KEY_LENGTH = 160;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RecalculationJobIdempotency() {
    }

    public static String voidJob(String oldTaskId) {
        return verifyLength("VOID_RECALC:"
                + requireText(oldTaskId, "oldTaskId"));
    }

    public static String rangeJob(
            long operatorId,
            String buildingId,
            List<String> pointIds,
            long fromInclusive,
            long toExclusive,
            String reason) {
        if (operatorId <= 0L) {
            throw new IllegalArgumentException("operatorId 必须大于 0");
        }
        requireMinuteAligned(fromInclusive, "fromInclusive");
        requireMinuteAligned(toExclusive, "toExclusive");
        if (toExclusive <= fromInclusive) {
            throw new IllegalArgumentException("toExclusive 必须晚于 fromInclusive");
        }
        List<String> normalizedPointIds = normalizePointIds(pointIds);
        String normalizedReason = requireText(reason, "reason");
        if (normalizedReason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("reason 不能超过 500 个字符");
        }
        RangeRequest canonical = new RangeRequest(
                operatorId,
                requireText(buildingId, "buildingId"),
                normalizedPointIds,
                fromInclusive,
                toExclusive,
                normalizedReason);
        return "RANGE_RECALC:" + sha256(encode(canonical));
    }

    private static List<String> normalizePointIds(List<String> pointIds) {
        Objects.requireNonNull(pointIds, "pointIds 不能为空");
        if (pointIds.isEmpty()) {
            throw new IllegalArgumentException("pointIds 不能为空");
        }
        TreeSet<String> normalized = new TreeSet<>();
        pointIds.forEach(pointId ->
                normalized.add(requireText(pointId, "pointId")));
        if (normalized.size() > MAX_POINT_COUNT) {
            throw new IllegalArgumentException("pointIds 不能超过 100 个");
        }
        return List.copyOf(normalized);
    }

    private static byte[] encode(RangeRequest request) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("重算请求无法生成规范化 JSON", exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", exception);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    private static void requireMinuteAligned(long timestamp, String field) {
        if (Math.floorMod(timestamp, MINUTE_MILLIS) != 0L) {
            throw new IllegalArgumentException(field + " 必须对齐到分钟");
        }
    }

    private static String verifyLength(String key) {
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("重算任务幂等键不能超过 160 个字符");
        }
        return key;
    }

    /**
     * 字段声明顺序就是规范化 JSON 顺序，变更顺序会改变已有请求的幂等键。
     */
    private record RangeRequest(
            long operatorId,
            String buildingId,
            List<String> pointIds,
            long fromInclusive,
            long toExclusive,
            String reason) {
    }
}
