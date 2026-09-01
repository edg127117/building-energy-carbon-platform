package com.platform.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EnergyAggregationInputGovernanceMigrationContractTest {
    @Test
    void migrationKeepsEventsCorrectionsAndPoliciesAppendOnlyAndEvidenceBounded() throws Exception {
        String sql = Files.readString(Path.of("src", "env", "init",
                "V36__mysql_energy_aggregation_input_governance.sql"), StandardCharsets.UTF_8);

        assertThat(sql).contains("biz_energy_meter_event_version")
                .contains("biz_energy_activity_correction_version")
                .contains("biz_energy_integration_policy_version")
                .contains("PENDING_REVIEW")
                .contains("quality_gate_passed")
                .contains("simulation_flag")
                .contains("REQUIRE_BOUNDARY_READINGS")
                .doesNotContain("tCO2")
                .doesNotContain("MOBILE_COMBUSTION")
                .doesNotContain("ON DUPLICATE KEY UPDATE");
    }
}
