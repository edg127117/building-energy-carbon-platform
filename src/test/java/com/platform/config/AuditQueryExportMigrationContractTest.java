package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AuditQueryExportMigrationContractTest {
    @Test
    void migrationAddsPublicIndexesWithoutCreatingCentralAuditCopy() throws IOException {
        String sql;
        try (var input = getClass().getResourceAsStream(
                "/db/migration/mysql/V29__mysql_audit_query_redacted_export.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql).contains(
                "biz_collection_config_audit_log", "biz_quality_usage_audit_log",
                "biz_device_parameter_audit_log", "biz_relation_audit_log",
                "biz_onboarding_audit_log", "sys_audit_export_job",
                "idx_collection_audit_public_trace", "idx_onboarding_audit_retention");
        assertThat(sql).doesNotContain("CREATE TABLE `sys_audit_event`");
        assertThat(sql).contains("历史行缺失原始 traceId");
    }
}
