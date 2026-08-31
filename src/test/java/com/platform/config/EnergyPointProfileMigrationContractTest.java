package com.platform.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EnergyPointProfileMigrationContractTest {

    @Test
    void migrationCreatesIndependentProfileWithoutProfessionalDefaultsOrPolicyFields() throws Exception {
        String sql = Files.readString(Path.of("src", "env", "init",
                "V31__mysql_energy_point_profile.sql"), StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "CREATE TABLE `biz_energy_point_profile`",
                "UNIQUE KEY `uk_energy_point_profile_point` (`point_id`)",
                "FOREIGN KEY (`point_id`, `building_id`)",
                "`config_revision` INT NOT NULL DEFAULT 0",
                "`confirmation_status` IN ('PENDING_EXPERT','CONFIRMED')",
                "CHAR_LENGTH(TRIM(`evidence_reference`)) > 0");
        assertThat(sql).doesNotContain(
                "INSERT INTO",
                "expected_interval_seconds",
                "allowed_delay_seconds",
                "system_group_id",
                "`unit`");
    }

    @Test
    void h2SchemaMirrorsProfileAndStartsWithoutSeededProfessionalFacts() throws Exception {
        String schema = Files.readString(Path.of("src", "test", "resources", "schema-test.sql"),
                StandardCharsets.UTF_8);
        String data = Files.readString(Path.of("src", "test", "resources", "data-test.sql"),
                StandardCharsets.UTF_8);

        assertThat(schema).contains("CREATE TABLE biz_energy_point_profile");
        assertThat(data).doesNotContain("INSERT INTO biz_energy_point_profile");
    }
}
