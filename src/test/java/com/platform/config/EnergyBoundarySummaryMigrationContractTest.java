package com.platform.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EnergyBoundarySummaryMigrationContractTest {
    @Test
    void definesVersionedSafeSummaryPolicy() throws Exception {
        String mysql = Files.readString(Path.of("src", "env", "init",
                "V38__mysql_energy_boundary_summary_query.sql"));
        String testSchema = Files.readString(Path.of("src", "test", "resources",
                "schema-test.sql"));

        assertThat(mysql).contains("biz_energy_boundary_summary_policy",
                "biz_energy_boundary_summary_policy_version", "MAIN_METER_TOTAL",
                "MAIN_WITH_SUBMETER_BREAKDOWN", "PENDING_EXPERT", "source_type`='SIMULATION'");
        assertThat(testSchema).contains("biz_energy_boundary_summary_policy_version");
    }
}
