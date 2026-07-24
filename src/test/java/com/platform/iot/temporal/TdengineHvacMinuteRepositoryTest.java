package com.platform.iot.temporal;

import com.platform.config.TdengineProperties;
import com.platform.iot.temporal.impl.TdengineHvacMinuteRepository;
import com.platform.iot.temporal.model.HvacMinuteQueryRow;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

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
        repository.saveAll(List.of(
                aggregate("WCR1_TWin", "WCR1", "TWin", 12.3),
                aggregate("DBO_RH", null, "RH", 60.0)));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(template, atLeastOnce()).execute(sqlCaptor.capture());
        List<String> inserts = sqlCaptor.getAllValues().stream()
                .filter(sql -> sql.startsWith("INSERT INTO")).toList();

        assertThat(inserts).singleElement().satisfies(sql -> assertThat(sql)
                .contains("st_raw_minute_POINT_WCR")
                .contains("st_raw_minute_POINT_DBO")
                .contains("12.300000000000")
                .contains("60.000000000000"));
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
    void emptyBatchDoesNotTouchTdengine() {
        repository.saveAll(List.of());

        verifyNoInteractions(template);
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
                .contains("PARTITION BY point_id")
                .contains("ORDER BY ts DESC")
                .contains("LIMIT 1");
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
                .contains("SUM(avg_val*sample_count)/SUM(sample_count)")
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
                MINUTE + 90_000L);
    }

    private HvacMinuteQueryRow row(String pointId, long time, double average) {
        return new HvacMinuteQueryRow(
                pointId, time, average, average - 0.5, average + 0.5,
                6L, 0);
    }
}
