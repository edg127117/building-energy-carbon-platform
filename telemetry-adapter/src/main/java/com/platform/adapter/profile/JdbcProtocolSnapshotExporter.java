package com.platform.adapter.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Entry;
import com.platform.adapter.publication.ProtocolSnapshotContracts.MigrationExport;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Snapshot;
import com.platform.adapter.publication.SnapshotValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从旧适配器数据库只读导出启用快照和独立禁用规则归档。 */
@Component
public class JdbcProtocolSnapshotExporter {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcProtocolSnapshotExporter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ExportResult export(Path exportFile, String outputVersion)
            throws IOException {
        List<ProtocolProfile> profiles = loadProfiles();
        List<ProtocolFieldMapping> mappings = loadMappings();
        Map<String, List<ProtocolFieldMapping>> mappingsByProfile = new LinkedHashMap<>();
        for (ProtocolFieldMapping mapping : mappings) {
            mappingsByProfile.computeIfAbsent(mapping.profileId(), ignored -> new ArrayList<>())
                    .add(mapping);
        }

        List<Entry> activeEntries = new ArrayList<>();
        List<Entry> archivedEntries = new ArrayList<>();
        for (ProtocolProfile profile : profiles) {
            List<ProtocolFieldMapping> allMappings = mappingsByProfile.getOrDefault(
                    profile.profileId(), List.of());
            if (profile.enabled()) {
                List<ProtocolFieldMapping> enabledMappings = allMappings.stream()
                        .filter(ProtocolFieldMapping::enabled)
                        .toList();
                activeEntries.add(new Entry(profile, enabledMappings));
                List<ProtocolFieldMapping> disabledMappings = allMappings.stream()
                        .filter(mapping -> !mapping.enabled())
                        .toList();
                if (!disabledMappings.isEmpty()) {
                    archivedEntries.add(new Entry(profile, disabledMappings));
                }
            } else {
                archivedEntries.add(new Entry(profile, allMappings));
            }
        }
        Snapshot active = new Snapshot(
                SnapshotValidator.SUPPORTED_SCHEMA_VERSION, outputVersion, activeEntries);
        SnapshotValidator.validate(active, outputVersion);
        Snapshot archived = new Snapshot(
                SnapshotValidator.SUPPORTED_SCHEMA_VERSION, outputVersion, archivedEntries);
        writeAtomically(exportFile, objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(new MigrationExport(active, archived)));
        return new ExportResult(activeEntries.size(), archivedEntries.size());
    }

    private List<ProtocolProfile> loadProfiles() {
        return jdbcTemplate.query("""
                SELECT profile_id, profile_code, profile_version, source_topic,
                       device_identity_type, device_identity_path,
                       protocol_version_path, expected_protocol_version,
                       timestamp_path, seq_path, message_id_path, boot_id_path,
                       batch_id_path, retransmitted_at_path, max_ack_mode,
                       correlation_policy, enabled
                FROM iot_protocol_profile
                ORDER BY source_topic, profile_code, profile_version
                """, (rs, rowNum) -> new ProtocolProfile(
                rs.getString("profile_id"), rs.getString("profile_code"),
                rs.getInt("profile_version"), rs.getString("source_topic"),
                rs.getString("device_identity_type"), rs.getString("device_identity_path"),
                rs.getString("protocol_version_path"), rs.getString("expected_protocol_version"),
                rs.getString("timestamp_path"), rs.getString("seq_path"),
                rs.getString("message_id_path"), rs.getString("boot_id_path"),
                rs.getString("batch_id_path"), rs.getString("retransmitted_at_path"),
                rs.getString("max_ack_mode"), rs.getString("correlation_policy"),
                rs.getBoolean("enabled")));
    }

    private List<ProtocolFieldMapping> loadMappings() {
        return jdbcTemplate.query("""
                SELECT mapping_id, profile_id, source_path, metric_code, value_type,
                       source_unit, target_unit, scale, offset_value,
                       required_flag, enabled, sort_order
                FROM iot_protocol_field_mapping
                ORDER BY profile_id, sort_order, mapping_id
                """, (rs, rowNum) -> new ProtocolFieldMapping(
                rs.getString("mapping_id"), rs.getString("profile_id"),
                rs.getString("source_path"), rs.getString("metric_code"),
                rs.getString("value_type"), rs.getString("source_unit"),
                rs.getString("target_unit"), rs.getBigDecimal("scale"),
                rs.getBigDecimal("offset_value"), rs.getBoolean("required_flag"),
                rs.getBoolean("enabled"), rs.getInt("sort_order")));
    }

    private void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, absolute,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record ExportResult(int activeProfiles, int archivedEntries) {
    }
}
