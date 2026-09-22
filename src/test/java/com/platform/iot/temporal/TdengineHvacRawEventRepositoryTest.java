package com.platform.iot.temporal;

import com.platform.config.TdengineProperties;
import com.platform.iot.temporal.impl.TdengineHvacRawEventRepository;
import com.platform.iot.temporal.model.RawEventWriteResult;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import com.platform.iot.temporal.model.PointMinuteKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
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
    void retentionPointScanIsBoundedAndDetectsHistoricalDaikinSource() {
        when(template.queryForList(startsWith("SELECT DISTINCT tbname,point_id"))).thenReturn(List.of(
                Map.of("tbname", "raw_custom_1", "point_id", "POINT001"),
                Map.of("tbname", "raw_custom_2", "point_id", "POINT002")));
        when(template.queryForList(startsWith("SELECT source_system FROM")))
                .thenReturn(List.of(Map.of("source_system", "DAIKIN_V2")), List.of());

        var points = repository.findRetentionPoints("POINT000", 100, "DAIKIN_V2");

        assertThat(points).containsExactly(
                new HvacRawEventRepository.RetentionPoint("raw_custom_1", "POINT001", true),
                new HvacRawEventRepository.RetentionPoint("raw_custom_2", "POINT002", false));
        verify(template).queryForList(contains("tbname>'POINT000' ORDER BY tbname LIMIT 100"));
    }

    @Test
    void retentionDeletesOnlyOnePointBeforeItsCutoff() {
        long cutoff = 1_700_000_000_000L;

        repository.deletePointBefore("raw_custom_1", cutoff);

        verify(template).execute("DELETE FROM iot_telemetry.raw_custom_1 WHERE ts < " + cutoff);
    }

    @Test
    void daikinCleanupDeletesOnePointAndOneBoundedTimeWindow() {
        when(template.queryForList(startsWith("SELECT tbname,point_id,FIRST(ts)"))).thenReturn(List.of(Map.of(
                "tbname", "raw_custom_1", "point_id", "POINT001",
                "oldest_ts", new Timestamp(1_700_000_000_000L))));
        when(template.queryForList(contains("source_system<>'DAIKIN_V2'"))).thenReturn(List.of());

        var result = repository.deleteSourceBeforeInBoundedWindow(
                "DAIKIN_V2", 1_800_000_000_000L, Duration.ofDays(7).toMillis(), null);

        assertThat(result.workPerformed()).isTrue();
        long start = 1_700_000_000_000L;
        long end = start + Duration.ofDays(7).toMillis();
        verify(template).execute(org.mockito.ArgumentMatchers.<String>argThat(sql -> sql.contains("raw_custom_1")
                && sql.contains("ts >= " + start)
                && sql.contains("ts < " + end)));
    }

    @Test
    void daikinCleanupDoesNotDeleteMixedSourceTimeWindow() {
        when(template.queryForList(startsWith("SELECT tbname,point_id,FIRST(ts)"))).thenReturn(List.of(Map.of(
                "tbname", "raw_custom_1", "point_id", "POINT001",
                "oldest_ts", new Timestamp(1_700_000_000_000L))));
        when(template.queryForList(contains("source_system<>'DAIKIN_V2'")))
                .thenReturn(List.of(Map.of("source_system", "MQTT_FREEZE_V1")));

        var result = repository.deleteSourceBeforeInBoundedWindow(
                "DAIKIN_V2", 1_800_000_000_000L, Duration.ofDays(7).toMillis(), null);

        assertThat(result.endOfScan()).isTrue();
        verify(template, never()).execute(startsWith("DELETE FROM"));
    }

    @Test
    void pointHistoryRestrictsSourceOwnershipWindowCursorAndLimitInDatabase() {
        when(template.queryForList(startsWith("SELECT *"))).thenReturn(List.of());
        repository.findPointHistory("BLD001", "EQUIP001", "POINT001", 1000, 9000, 2000L, 501);
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(template).queryForList(sql.capture());
        assertThat(sql.getValue()).contains("building_id='BLD001'", "equip_id='EQUIP001'", "point_id='POINT001'",
                "source_system='DAIKIN_V2'", "AND ts>=", "AND ts<", "AND ts>", "ORDER BY ts ASC LIMIT 501");
    }

    @Test
    void equipmentTrendIsScopedAndDownsampledInsideTdengine() {
        when(template.queryForList(contains("AVG(val) AS average_value"))).thenReturn(List.of(Map.of(
                "point_id", "POINT001",
                "bucket_time", new Timestamp(1_700_000_000_000L),
                "average_value", 12.5,
                "data_quality", 1)));

        var result = repository.findEquipmentTrend(
                "BLD001", "EQUIP001", List.of("POINT001", "POINT002"),
                1_700_000_000_000L, 1_700_003_600_000L, 60);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().average()).isEqualTo(12.5);
        verify(template).queryForList(org.mockito.ArgumentMatchers.<String>argThat(sql ->
                sql.contains("building_id='BLD001'")
                        && sql.contains("equip_id='EQUIP001'")
                        && sql.contains("point_id IN ('POINT001','POINT002')")
                        && sql.contains("MAX(data_quality) AS data_quality")
                        && sql.contains("PARTITION BY point_id")
                        && sql.contains("INTERVAL(60s)")
                        && sql.contains("ORDER BY point_id,_wstart")));
    }

    @Test
    void emptyEquipmentTrendDoesNotTouchTdengine() {
        assertThat(repository.findEquipmentTrend(
                "BLD001", "EQUIP001", List.of(), 1L, 2L, 10)).isEmpty();

        verifyNoInteractions(template);
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

    @Test
    void queriesLatestRawRowsForAllExactEquipmentPointsInOnePartitionedStatement() {
        Map<String, Object> first = new HashMap<>();
        first.put("point_id", "POINT101");
        first.put("latest_value", 0.0);
        first.put("event_time", new Timestamp(1_800_000_000_000L));
        first.put("latest_received_time", new Timestamp(1_800_000_001_000L));
        first.put("latest_data_quality", 0);
        Map<String, Object> second = new HashMap<>(first);
        second.put("point_id", "POINT102");
        second.put("latest_value", 456.7);
        when(template.queryForList(anyString())).thenReturn(List.of(first, second));

        var result = repository.findLatestByEquipmentPoints(
                "BLD001", "EQUIP_IDU_1", List.of("POINT101", "POINT102"));

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().value()).isZero();
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(template).queryForList(sql.capture());
        assertThat(sql.getValue())
                .contains("building_id='BLD001'")
                .contains("equip_id='EQUIP_IDU_1'")
                .contains("point_id IN ('POINT101','POINT102')")
                .contains("LAST_ROW(val)")
                .contains("PARTITION BY point_id, point_code")
                .doesNotContain("avg_val")
                .doesNotContain(" LIMIT 1");
    }

    @Test
    void scansLateEvidenceInTdengineWithGroupingSeekAndLimit() {
        when(template.queryForList(anyString())).thenReturn(List.of());

        repository.findLateMinuteEvidence(
                1_800_000_000_000L,
                1_800_086_400_000L,
                1_800_000_060_000L,
                "POINT'001",
                100);

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(template).queryForList(sql.capture());
        assertThat(sql.getValue())
                .contains("late_flag = 1")
                .contains("PARTITION BY point_id")
                .contains("INTERVAL(1m)")
                .contains(") grouped_late")
                .doesNotContain("MAX(received_time)")
                .contains("minute_start >")
                .contains("point_id > 'POINT''001'")
                .contains("LIMIT 100");
    }

    @Test
    void verifiesExactLatePointMinuteKeysInOneBoundedQuery() {
        when(template.queryForList(anyString())).thenReturn(List.of());

        repository.findLateEvidenceKeys(List.of(
                new PointMinuteKey("POINT'001", 1_800_000_000_000L),
                new PointMinuteKey("POINT002", 1_800_000_060_000L)));

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(template).queryForList(sql.capture());
        assertThat(sql.getValue())
                .contains("late_flag = 1")
                .contains("point_id='POINT''001'")
                .contains("point_id='POINT002'")
                .contains("COUNT(*) AS evidence_count")
                .contains("PARTITION BY point_id")
                .contains("INTERVAL(1m)");
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
