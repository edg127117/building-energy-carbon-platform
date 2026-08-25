package com.platform.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceParameterMigrationContractTest {

    @Test
    void mysqlMigrationContainsGovernanceBitemporalRecalculationAndLegacyBoundaries() throws Exception {
        String sql = resource("V20__mysql_device_parameter_governance.sql");

        assertThat(sql).contains(
                "CREATE TABLE IF NOT EXISTS biz_device_parameter_definition",
                "CREATE TABLE IF NOT EXISTS biz_device_parameter_candidate",
                "CREATE TABLE IF NOT EXISTS biz_device_parameter_version",
                "CREATE TABLE IF NOT EXISTS biz_device_parameter_timeline_revision",
                "CREATE TABLE IF NOT EXISTS biz_device_parameter_timeline_segment",
                "CREATE TABLE IF NOT EXISTS biz_device_parameter_recalc_job",
                "CREATE TABLE IF NOT EXISTS biz_device_parameter_legacy_staging",
                "CREATE TABLE IF NOT EXISTS biz_device_parameter_audit_log",
                "source_type VARCHAR(20) NOT NULL",
                "equipment_type_code VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL",
                "building_id VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL",
                "equip_id VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL",
                "business_effective_from TIMESTAMP(3) NOT NULL",
                "published_at TIMESTAMP(3) NOT NULL",
                "recalculation_status VARCHAR(30) NOT NULL");
        assertThat(sql).doesNotContain("INSERT INTO biz_device_parameter_definition");
    }

    @Test
    void tdengineMigrationKeepsParameterEvidenceAndImmutableResultRevisions() throws Exception {
        String sql = resource("21-migrate-tdengine-device-parameter-formula-lineage.sql");

        assertThat(sql).contains(
                "`parameter_evidence_json` NCHAR(4096)",
                "CREATE STABLE IF NOT EXISTS `iot_telemetry`.`st_formula_result_revision`",
                "`result_revision_id`      NCHAR(32)",
                "`attempt_id`              NCHAR(32)",
                "`calculated_at`           TIMESTAMP");
        assertThat(sql).doesNotContain("DELETE FROM");
    }

    private static String resource(String fileName) throws Exception {
        return java.nio.file.Files.readString(
                java.nio.file.Path.of("src", "env", "init", fileName),
                StandardCharsets.UTF_8);
    }
}
