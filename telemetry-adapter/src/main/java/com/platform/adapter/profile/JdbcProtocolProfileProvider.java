package com.platform.adapter.profile;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 云端 MySQL 协议配置的本地完整快照。
 *
 * <p>刷新时先加载并校验全部启用模板与字段映射，再原子替换快照。数据库暂时不可用
 * 时继续使用上一完整版本，避免 MQTT 热路径读到半份配置或逐包访问数据库。</p>
 */
@Component
public class JdbcProtocolProfileProvider implements ProtocolProfileProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcProtocolProfileProvider.class);

    private final JdbcTemplate jdbcTemplate;
    private volatile Map<String, List<ResolvedProtocolProfile>> profilesByTopic = Map.of();
    private volatile boolean snapshotAvailable;

    public JdbcProtocolProfileProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${adapter.profile-refresh-ms:60000}")
    public void refresh() {
        try {
            List<ProtocolProfile> profiles = jdbcTemplate.query("""
                    SELECT profile_id, profile_code, profile_version, source_topic,
                           device_identity_type, device_identity_path,
                           protocol_version_path, expected_protocol_version,
                           timestamp_path, seq_path, message_id_path, boot_id_path,
                           batch_id_path, retransmitted_at_path, max_ack_mode,
                           correlation_policy, enabled
                    FROM iot_protocol_profile
                    WHERE enabled = 1
                    ORDER BY source_topic, profile_code, profile_version
                    """, (rs, rowNum) -> new ProtocolProfile(
                    rs.getString("profile_id"),
                    rs.getString("profile_code"),
                    rs.getInt("profile_version"),
                    rs.getString("source_topic"),
                    rs.getString("device_identity_type"),
                    rs.getString("device_identity_path"),
                    rs.getString("protocol_version_path"),
                    rs.getString("expected_protocol_version"),
                    rs.getString("timestamp_path"),
                    rs.getString("seq_path"),
                    rs.getString("message_id_path"),
                    rs.getString("boot_id_path"),
                    rs.getString("batch_id_path"),
                    rs.getString("retransmitted_at_path"),
                    rs.getString("max_ack_mode"),
                    rs.getString("correlation_policy"),
                    rs.getBoolean("enabled")));
            List<ProtocolFieldMapping> mappings = jdbcTemplate.query("""
                    SELECT mapping_id, profile_id, source_path, metric_code, value_type,
                           source_unit, target_unit, scale, offset_value,
                           required_flag, enabled, sort_order
                    FROM iot_protocol_field_mapping
                    WHERE enabled = 1
                    ORDER BY profile_id, sort_order, mapping_id
                    """, (rs, rowNum) -> new ProtocolFieldMapping(
                    rs.getString("mapping_id"),
                    rs.getString("profile_id"),
                    rs.getString("source_path"),
                    rs.getString("metric_code"),
                    rs.getString("value_type"),
                    rs.getString("source_unit"),
                    rs.getString("target_unit"),
                    rs.getBigDecimal("scale"),
                    rs.getBigDecimal("offset_value"),
                    rs.getBoolean("required_flag"),
                    rs.getBoolean("enabled"),
                    rs.getInt("sort_order")));

            Map<String, List<ProtocolFieldMapping>> mappingsByProfile = new LinkedHashMap<>();
            for (ProtocolFieldMapping mapping : mappings) {
                mappingsByProfile.computeIfAbsent(mapping.profileId(), ignored -> new ArrayList<>())
                        .add(mapping);
            }
            Map<String, List<ResolvedProtocolProfile>> next = new LinkedHashMap<>();
            for (ProtocolProfile profile : profiles) {
                List<ProtocolFieldMapping> profileMappings = mappingsByProfile.get(profile.profileId());
                if (profileMappings == null || profileMappings.isEmpty()) {
                    throw new IllegalStateException(
                            "启用协议模板没有字段映射: " + profile.profileCode());
                }
                next.computeIfAbsent(profile.sourceTopic(), ignored -> new ArrayList<>())
                        .add(new ResolvedProtocolProfile(profile, profileMappings));
            }
            Map<String, List<ResolvedProtocolProfile>> immutable = new LinkedHashMap<>();
            next.forEach((topic, resolved) -> immutable.put(topic, List.copyOf(resolved)));
            profilesByTopic = Map.copyOf(immutable);
            snapshotAvailable = true;
            log.info("协议模板快照刷新完成: topics={}, profiles={}",
                    immutable.size(), profiles.size());
        } catch (RuntimeException exception) {
            log.warn("协议模板快照刷新失败，继续使用上一完整版本: {}", exception.getMessage());
        }
    }

    @Override
    public ResolvedProtocolProfile resolve(String topic, JsonNode payload) {
        if (!snapshotAvailable) {
            throw new ProtocolProfileUnavailableException("协议模板快照尚未成功加载");
        }
        List<ResolvedProtocolProfile> candidates = profilesByTopic.get(topic);
        if (candidates == null || candidates.isEmpty()) {
            throw new ProtocolProfileResolutionException("MQTT主题未配置启用协议模板: " + topic);
        }
        List<ResolvedProtocolProfile> matches = candidates.stream()
                .filter(candidate -> versionMatches(candidate.profile(), payload))
                .sorted(Comparator.comparingInt(candidate -> candidate.profile().profileVersion()))
                .toList();
        if (matches.size() != 1) {
            throw new ProtocolProfileResolutionException(
                    "无法唯一确定协议模板: topic=" + topic + ", matches=" + matches.size());
        }
        return matches.getFirst();
    }

    private boolean versionMatches(ProtocolProfile profile, JsonNode payload) {
        String expected = profile.expectedProtocolVersion();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        String path = profile.protocolVersionPath();
        if (path == null || path.isBlank() || payload == null) {
            return false;
        }
        JsonNode versionNode = payload.at(path);
        return versionNode.isValueNode() && expected.equals(versionNode.asText());
    }
}
