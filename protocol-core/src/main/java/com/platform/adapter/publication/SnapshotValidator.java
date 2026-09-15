package com.platform.adapter.publication;

import com.platform.adapter.profile.ProtocolFieldMapping;
import com.platform.adapter.profile.ProtocolProfile;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Entry;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Snapshot;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/** 对完整发布集合执行平台与适配器一致的静态能力校验。 */
public final class SnapshotValidator {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final Set<String> OUTPUT_VERSIONS = Set.of("V1", "V2");
    private static final Set<String> VALUE_TYPES = Set.of("DECIMAL");
    private static final Set<String> ACK_MODES = Set.of(
            "DEVICE_DIRECT", "ADAPTER_PROXY", "EVIDENCE_ONLY");
    private static final Set<String> CORRELATION_POLICIES = Set.of(
            "SOURCE_MESSAGE_ID", "BOOT_ID_AND_SEQ", "SEQ_AND_COLLECTED_AT",
            "UNIQUE_COLLECTED_AT", "NONE");

    private SnapshotValidator() {
    }

    public static void validate(Snapshot snapshot, String configuredOutputVersion) {
        if (snapshot == null) {
            fail("SNAPSHOT_MISSING", "快照不能为空");
        }
        if (snapshot.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            fail("UNSUPPORTED_SCHEMA_VERSION", "不支持的快照结构版本");
        }
        String outputVersion = normalized(snapshot.outputVersion(), "outputVersion");
        String configured = normalized(configuredOutputVersion, "configuredOutputVersion");
        if (!OUTPUT_VERSIONS.contains(outputVersion) || !OUTPUT_VERSIONS.contains(configured)) {
            fail("UNSUPPORTED_OUTPUT_VERSION", "输出版本仅支持V1或V2");
        }
        if (!configured.equals(outputVersion)) {
            fail("OUTPUT_VERSION_MISMATCH", "快照输出版本与适配器运行配置不一致");
        }
        List<Entry> entries = snapshot.profiles();
        if (entries == null || entries.isEmpty()) {
            fail("EMPTY_PROFILE_SET", "完整快照至少包含一个启用协议模板");
        }

        Set<String> profileIds = new HashSet<>();
        Set<String> profileKeys = new HashSet<>();
        Set<String> mappingIds = new HashSet<>();
        Set<String> selectors = new HashSet<>();
        Map<String, String> versionPathsByTopic = new HashMap<>();
        for (Entry entry : entries) {
            validateEntry(entry, outputVersion, profileIds, profileKeys, mappingIds,
                    selectors, versionPathsByTopic);
        }
    }

    private static void validateEntry(
            Entry entry,
            String outputVersion,
            Set<String> profileIds,
            Set<String> profileKeys,
            Set<String> mappingIds,
            Set<String> selectors,
            Map<String, String> versionPathsByTopic) {
        if (entry == null || entry.profile() == null) {
            fail("PROFILE_MISSING", "协议模板不能为空");
        }
        ProtocolProfile profile = entry.profile();
        String profileId = required(profile.profileId(), "profileId");
        String profileCode = required(profile.profileCode(), "profileCode");
        String topic = required(profile.sourceTopic(), "sourceTopic");
        if (topic.indexOf('+') >= 0 || topic.indexOf('#') >= 0) {
            fail("WILDCARD_SOURCE_TOPIC", "协议sourceTopic必须是精确主题，不能包含通配符");
        }
        if (!profile.enabled()) {
            fail("DISABLED_PROFILE", "完整快照不得包含未启用协议模板");
        }
        if (profile.profileVersion() <= 0) {
            fail("INVALID_PROFILE_VERSION", "profileVersion必须为正整数");
        }
        if (!profileIds.add(profileId)) {
            fail("DUPLICATE_PROFILE_ID", "profileId重复");
        }
        if (!profileKeys.add(profileCode + '\u001f' + profile.profileVersion())) {
            fail("DUPLICATE_PROFILE_VERSION", "profileCode与profileVersion重复");
        }
        validatePointer(profile.deviceIdentityPath(), "deviceIdentityPath", false);
        required(profile.deviceIdentityType(), "deviceIdentityType");
        validatePointer(profile.protocolVersionPath(), "protocolVersionPath", true);
        validatePointer(profile.timestampPath(), "timestampPath", true);
        validatePointer(profile.seqPath(), "seqPath", true);
        validatePointer(profile.messageIdPath(), "messageIdPath", true);
        validatePointer(profile.bootIdPath(), "bootIdPath", true);
        validatePointer(profile.batchIdPath(), "batchIdPath", true);
        validatePointer(profile.retransmittedAtPath(), "retransmittedAtPath", true);

        String expectedVersion = trimToNull(profile.expectedProtocolVersion());
        String versionPath = trimToNull(profile.protocolVersionPath());
        if ((expectedVersion == null) != (versionPath == null)) {
            fail("INCOMPLETE_VERSION_SELECTOR", "协议版本路径与期望值必须同时配置或同时为空");
        }
        String priorPath = versionPathsByTopic.putIfAbsent(
                topic, versionPath == null ? "" : versionPath);
        if (priorPath != null && !priorPath.equals(versionPath == null ? "" : versionPath)) {
            fail("INCONSISTENT_VERSION_SELECTOR_PATH", "同一主题必须使用同一协议判别路径");
        }
        String selector = topic + '\u001f' + (versionPath == null
                ? "*" : versionPath + '\u001f' + expectedVersion);
        if (!selectors.add(selector)) {
            fail("AMBIGUOUS_PROFILE_SELECTOR", "同一主题存在重复的协议选择条件");
        }
        if (versionPath == null && selectors.stream().anyMatch(value ->
                value.startsWith(topic + '\u001f') && !value.equals(selector))) {
            fail("AMBIGUOUS_PROFILE_SELECTOR", "同一主题的无版本模板会与其他模板冲突");
        }
        if (versionPath != null && selectors.contains(topic + "\u001f*")) {
            fail("AMBIGUOUS_PROFILE_SELECTOR", "同一主题的版本模板会与无版本模板冲突");
        }

        String ackMode = normalizedDefault(
                profile.maxAckMode(), "EVIDENCE_ONLY", "maxAckMode");
        String correlationPolicy = normalizedDefault(
                profile.correlationPolicy(), "NONE", "correlationPolicy");
        if (!ACK_MODES.contains(ackMode)) {
            fail("UNSUPPORTED_ACK_MODE", "不支持的maxAckMode");
        }
        if (!CORRELATION_POLICIES.contains(correlationPolicy)) {
            fail("UNSUPPORTED_CORRELATION_POLICY", "不支持的correlationPolicy");
        }
        validateCorrelationFields(profile, correlationPolicy);
        if ("V1".equals(outputVersion)
                && (!"EVIDENCE_ONLY".equals(ackMode) || !"NONE".equals(correlationPolicy))) {
            fail("V1_RELIABILITY_UPGRADE", "V1输出不得声明高于证据留存的ACK或关联能力");
        }

        List<ProtocolFieldMapping> mappings = entry.mappings();
        if (mappings == null || mappings.isEmpty()) {
            fail("EMPTY_MAPPING_SET", "启用协议模板至少包含一个字段映射");
        }
        Set<String> metricCodes = new HashSet<>();
        Set<String> sourcePaths = new HashSet<>();
        for (ProtocolFieldMapping mapping : mappings) {
            validateMapping(profileId, mapping, mappingIds, metricCodes, sourcePaths);
        }
    }

    private static void validateMapping(
            String profileId,
            ProtocolFieldMapping mapping,
            Set<String> mappingIds,
            Set<String> metricCodes,
            Set<String> sourcePaths) {
        if (mapping == null) {
            fail("MAPPING_MISSING", "字段映射不能为空");
        }
        if (!mapping.enabled()) {
            fail("DISABLED_MAPPING", "完整快照不得包含未启用字段映射");
        }
        String mappingId = required(mapping.mappingId(), "mappingId");
        if (!mappingIds.add(mappingId)) {
            fail("DUPLICATE_MAPPING_ID", "mappingId重复");
        }
        if (!profileId.equals(required(mapping.profileId(), "mapping.profileId"))) {
            fail("MAPPING_PROFILE_MISMATCH", "字段映射不属于所在协议模板");
        }
        String sourcePath = validatePointer(mapping.sourcePath(), "sourcePath", false);
        if (!sourcePaths.add(sourcePath)) {
            fail("DUPLICATE_SOURCE_PATH", "同一协议模板的sourcePath重复");
        }
        String metricCode = required(mapping.metricCode(), "metricCode");
        if (!metricCodes.add(metricCode)) {
            fail("DUPLICATE_METRIC_CODE", "同一协议模板的metricCode重复");
        }
        String valueType = required(mapping.valueType(), "valueType").toUpperCase(Locale.ROOT);
        if (!VALUE_TYPES.contains(valueType)) {
            fail("UNSUPPORTED_VALUE_TYPE", "第一版字段映射仅支持DECIMAL");
        }
        required(mapping.targetUnit(), "targetUnit");
        if (mapping.sortOrder() < 0) {
            fail("INVALID_SORT_ORDER", "sortOrder必须非负");
        }
    }

    private static String validatePointer(String value, String fieldName, boolean optional) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            if (optional) {
                return null;
            }
            fail("INVALID_JSON_POINTER", fieldName + "不能为空");
        }
        if (!normalized.startsWith("/")) {
            fail("INVALID_JSON_POINTER", fieldName + "必须是JSON Pointer");
        }
        return normalized;
    }

    private static void validateCorrelationFields(
            ProtocolProfile profile, String correlationPolicy) {
        switch (correlationPolicy) {
            case "SOURCE_MESSAGE_ID" -> requirePointer(
                    profile.messageIdPath(), "SOURCE_MESSAGE_ID需要messageIdPath");
            case "BOOT_ID_AND_SEQ" -> {
                requirePointer(profile.bootIdPath(), "BOOT_ID_AND_SEQ需要bootIdPath");
                requirePointer(profile.seqPath(), "BOOT_ID_AND_SEQ需要seqPath");
            }
            case "SEQ_AND_COLLECTED_AT" -> {
                requirePointer(profile.seqPath(), "SEQ_AND_COLLECTED_AT需要seqPath");
                requirePointer(profile.timestampPath(), "SEQ_AND_COLLECTED_AT需要timestampPath");
            }
            case "UNIQUE_COLLECTED_AT" -> requirePointer(
                    profile.timestampPath(), "UNIQUE_COLLECTED_AT需要timestampPath");
            default -> {
                // NONE不要求高级身份字段。
            }
        }
    }

    private static void requirePointer(String value, String message) {
        if (trimToNull(value) == null) {
            fail("CORRELATION_FIELD_MISSING", message);
        }
    }

    private static String normalizedDefault(String value, String fallback, String fieldName) {
        String normalized = trimToNull(value);
        return normalized == null ? fallback : normalized.toUpperCase(Locale.ROOT);
    }

    private static String normalized(String value, String fieldName) {
        return required(value, fieldName).toUpperCase(Locale.ROOT);
    }

    private static String required(String value, String fieldName) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            fail("REQUIRED_FIELD_MISSING", fieldName + "不能为空");
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void fail(String code, String message) {
        throw new SnapshotValidationException(code, message);
    }
}
