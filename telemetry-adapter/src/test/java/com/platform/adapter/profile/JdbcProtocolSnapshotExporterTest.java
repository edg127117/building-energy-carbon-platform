package com.platform.adapter.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.adapter.publication.ProtocolSnapshotContracts.MigrationExport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@ActiveProfiles("test")
class JdbcProtocolSnapshotExporterTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    Path tempDir;

    @Test
    void exportsEnabledSnapshotAndKeepsDisabledRulesOnlyInArchive() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO iot_protocol_field_mapping
                (mapping_id, profile_id, source_path, metric_code, value_type,
                 source_unit, target_unit, scale, offset_value,
                 required_flag, enabled, sort_order)
                VALUES ('MAP_DISABLED', 'PROFILE_V1', '/legacy', 'LEGACY', 'DECIMAL',
                        'kWh', 'kWh', 1, 0, 0, 0, 99)
                """);
        jdbcTemplate.update("""
                INSERT INTO iot_protocol_field_mapping
                (mapping_id, profile_id, source_path, metric_code, value_type,
                 source_unit, target_unit, scale, offset_value,
                 required_flag, enabled, sort_order)
                VALUES ('MAP_DISABLED_PROFILE', 'PROFILE_DISABLED', '/legacy', 'LEGACY',
                        'DECIMAL', 'kWh', 'kWh', 1, 0, 0, 1, 1)
                """);
        int profilesBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM iot_protocol_profile", Integer.class);
        int mappingsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM iot_protocol_field_mapping", Integer.class);
        ObjectMapper objectMapper = new ObjectMapper();
        JdbcProtocolSnapshotExporter exporter = new JdbcProtocolSnapshotExporter(
                jdbcTemplate, objectMapper);
        Path output = tempDir.resolve("migration.json");

        JdbcProtocolSnapshotExporter.ExportResult result = exporter.export(output, "V2");

        MigrationExport migration = objectMapper.readValue(output.toFile(), MigrationExport.class);
        assertThat(result.activeProfiles()).isEqualTo(2);
        assertThat(result.archivedEntries()).isEqualTo(2);
        assertThat(migration.snapshot().profiles())
                .allMatch(entry -> entry.profile().enabled())
                .allMatch(entry -> entry.mappings().stream().allMatch(ProtocolFieldMapping::enabled));
        assertThat(migration.archived().profiles())
                .anyMatch(entry -> !entry.profile().enabled())
                .anyMatch(entry -> entry.mappings().stream()
                        .anyMatch(mapping -> !mapping.enabled()));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM iot_protocol_profile", Integer.class))
                .isEqualTo(profilesBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM iot_protocol_field_mapping", Integer.class))
                .isEqualTo(mappingsBefore);
    }
}
