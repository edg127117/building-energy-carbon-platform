package com.platform.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionPolicyMigrationContractTest {
    private static final Path MIGRATION = Path.of(
            "src/env/init/V17__mysql_collection_policy_governance.sql");

    @Test
    void migrationContainsGovernanceModelAndFailClosedChecksWithoutCascadeDelete() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        assertThat(sql).contains(
                "CREATE TABLE IF NOT EXISTS `biz_data_source`",
                "CREATE TABLE IF NOT EXISTS `biz_collection_policy`",
                "CREATE TABLE IF NOT EXISTS `biz_collection_policy_version`",
                "CREATE TABLE IF NOT EXISTS `biz_collection_review_request`",
                "CREATE TABLE IF NOT EXISTS `biz_collection_config_audit_log`",
                "COLLECTION_MIGRATION_SOURCE_SYSTEM_BUILDING_CONFLICT",
                "COLLECTION_MIGRATION_PARTIAL_DATA_CONFLICT",
                "COLLECTION_MIGRATION_LEGACY_ALIAS_COUNT_CONFLICT",
                "SIGNAL SQLSTATE '45000'",
                "ON DELETE RESTRICT");
        assertThat(sql).doesNotContain("ON DELETE CASCADE");
        assertThat(sql).doesNotContain("expected_interval_seconds` INT NOT NULL DEFAULT 60");
        assertThat(sql).contains("1, 'ACTIVE', 1, 60, 30, 'DEVICE_EVENT_TIME'",
                "'FIXED_DAYS', 90, 'LONG_TERM', NULL");
    }
}
