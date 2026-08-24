package com.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class QualityUsageTdengineMigrationContractTest {
    @Test
    void createsAppendOnlyAttemptAndOverwriteableStateWithoutDeletingFacts()
            throws IOException {
        String sql = Files.readString(
                Path.of("src/env/init/19-migrate-tdengine-quality-usage-policy.sql"),
                StandardCharsets.UTF_8).toLowerCase();

        assertThat(sql)
                .contains("st_formula_calc_attempt_v2")
                .contains("attempt_id", "minute_start", "policy_evidence_json", "config_revision")
                .contains("st_indicator_minute_state")
                .contains("current_status", "source_fact_id", "state_updated_at")
                .doesNotContain("drop table", "delete from st_indicator_minute");
    }
}
