package com.platform.energy.activity;

import com.platform.config.TdengineProperties;
import com.platform.energy.activity.EnergyActivityDataReader.Cursor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TdengineEnergyActivityDataReaderTest {

    @Test
    void readsBoundedSeekPageAndReturnsCursorFromVisibleRows() {
        JdbcTemplate template = mock(JdbcTemplate.class);
        TdengineProperties properties = new TdengineProperties();
        properties.setDatabase("iot_telemetry");
        properties.setStRawEvent("st_raw_event");
        when(template.queryForList(anyString())).thenReturn(List.of(
                row("POINT001", 120_000L, 120_100L, 0),
                row("POINT002", 120_000L, 120_200L, 1),
                row("POINT001", 180_000L, 180_100L, 0)));
        TdengineEnergyActivityDataReader reader =
                new TdengineEnergyActivityDataReader(template, properties);

        var page = reader.readRawEvents(
                "BLD001", Set.of("POINT002", "POINT001"),
                60_000L, 240_000L, new Cursor(60_000L, "POINT001"), 2);

        assertThat(page.truncated()).isTrue();
        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isEqualTo(new Cursor(120_000L, "POINT002"));
        assertThat(page.items().getFirst().receivedTime()).isEqualTo(120_100L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(template).queryForList(sql.capture());
        assertThat(sql.getValue())
                .contains("FROM iot_telemetry.st_raw_event")
                .contains("building_id='BLD001'")
                .contains("point_id IN ('POINT001','POINT002')")
                .contains("ts >= 60000")
                .contains("ts < 240000")
                .contains("ts > 60000")
                .contains("ts = 60000")
                .contains("point_id > 'POINT001'")
                .contains("ORDER BY ts,point_id")
                .contains("LIMIT 3");
    }

    @Test
    void readsLatestStartAnchorWithinBuildingAndPointScope() {
        JdbcTemplate template = mock(JdbcTemplate.class);
        TdengineProperties properties = new TdengineProperties();
        properties.setDatabase("iot_telemetry");
        properties.setStRawEvent("st_raw_event");
        when(template.queryForList(anyString())).thenReturn(List.of(
                row("POINT001", 59_000L, 59_100L, 0)));
        TdengineEnergyActivityDataReader reader =
                new TdengineEnergyActivityDataReader(template, properties);

        var anchor = reader.readLatestAtOrBefore("BLD001", "POINT001", 60_000L);

        assertThat(anchor.eventTime()).isEqualTo(59_000L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(template).queryForList(sql.capture());
        assertThat(sql.getValue()).contains("building_id='BLD001'")
                .contains("point_id='POINT001'")
                .contains("ts <= 60000")
                .contains("ORDER BY ts DESC")
                .contains("LIMIT 1");
    }

    private static Map<String, Object> row(
            String pointId, long eventTime, long receivedTime, int quality) {
        Map<String, Object> row = new HashMap<>();
        row.put("point_id", pointId);
        row.put("point_code", pointId + "_CODE");
        row.put("building_id", "BLD001");
        row.put("source_system", "SIMULATION_V1");
        row.put("source_point_code", pointId + "_SOURCE");
        row.put("source_device_id", "SIM_DEVICE");
        row.put("val", 12.3);
        row.put("ts", new Timestamp(eventTime));
        row.put("received_time", new Timestamp(receivedTime));
        row.put("data_quality", quality);
        row.put("late_flag", 0);
        return row;
    }
}
