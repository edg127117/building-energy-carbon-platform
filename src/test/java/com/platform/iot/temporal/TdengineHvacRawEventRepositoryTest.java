package com.platform.iot.temporal;

import com.platform.config.TdengineProperties;
import com.platform.iot.temporal.impl.TdengineHvacRawEventRepository;
import com.platform.iot.temporal.model.RawEventWriteResult;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TdengineHvacRawEventRepositoryTest {

    @Mock
    private JdbcTemplate template;

    private TdengineHvacRawEventRepository repository;

    @BeforeEach
    void setUp() {
        TdengineProperties properties = new TdengineProperties();
        properties.setDatabase("iot_telemetry");
        properties.setStRawEvent("st_raw_event");
        repository = new TdengineHvacRawEventRepository(template, properties);
    }

    @Test
    void createsChildBeforeFirstPointEventIsQueriedAndInserted() {
        when(template.queryForList(startsWith("SELECT val"))).thenReturn(List.of());

        RawEventWriteResult result = repository.upsert(event(12.3));

        assertThat(result).isEqualTo(RawEventWriteResult.INSERTED);
        // 第一次遇到某测点时先创建子表，避免 SELECT 一个尚不存在的子表而导致首条数据丢失。
        verify(template).execute(startsWith(
                "CREATE TABLE IF NOT EXISTS iot_telemetry.st_raw_event_POINT001"));
        verify(template).execute(startsWith(
                "INSERT INTO iot_telemetry.st_raw_event_POINT001"));
    }

    @Test
    void ignoresExactDuplicateAndDoesNotWriteSecondRow() {
        when(template.queryForList(startsWith("SELECT val")))
                .thenReturn(List.of(Map.of(
                        "val", 12.3,
                        "source_system", "MQTT_FREEZE_V1",
                        "source_point_code", "WCR1_TWin",
                        "source_device_id", "WCR1")));

        RawEventWriteResult result = repository.upsert(event(12.3));

        assertThat(result).isEqualTo(RawEventWriteResult.DUPLICATE);
        verify(template, never()).execute(startsWith("INSERT INTO"));
    }

    @Test
    void overwritesConflictingValueAtSamePointAndEventTime() {
        when(template.queryForList(startsWith("SELECT val")))
                .thenReturn(List.of(Map.of(
                        "val", 11.8,
                        "source_system", "MQTT_FREEZE_V1",
                        "source_point_code", "WCR1_TWin",
                        "source_device_id", "WCR1")));

        RawEventWriteResult result = repository.upsert(event(12.3));

        assertThat(result).isEqualTo(RawEventWriteResult.CONFLICT_UPDATED);
        verify(template).execute(startsWith(
                "INSERT INTO iot_telemetry.st_raw_event_POINT001"));
    }

    @Test
    void createsOneChildOnlyOnceForRepeatedEventsInSameProcess() {
        when(template.queryForList(startsWith("SELECT val"))).thenReturn(List.of());

        repository.upsert(event(12.3));
        repository.upsert(event(12.4));

        verify(template, times(1)).execute(startsWith("CREATE TABLE IF NOT EXISTS"));
        verify(template, times(2)).execute(startsWith("INSERT INTO"));
    }

    @Test
    void queriesAllPointsInOneStableWindowAndExcludesLateEvents() {
        when(template.queryForList(startsWith("SELECT ts"))).thenReturn(List.of(
                row("WCR1_TWin", "WCR1", "TWin", 12.3, 1_000L),
                row("DBO_RH", null, "RH", 60.0, 2_000L)));

        List<RawTelemetryEvent> result = repository.findWindow(
                1_800_000_000_000L, 1_800_000_060_000L, false);

        assertThat(result).extracting(RawTelemetryEvent::pointCode)
                .containsExactly("WCR1_TWin", "DBO_RH");
        var sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(template).queryForList(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("FROM iot_telemetry.st_raw_event")
                .contains("ts >= '2027-01-15 16:00:00.0'")
                .contains("ts < '2027-01-15 16:01:00.0'")
                .contains("late_flag=0")
                .doesNotContain("st_raw_event_WCR1_TWin");
    }

    private Map<String, Object> row(
            String pointCode, String equipId, String suffixCode,
            double value, long eventOffset) {
        Map<String, Object> row = new HashMap<>();
        row.put("point_code", pointCode);
        row.put("point_id", pointCode.equals("DBO_RH") ? "POINT019" : "POINT001");
        row.put("source_system", "MQTT_FREEZE_V1");
        row.put("source_point_code", pointCode);
        row.put("source_device_id", equipId == null ? "WEATHER_GATEWAY" : equipId);
        row.put("building_id", "BLD001");
        row.put("system_group_id", "GROUP001");
        row.put("equip_id", equipId);
        row.put("equip_code", equipId);
        row.put("family_code", pointCode.startsWith("DBO") ? "RHO" : "WCR");
        row.put("component_code", "MAIN");
        row.put("suffix_code", suffixCode);
        row.put("val", value);
        row.put("ts", new Timestamp(1_800_000_000_000L + eventOffset));
        row.put("received_time", new Timestamp(1_800_000_000_100L + eventOffset));
        row.put("data_quality", 0);
        row.put("is_for_calc", 1);
        row.put("late_flag", 0);
        return row;
    }

    private RawTelemetryEvent event(double value) {
        return new RawTelemetryEvent(
                "POINT001", "WCR1_TWin",
                "MQTT_FREEZE_V1", "WCR1_TWin", "WCR1",
                "BLD001", "GROUP001", "EQUIP001", "WCR1",
                "WCR", "MAIN", "TWin", value,
                1_800_000_000_000L, 1_800_000_001_000L,
                0, 1, false);
    }
}
