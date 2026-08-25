package com.platform.adapter.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.adapter.model.DeviceIdentity;
import com.platform.adapter.model.DedupMode;
import com.platform.adapter.model.IdSource;
import com.platform.adapter.model.StandardMetric;
import com.platform.adapter.model.StandardTelemetryMessage;
import com.platform.adapter.model.TimeSource;
import com.platform.adapter.profile.ProtocolFieldMapping;
import com.platform.adapter.profile.ProtocolProfile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 按 MySQL 协议模板把一条不可信 JSON 报文转换为标准多指标报文。
 *
 * <p>字段名称、嵌套路径、单位倍率和必填约束全部来自配置。适配器先验证完整包，
 * 再一次性生成结果，避免缺字段时向本地平台发布无法追溯的半包数据。</p>
 */
@Component
public class JsonTelemetryAdapter {

    private static final long MIN_MILLISECOND_TIMESTAMP = 1_000_000_000_000L;
    private static final long MAX_MILLISECOND_TIMESTAMP_EXCLUSIVE = 10_000_000_000_000L;

    private final ObjectMapper objectMapper;

    public JsonTelemetryAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public StandardTelemetryMessage adapt(
            String topic,
            byte[] payload,
            long receivedTime,
            ProtocolProfile profile,
            List<ProtocolFieldMapping> mappings) {
        validateProfile(topic, profile);
        JsonNode root = parseObject(payload);
        DeviceIdentity identity = readIdentity(root, profile);
        Long deviceTime = readOptionalInteger(root, profile.timestampPath(), "timestamp");
        Long seq = readOptionalInteger(root, profile.seqPath(), "seq");
        String sourceMessageId = readOptionalText(root, profile.messageIdPath());
        String bootId = readOptionalText(root, profile.bootIdPath());
        String batchId = readOptionalText(root, profile.batchIdPath());
        Long retransmittedAt = readOptionalInteger(
                root, profile.retransmittedAtPath(), "retransmittedAt");
        if (deviceTime != null && (deviceTime < MIN_MILLISECOND_TIMESTAMP
                || deviceTime >= MAX_MILLISECOND_TIMESTAMP_EXCLUSIVE)) {
            throw error("INVALID_TIMESTAMP", "timestamp必须是13位Unix毫秒时间戳");
        }
        if (seq != null && seq < 0) {
            throw error("INVALID_SEQUENCE", "seq不能为负数");
        }

        List<ProtocolFieldMapping> activeMappings = mappings.stream()
                .filter(ProtocolFieldMapping::enabled)
                .sorted(Comparator.comparingInt(ProtocolFieldMapping::sortOrder))
                .toList();
        validateUniqueMetricCodes(activeMappings);

        List<StandardMetric> metrics = new ArrayList<>();
        for (ProtocolFieldMapping mapping : activeMappings) {
            JsonNode valueNode = root.at(requiredPointer(mapping.sourcePath(), "sourcePath"));
            if (valueNode.isMissingNode() || valueNode.isNull()) {
                if (mapping.required()) {
                    throw error("REQUIRED_FIELD_MISSING",
                            "必填字段缺失: " + mapping.sourcePath());
                }
                continue;
            }
            if (!valueNode.isNumber()) {
                throw error("INVALID_NUMBER", "字段必须是数值: " + mapping.sourcePath());
            }
            BigDecimal rawValue = valueNode.decimalValue();
            BigDecimal scale = mapping.scale() == null ? BigDecimal.ONE : mapping.scale();
            BigDecimal offset = mapping.offset() == null ? BigDecimal.ZERO : mapping.offset();
            metrics.add(new StandardMetric(
                    requiredText(mapping.metricCode(), "metricCode"),
                    rawValue.multiply(scale).add(offset),
                    requiredText(mapping.targetUnit(), "targetUnit"),
                    mapping.sourcePath()));
        }
        if (metrics.isEmpty()) {
            throw error("NO_METRICS", "报文没有可发布的标准指标");
        }

        TimeSource timeSource = deviceTime == null
                ? TimeSource.ADAPTER_RECEIVED : TimeSource.DEVICE_REPORTED;
        MessageIdentity messageIdentity = createIdentity(
                identity, sourceMessageId, bootId, seq, deviceTime);
        return new StandardTelemetryMessage(
                "2.0",
                requiredText(profile.profileCode(), "profileCode"),
                profile.profileVersion(),
                identity,
                messageIdentity.canonicalMessageId(),
                sourceMessageId,
                bootId,
                seq,
                deviceTime,
                receivedTime,
                retransmittedAt,
                batchId,
                null,
                null,
                messageIdentity.idSource(),
                timeSource,
                messageIdentity.dedupMode(),
                defaultText(profile.maxAckMode(), "EVIDENCE_ONLY"),
                defaultText(profile.correlationPolicy(), "NONE"),
                metrics);
    }

    private MessageIdentity createIdentity(
            DeviceIdentity identity,
            String sourceMessageId,
            String bootId,
            Long seq,
            Long collectedAt) {
        String deviceScope = identity.type() + "\u001f" + identity.value() + "\u001f";
        if (sourceMessageId != null) {
            return new MessageIdentity(hash(deviceScope + "mid\u001f" + sourceMessageId),
                    IdSource.DEVICE_REPORTED, DedupMode.EXACT);
        }
        if (bootId != null && seq != null) {
            return new MessageIdentity(hash(deviceScope + "boot-seq\u001f" + bootId + "\u001f" + seq),
                    IdSource.ADAPTER_DERIVED, DedupMode.DERIVED);
        }
        if (seq != null && collectedAt != null) {
            return new MessageIdentity(hash(deviceScope + "seq-time\u001f" + seq + "\u001f" + collectedAt),
                    IdSource.ADAPTER_DERIVED, DedupMode.DERIVED);
        }
        if (collectedAt != null) {
            return new MessageIdentity(hash(deviceScope + "time\u001f" + collectedAt),
                    IdSource.ADAPTER_DERIVED, DedupMode.DERIVED);
        }
        return new MessageIdentity(UUID.randomUUID().toString(),
                IdSource.ADAPTER_GENERATED, DedupMode.NONE);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private String readOptionalText(JsonNode root, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        JsonNode node = root.at(requiredPointer(path, "optionalTextPath"));
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isValueNode()) {
            throw error("INVALID_TEXT", "关联字段必须是标量");
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private JsonNode parseObject(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw error("EMPTY_PAYLOAD", "设备报文为空");
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root == null || !root.isObject()) {
                throw error("MALFORMED_PAYLOAD", "设备报文必须是JSON对象");
            }
            return root;
        } catch (IOException exception) {
            throw new TelemetryAdaptationException(
                    "MALFORMED_JSON", "设备报文不是合法JSON", exception);
        }
    }

    private DeviceIdentity readIdentity(JsonNode root, ProtocolProfile profile) {
        String pointer = requiredPointer(profile.deviceIdentityPath(), "deviceIdentityPath");
        JsonNode node = root.at(pointer);
        if (node.isMissingNode() || node.isNull() || !node.isValueNode()) {
            throw error("DEVICE_IDENTITY_MISSING", "设备身份缺失: " + pointer);
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            throw error("DEVICE_IDENTITY_MISSING", "设备身份不能为空: " + pointer);
        }
        return new DeviceIdentity(
                requiredText(profile.deviceIdentityType(), "deviceIdentityType"),
                value);
    }

    private Long readOptionalInteger(JsonNode root, String path, String fieldName) {
        if (path == null || path.isBlank()) {
            return null;
        }
        JsonNode node = root.at(requiredPointer(path, fieldName + "Path"));
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw error("INVALID_" + fieldName.toUpperCase(),
                    fieldName + "必须是整数");
        }
        try {
            return node.decimalValue().longValueExact();
        } catch (ArithmeticException exception) {
            throw error("INVALID_" + fieldName.toUpperCase(),
                    fieldName + "必须是整数");
        }
    }

    private void validateProfile(String topic, ProtocolProfile profile) {
        if (profile == null || !profile.enabled()) {
            throw error("PROFILE_DISABLED", "协议模板不存在或未启用");
        }
        if (topic == null || !topic.equals(profile.sourceTopic())) {
            throw error("TOPIC_PROFILE_MISMATCH", "MQTT主题与协议模板不一致");
        }
    }

    private void validateUniqueMetricCodes(List<ProtocolFieldMapping> mappings) {
        Set<String> codes = new HashSet<>();
        for (ProtocolFieldMapping mapping : mappings) {
            String code = requiredText(mapping.metricCode(), "metricCode");
            if (!codes.add(code)) {
                throw error("DUPLICATE_METRIC_CODE", "标准指标重复: " + code);
            }
        }
    }

    private String requiredPointer(String value, String fieldName) {
        String path = requiredText(value, fieldName);
        if (!path.startsWith("/")) {
            throw error("INVALID_PROFILE", fieldName + "必须是JSON Pointer");
        }
        return path;
    }

    private String requiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw error("INVALID_PROFILE", fieldName + "不能为空");
        }
        return value;
    }

    private TelemetryAdaptationException error(String code, String message) {
        return new TelemetryAdaptationException(code, message);
    }

    private record MessageIdentity(
            String canonicalMessageId, IdSource idSource, DedupMode dedupMode) {
    }
}
