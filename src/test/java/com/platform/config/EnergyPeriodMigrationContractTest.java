package com.platform.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EnergyPeriodMigrationContractTest {
    @Test
    void definesVersionedWorkflowIndexesAndDeterministicTdengineStore() throws Exception {
        String mysql = Files.readString(Path.of("src", "env", "init",
                "V37__mysql_energy_period_closing_recalculation.sql"));
        String tdengine = Files.readString(Path.of("src", "env", "init",
                "22-migrate-tdengine-energy-period-results.sql"));
        String testSchema = Files.readString(Path.of("src", "test", "resources",
                "schema-test.sql"));

        assertThat(mysql).contains("biz_energy_period_result_current",
                "biz_energy_period_result_snapshot", "biz_energy_recalculation_batch",
                "biz_energy_dirty_period", "ENERGY_LOCK_APPROVE", "ENERGY_RECALC_APPROVE",
                "total_items` BETWEEN 1 AND 100");
        assertThat(tdengine).contains("CREATE STABLE IF NOT EXISTS",
                "st_energy_period_result", "result_key", "evidence_hash",
                "native_quantity_decimal", "native_quantity` DOUBLE");
        assertThat(testSchema).contains("biz_energy_period_result_current",
                "biz_energy_recalculation_batch");
    }
}
