package com.platform.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EnergyConversionMigrationContractTest {
    @Test
    void migrationKeepsProfessionalBaselinesPendingAndSeparatesFormulaEvidence() throws Exception {
        String sql = Files.readString(Path.of("src", "env", "init",
                "V35__mysql_energy_tce_conversion_rules.sql"), StandardCharsets.UTF_8);

        assertThat(sql).contains("biz_standard_coal_lhv_version")
                .contains("biz_energy_conversion_formula_version")
                .contains("biz_energy_conversion_parameter_version")
                .contains("29.3076")
                .contains("DIRECT_TCE_FACTOR_V1")
                .contains("LOWER_HEATING_VALUE_V1")
                .contains("ENERGY_EQUIVALENT_V1")
                .contains("DEVELOPMENT_SIMULATION")
                .contains("PENDING_EXPERT")
                .contains("生产不可用")
                .contains("ENERGY_RULE_MAINTAIN")
                .contains("ENERGY_RULE_REVIEW")
                .contains("ENERGY_CALCULATION_RUN")
                .doesNotContain("tCO2")
                .doesNotContain("MOBILE_COMBUSTION");
    }
}
