package com.platform.iot.temporal;

import com.platform.config.TdengineProperties;
import com.platform.iot.temporal.impl.TdengineHvacMinuteRepository;
import com.platform.iot.temporal.model.HvacMinuteQueryRow;
import com.platform.iot.temporal.model.MinuteQualityWriteResult;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TdengineHvacMinuteRepositoryTest {

    private static final long MINUTE = 1_800_000_000_000L;

    @Mock
    private JdbcTemplate template;

    private TdengineHvacMinuteRepository repository;

    @BeforeEach
    void setUp() {
        TdengineProperties properties = new TdengineProperties();
        properties.setDatabase("iot_telemetry");
        properties.setStRawMinute("st_raw_minute");
        repository = new TdengineHvacMinuteRepository(template, properties);
    }

    @Test
    void writesAllPointMinutesInOneDataInsertStatement() {
        when(template.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

        List<MinuteQualityWriteResult> results = repository.saveAllWithQualityPriority(List.of(
                aggregate("WCR1_TWin", "WCR1", "TWin", 12.3),
                aggregate("DBO_RH", null, "RH", 60.0)), null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(template, atLeastOnce()).execute(sqlCaptor.capture());
        List<String> inserts = sqlCaptor.getAllValues().stream()
                .filter(sql -> sql.startsWith("INSERT INTO")).toList();

        assertThat(inserts).singleElement().satisfies(sql -> assertThat(sql)
                .contains("st_raw_minute_POINT_WCR")
                .contains("st_raw_minute_POINT_DBO")
                .contains("12.300000000000")
                .contains("60.000000000000")
                .contains("quality_task_id")
                .contains("NULL"));
        assertThat(results).extracting(MinuteQualityWriteResult::outcome)
                .containsOnly(MinuteQualityWriteResult.Outcome.INSERTED);
    }

    @Test
    void readsExistingPointIdsForOneMinuteInOneStableQuery() {
        when(template.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("POINT_WCR", "POINT_DBO", "POINT_WCR"));

        Set<String> result = repository.findExistingPointIds(MINUTE);

        assertThat(result).containsExactlyInAnyOrder("POINT_WCR", "POINT_DBO");
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(template).queryForList(sqlCaptor.capture(), eq(String.class));
        assertThat(sqlCaptor.getValue())
                .contains("FROM iot_telemetry.st_raw_minute")
                .contains("WHERE ts=");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsAndFullyMapsOneCompleteMinuteForBuildings() throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("point_id")).thenReturn("POINT001");
        when(resultSet.getString("point_code")).thenReturn("WCR1_TWin");
        when(resultSet.getString("building_id")).thenReturn("BLD001");
        when(resultSet.getString("system_group_id")).thenReturn("GROUP001");
        when(resultSet.getString("equip_id")).thenReturn("EQUIP001");
        when(resultSet.getString("equip_code")).thenReturn("WCR1");
        when(resultSet.getString("family_code")).thenReturn("WCR");
        when(resultSet.getString("component_code")).thenReturn("MAIN");
        when(resultSet.getString("suffix_code")).thenReturn("TWin");
        when(resultSet.getInt("is_for_calc")).thenReturn(1);
        when(resultSet.getTimestamp("ts")).thenReturn(new Timestamp(MINUTE));
        when(resultSet.getDouble("avg_val")).thenReturn(12.3);
        when(resultSet.getDouble("min_val")).thenReturn(11.8);
        when(resultSet.getDouble("max_val")).thenReturn(12.8);
        when(resultSet.getInt("sample_count")).thenReturn(3);
        when(resultSet.getInt("data_quality")).thenReturn(0);
        when(resultSet.getTimestamp("first_received_time"))
                .thenReturn(new Timestamp(MINUTE + 1_000L));
        when(resultSet.getTimestamp("last_received_time"))
                .thenReturn(new Timestamp(MINUTE + 50_000L));
        when(resultSet.getTimestamp("finalized_at"))
                .thenReturn(new Timestamp(MINUTE + 90_000L));
        when(resultSet.getString("quality_task_id")).thenReturn(null);
        when(template.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<RawMinuteAggregate> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        });

        List<RawMinuteAggregate> result = repository.findByMinute(
                MINUTE, new LinkedHashSet<>(List.of("BLD001", "BLD002")));

        assertThat(result).containsExactly(new RawMinuteAggregate(
                "POINT001", "WCR1_TWin", "BLD001", "GROUP001",
                "EQUIP001", "WCR1", "WCR", "MAIN", "TWin", 1,
                MINUTE, 12.3, 11.8, 12.8, 3, 0,
                MINUTE + 1_000L, MINUTE + 50_000L, MINUTE + 90_000L, null));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(template).query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getValue()).isEqualTo("""
                SELECT point_id, point_code, building_id, system_group_id, equip_id,
                       equip_code, family_code, component_code, suffix_code, is_for_calc,
                       ts, avg_val, min_val, max_val, sample_count, data_quality,
                       first_received_time, last_received_time, finalized_at, quality_task_id
                FROM iot_telemetry.st_raw_minute
                WHERE ts = '%s'
                  AND building_id IN ('BLD001','BLD002')
                ORDER BY building_id, point_id
                """.formatted(new Timestamp(MINUTE)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void quotesBuildingIdsAsSqlValues() {
        when(template.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

        repository.findByMinute(MINUTE, Set.of("BLD'001"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(template).query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getValue()).contains("building_id IN ('BLD''001')");
    }

    @Test
    void emptyBuildingSetDoesNotTouchTdengine() {
        assertThat(repository.findByMinute(MINUTE, Set.of())).isEmpty();

        verifyNoInteractions(template);
    }

    @Test
    void emptyBatchDoesNotTouchTdengine() {
        assertThat(repository.saveAllWithQualityPriority(List.of(), null)).isEmpty();

        verifyNoInteractions(template);
    }

    @Test
    void generatedMinuteWritesNullReceiveTimesAndQualityTaskId() {
        when(template.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        RawMinuteAggregate generated = generatedAggregate(2, "TASK_Q2", 18.5);

        repository.saveAllWithQualityPriority(List.of(generated), null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(template, atLeastOnce()).execute(sqlCaptor.capture());
        assertThat(sqlCaptor.getAllValues().stream()
                .filter(sql -> sql.startsWith("INSERT INTO"))
                .findFirst()).get().satisfies(sql -> assertThat(sql)
                .contains("first_received_time,last_received_time,finalized_at,quality_task_id")
                .contains(",0,NULL,NULL,")
                .contains("'TASK_Q2'"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void appliesQualityPriorityIdempotencyAndSameLevelSupersessionInOneBatchRead() {
        RawMinuteAggregate oldQ2 = generatedAggregate(2, "OLD_Q2", 18.0);
        RawMinuteAggregate oldQ1 = generatedAggregateAt(
                "POINT_Q1", 1, "OLD_Q1", 19.0, MINUTE + 60_000L);
        RawMinuteAggregate realQ0 = realAggregateAt(
                "POINT_Q0", 20.0, MINUTE + 120_000L);
        when(template.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(oldQ2, oldQ1, realQ0));

        List<MinuteQualityWriteResult> results = repository.saveAllWithQualityPriority(List.of(
                generatedAggregate(1, "NEW_Q1", 18.5),
                generatedAggregateAt("POINT_Q1", 1, "OLD_Q1", 19.0, MINUTE + 60_000L),
                generatedAggregateAt("POINT_Q0", 2, "NEW_Q2", 21.0, MINUTE + 120_000L)
        ), null);

        assertThat(results).extracting(MinuteQualityWriteResult::outcome)
                .containsExactly(
                        MinuteQualityWriteResult.Outcome.UPGRADED,
                        MinuteQualityWriteResult.Outcome.IDEMPOTENT,
                        MinuteQualityWriteResult.Outcome.REJECTED_HIGHER_QUALITY);
        verify(template, times(1)).query(anyString(), any(RowMapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsRealCorrectionAndRejectsSameQualityDifferentTaskUnlessSuperseded() {
        RawMinuteAggregate oldReal = realAggregateAt("POINT_Q0", 20.0, MINUTE);
        RawMinuteAggregate oldQ1 = generatedAggregateAt(
                "POINT_Q1", 1, "OLD_Q1", 19.0, MINUTE + 60_000L);
        when(template.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(oldReal, oldQ1));

        List<MinuteQualityWriteResult> rejected = repository.saveAllWithQualityPriority(List.of(
                realAggregateAt("POINT_Q0", 20.5, MINUTE),
                generatedAggregateAt("POINT_Q1", 1, "NEW_Q1", 19.5, MINUTE + 60_000L)
        ), null);

        assertThat(rejected).extracting(MinuteQualityWriteResult::outcome)
                .containsExactly(
                        MinuteQualityWriteResult.Outcome.UPDATED_REAL,
                        MinuteQualityWriteResult.Outcome.REJECTED_SAME_QUALITY);

        reset(template);
        when(template.query(anyString(), any(RowMapper.class))).thenReturn(List.of(oldQ1));
        List<MinuteQualityWriteResult> superseded =
                repository.saveAllWithQualityPriority(List.of(
                        generatedAggregateAt("POINT_Q1", 1, "NEW_Q1", 19.5,
                                MINUTE + 60_000L)), "OLD_Q1");
        assertThat(superseded).extracting(MinuteQualityWriteResult::outcome)
                .containsExactly(MinuteQualityWriteResult.Outcome.UPGRADED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void upgradesBothQ2AndQ1ToRealQ0() {
        RawMinuteAggregate oldQ2 = generatedAggregateAt(
                "POINT_Q2", 2, "OLD_Q2", 18.0, MINUTE);
        RawMinuteAggregate oldQ1 = generatedAggregateAt(
                "POINT_Q1", 1, "OLD_Q1", 19.0, MINUTE + 60_000L);
        when(template.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(oldQ2, oldQ1));

        List<MinuteQualityWriteResult> results =
                repository.saveAllWithQualityPriority(List.of(
                        realAggregateAt("POINT_Q2", 18.5, MINUTE),
                        realAggregateAt("POINT_Q1", 19.5, MINUTE + 60_000L)), null);

        assertThat(results).extracting(MinuteQualityWriteResult::outcome)
                .containsExactly(
                        MinuteQualityWriteResult.Outcome.UPGRADED,
                        MinuteQualityWriteResult.Outcome.UPGRADED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findsOnePointMinuteInOneQuery() {
        RawMinuteAggregate expected = realAggregateAt("POINT_Q0", 20.0, MINUTE);
        when(template.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(expected));

        assertThat(repository.findPointMinute("POINT_Q0", MINUTE)).contains(expected);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(template).query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("point_id='POINT_Q0'")
                .contains("AND ts=");
    }

    @Test
    @SuppressWarnings("unchecked")
    void findsPointRangeAndTaskRowsWithOneQueryAndHalfOpenBounds() {
        when(template.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

        repository.findRange(Set.of("POINT_Q0", "POINT_Q1"), MINUTE, MINUTE + 120_000L);
        repository.findByQualityTaskId("TASK'01");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(template, times(2)).query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getAllValues().get(0))
                .contains("point_id IN ('POINT_Q0','POINT_Q1')")
                .contains("ts>=")
                .contains("ts<");
        assertThat(sql.getAllValues().get(1))
                .contains("quality_task_id='TASK''01'");
    }

    @Test
    @SuppressWarnings("unchecked")
    void scopesTaskClosingQueryAndLateQ0CandidatesInTdengine() {
        when(template.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of());

        repository.findByQualityTaskId(
                "TASK'01", "POINT'01", MINUTE, MINUTE + 3_600_000L, 61);
        repository.findLateRealMinutes(
                MINUTE,
                MINUTE + 3_600_000L,
                MINUTE,
                "POINT'00",
                30,
                100);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(template, times(2)).query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getAllValues().get(0))
                .contains("quality_task_id='TASK''01'")
                .contains("point_id='POINT''01'")
                .contains("ts>=", "ts<", "LIMIT 61");
        assertThat(sql.getAllValues().get(1))
                .contains("point_id > 'POINT''00'")
                .contains("data_quality=0")
                .contains("finalized_at > ts + 90s")
                .contains("ORDER BY ts,point_id")
                .contains("LIMIT 100");
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletesOnlyMinuteStillOwnedByTask() {
        when(template.query(anyString(), any(RowMapper.class))).thenReturn(List.of(
                generatedAggregateAt(
                        "POINT_Q1", 1, "TASK_Q1", 19.0, MINUTE)));

        boolean deleted =
                repository.deleteIfOwnedByTask("POINT_Q1", MINUTE, "TASK_Q1");

        assertThat(deleted).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(template).execute(sql.capture());
        assertThat(sql.getValue())
                .contains("DELETE FROM iot_telemetry.st_raw_minute_POINT_Q1")
                .contains("WHERE ts=")
                .doesNotContain("quality_task_id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotDeleteMinuteAlreadyOwnedByAnotherTask() {
        when(template.query(anyString(), any(RowMapper.class))).thenReturn(List.of(
                generatedAggregateAt(
                        "POINT_Q1", 1, "NEW_TASK", 19.0, MINUTE)));

        boolean deleted =
                repository.deleteIfOwnedByTask("POINT_Q1", MINUTE, "OLD_TASK");

        assertThat(deleted).isFalse();
        verify(template, never()).execute(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsLatestRowsForAllPointIdsInOnePartitionedQuery() {
        when(template.query(anyString(), any(RowMapper.class)))
                .thenReturn(List.of(row("POINT001", MINUTE, 12.3)));

        List<HvacMinuteQueryRow> result =
                repository.findLatestByPointIds(List.of("POINT001", "POINT002"));

        assertThat(result).extracting(HvacMinuteQueryRow::pointId)
                .containsExactly("POINT001");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(template).query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("point_id IN ('POINT001','POINT002')")
                .contains("LAST_ROW(ts) AS bucket_time")
                .contains("LAST_ROW(avg_val) AS average_value")
                .contains("LAST_ROW(min_val) AS minimum_value")
                .contains("LAST_ROW(max_val) AS maximum_value")
                .contains("LAST_ROW(sample_count) AS sample_count")
                .contains("LAST_ROW(data_quality) AS data_quality")
                .contains("PARTITION BY point_id, point_code, building_id, system_group_id,")
                .contains("equip_id, equip_code, family_code, component_code,")
                .contains("suffix_code, is_for_calc")
                .doesNotContain("ORDER BY ts DESC")
                .doesNotContain("LIMIT 1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsOneMinuteHistoryWithoutDownsampling() {
        when(template.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

        repository.findHistory(
                List.of("POINT001", "POINT002"), MINUTE, MINUTE + 3_600_000L, 1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(template).query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("ts>=")
                .contains("ts<")
                .contains("ORDER BY point_id,ts")
                .doesNotContain("INTERVAL(");
    }

    @Test
    @SuppressWarnings("unchecked")
    void downsamplesHistoryWithWeightedAverageAndWorstQuality() {
        when(template.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

        repository.findHistory(
                List.of("POINT001"), MINUTE, MINUTE + 604_800_000L, 30);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(template).query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("_wstart AS bucket_time")
                .contains("SUM(avg_val * CASE WHEN sample_count > 0 THEN sample_count ELSE 1 END)")
                .contains("/ SUM(CASE WHEN sample_count > 0 THEN sample_count ELSE 1 END)")
                .contains("MIN(min_val)")
                .contains("MAX(max_val)")
                .contains("SUM(sample_count)")
                .contains("MAX(data_quality)")
                .contains("PARTITION BY point_id")
                .contains("INTERVAL(30m)");
    }

    @Test
    void emptyQueryPointListDoesNotTouchTdengine() {
        assertThat(repository.findLatestByPointIds(List.of())).isEmpty();
        assertThat(repository.findHistory(
                List.of(), MINUTE, MINUTE + 60_000L, 1)).isEmpty();

        verifyNoInteractions(template);
    }

    @Test
    void rejectsUnsupportedResolutionBeforeQuerying() {
        assertThatThrownBy(() -> repository.findHistory(
                List.of("POINT001"), MINUTE, MINUTE + 60_000L, 15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1、5、30");

        verifyNoInteractions(template);
    }

    @Test
    @SuppressWarnings("unchecked")
    void escapesPointIdsAsSqlValues() {
        when(template.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

        repository.findLatestByPointIds(List.of("POINT'001"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(template).query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getValue()).contains("'POINT''001'");
    }

    private RawMinuteAggregate aggregate(
            String pointCode, String equipId, String suffixCode, double average) {
        return new RawMinuteAggregate(
                pointCode.startsWith("DBO") ? "POINT_DBO" : "POINT_WCR",
                pointCode, "BLD001", "GROUP001",
                equipId, equipId, pointCode.startsWith("DBO") ? "RHO" : "WCR",
                "MAIN", suffixCode, 1,
                MINUTE, average, average - 0.5, average + 0.5,
                3, 0, MINUTE + 1_000L, MINUTE + 50_000L,
                MINUTE + 90_000L, null);
    }

    private RawMinuteAggregate generatedAggregate(int quality, String taskId, double average) {
        return generatedAggregateAt("POINT_WCR", quality, taskId, average, MINUTE);
    }

    private RawMinuteAggregate generatedAggregateAt(
            String pointId, int quality, String taskId, double average, long minute) {
        return new RawMinuteAggregate(
                pointId, pointId, "BLD001", "GROUP001", "WCR1", "WCR1",
                "WCR", "MAIN", "TWin", 1, minute, average, average, average,
                0, quality, null, null, minute + 90_000L, taskId);
    }

    private RawMinuteAggregate realAggregateAt(String pointId, double average, long minute) {
        return new RawMinuteAggregate(
                pointId, pointId, "BLD001", "GROUP001", "WCR1", "WCR1",
                "WCR", "MAIN", "TWin", 1, minute, average, average, average,
                1, 0, minute + 1_000L, minute + 2_000L, minute + 90_000L, null);
    }

    private HvacMinuteQueryRow row(String pointId, long time, double average) {
        return new HvacMinuteQueryRow(
                pointId, time, average, average - 0.5, average + 0.5,
                6L, 0);
    }
}
