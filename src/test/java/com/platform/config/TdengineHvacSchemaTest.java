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
                "add column finalized_at", "add tag building_id",
                "add tag point_id", "add tag family_code");
    }
}
