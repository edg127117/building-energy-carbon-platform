package com.platform.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EnergyActivityQualityScenarioMigrationContractTest {

    @Test
    void migrationAddsDedicatedScenarioWithoutInventingPointPolicies() throws Exception {
        String sql = Files.readString(Path.of("src", "env", "init",
                "V33__mysql_energy_activity_quality_scenario.sql"), StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "'ENERGY_ACTIVITY_AGGREGATION'",
                "'ENERGY_ACTIVITY_INPUT_GATE'",
                "'ENABLED'",
                "`config_revision` = `config_revision` + 1");
        assertThat(sql).doesNotContain(
                "biz_quality_usage_policy`",
                "biz_quality_usage_policy_version`",
                "biz_quality_usage_policy_level`");
    }
}
