package com.platform.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

class TdengineHvacSchemaTest {

    @Test
    void fullInitializationContainsHvacAndEnergyPeriodStables() throws Exception {
        TdengineProperties properties = new TdengineProperties();
        properties.setDatabase("iot_telemetry");
        JdbcTemplate template = mock(JdbcTemplate.class);
        when(template.queryForList(startsWith("DESCRIBE")))
                .thenReturn(List.of(Map.of("field", "ts")));
        when(template.queryForList(startsWith(
                "SELECT name FROM information_schema.ins_databases")))
                .thenReturn(List.of(Map.of("name", "iot_telemetry")));
        TdengineConfig config = new TdengineConfig(properties);

        config.initTaosDb(template).run();

        ArgumentCaptor<String> sqlCaptor =
                ArgumentCaptor.forClass(String.class);
        verify(template, atLeastOnce()).execute(sqlCaptor.capture());
        String allSql =
                String.join("\n", sqlCaptor.getAllValues()).toLowerCase();
        assertThat(allSql).contains(
                "st_raw_event",
                "st_raw_minute",
                "st_indicator_minute",
                "st_formula_calc_exception",
                "st_formula_calc_attempt_v2",
                "st_indicator_minute_state",
                "st_energy_period_result");
        assertThat(allSql).doesNotContain(
                "st_" + "electric" + "_data",
                "voltage" + "_a",
                "current" + "_a",
                "active" + "_power");
    }

    @Test
    void createsIdentityAwareStablesAndExpandsLegacyStructures() {
        TdengineProperties properties = new TdengineProperties();
        properties.setDatabase("iot_telemetry");
        JdbcTemplate template = mock(JdbcTemplate.class);
        when(template.queryForList(startsWith("DESCRIBE")))
                .thenReturn(List.of(
                        Map.of("field", "ts"),
                        Map.of("field", "val"),
                        Map.of("field", "data_quality")
                ));
        TdengineConfig config = new TdengineConfig(properties);

        config.initializeHvacSchema(template);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(template, atLeastOnce()).execute(sqlCaptor.capture());
        String allSql = String.join("\n", sqlCaptor.getAllValues()).toLowerCase();
        assertThat(allSql).contains(
                "st_raw_event", "received_time", "late_flag",
                "source_system", "source_point_code", "source_device_id",
                "point_id", "system_group_id", "equip_code",
                "family_code", "component_code");
        assertThat(allSql).contains("add column avg_val", "add column min_val",
                "add column max_val", "add column sample_count",
                "add column first_received_time", "add column last_received_time",
                "add column finalized_at", "add column quality_task_id nchar(32)",
                "quality_task_id nchar(32)", "add tag building_id",
                "add tag point_id", "add tag family_code");
    }

    @Test
    void createsFormulaResultAndExceptionSchemasAndMigratesIndicatorColumns() {
        TdengineProperties properties = new TdengineProperties();
        properties.setDatabase("iot_telemetry");
        JdbcTemplate template = mock(JdbcTemplate.class);
        when(template.queryForList(startsWith("DESCRIBE")))
                .thenReturn(List.of(
                        Map.of("field", "ts"),
                        Map.of("field", "val"),
                        Map.of("field", "indicator_id")
                ));
        TdengineConfig config = new TdengineConfig(properties);

        config.initializeFormulaSchema(template);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(template, atLeastOnce()).execute(sqlCaptor.capture());
        String allSql = String.join("\n", sqlCaptor.getAllValues()).toLowerCase();
        assertThat(allSql).contains(
                "st_indicator_minute",
                "data_quality", "formula_version", "calculated_at",
                "st_formula_calc_exception",
                "calc_status", "reason_code", "missing_inputs",
                "st_formula_calc_attempt_v2", "attempt_id", "minute_start",
                "policy_evidence_json", "config_revision",
                "st_indicator_minute_state", "current_status", "state_updated_at",
                "indicator_id", "indicator_code", "building_id",
                "system_group_id", "equip_id");
        assertThat(allSql).contains(
                "add column data_quality tinyint",
                "add column formula_version nchar(32)",
                "add column calculated_at timestamp");
        assertThat(allSql).doesNotContain("not null");
    }

    @Test
    void createsEnergyPeriodValueSchemaWithExactDecimalEvidence() {
        TdengineProperties properties = new TdengineProperties();
        properties.setDatabase("iot_telemetry");
        JdbcTemplate template = mock(JdbcTemplate.class);
        TdengineConfig config = new TdengineConfig(properties);

        config.initializeEnergyPeriodSchema(template);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(template).execute(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue().toLowerCase()).contains(
                "st_energy_period_result", "native_quantity_decimal binary(48)", "tce_value_decimal",
                "coverage_ratio_decimal", "revision bigint", "evidence_hash binary(64)");
    }
}
