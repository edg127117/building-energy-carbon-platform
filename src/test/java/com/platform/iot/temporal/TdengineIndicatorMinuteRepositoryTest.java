package com.platform.iot.temporal;

import com.platform.config.TdengineProperties;
import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.formula.model.FormulaCalculationException;
import com.platform.iot.formula.model.IndicatorMinuteKey;
import com.platform.iot.formula.model.IndicatorMinuteResult;
import com.platform.iot.formula.model.FormulaCalculationAttempt;
import com.platform.iot.formula.model.IndicatorMinuteState;
import com.platform.iot.temporal.impl.TdengineIndicatorMinuteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TdengineIndicatorMinuteRepositoryTest {

    private static final long MINUTE = 1_800_000_000_000L;

    private JdbcTemplate template;
    private TdengineIndicatorMinuteRepository repository;

    @BeforeEach
    void setUp() {
        template = mock(JdbcTemplate.class);
        TdengineProperties properties = new TdengineProperties();
        properties.setDatabase("iot_telemetry");
        repository = new TdengineIndicatorMinuteRepository(template, properties);
    }

    @Test
    void writesOneMultiChildInsertUsingSourceMinuteAndEscapedValues() {
        repository.saveSuccesses(List.of(
                new IndicatorMinuteResult(
                        "INDICATOR_WCR_COP_B1", "WCR_COP", "BLD001", "GROUP001",
                        "EQUIP_WCR_B1", MINUTE, 5.805555555556, 1,
                        "WCR_COP_V1", MINUTE + 90_000L),
                new IndicatorMinuteResult(
                        "INDICATOR_AHU_OHARE", "AHU'POWER", "BLD001", null,
                        null, MINUTE, 1.25, 0, "AHU_V1", MINUTE + 91_000L)));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(template).execute(sqlCaptor.capture());
        String insertSql = sqlCaptor.getValue();
        assertThat(insertSql)
                .contains("st_indicator_minute_INDICATOR_WCR_COP_B1")
                .contains("VALUES ('" + new Timestamp(MINUTE))
                .contains("5.805555555556")
                .contains("WCR_COP_V1")
                .contains("AHU''POWER")
                .doesNotContain(Long.toString(System.currentTimeMillis()));
    }

    @Test
    void propagatesTdengineWriteFailures() {
        doThrow(new DataAccessResourceFailureException("tdengine unavailable"))
                .when(template).execute(anyString());

        assertThatThrownBy(() -> repository.saveSuccesses(List.of(
                new IndicatorMinuteResult(
                        "INDICATOR_WCR_COP_B1", "WCR_COP", "BLD001", "GROUP001",
                        "EQUIP_WCR_B1", MINUTE, 5.8, 1, "WCR_COP_V1", MINUTE))))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void preservesSmallFiniteIndicatorValuesWithoutRoundingToZero() {
        repository.saveSuccesses(List.of(new IndicatorMinuteResult(
                "INDICATOR_WCR_COP_B1", "WCR_COP", "BLD001", "GROUP001",
                "EQUIP_WCR_B1", MINUTE, 1.0e-13, 1,
                "WCR_COP_V1", MINUTE + 90_000L)));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(template).execute(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("1.0E-13")
                .doesNotContain("0.000000000000");
    }

    @Test
    void rejectsNonFiniteValuesBeforeCallingTdengine() {
        IndicatorMinuteResult invalid = new IndicatorMinuteResult(
                "INDICATOR_WCR_COP_B1", "WCR_COP", "BLD001", "GROUP001",
                "EQUIP_WCR_B1", MINUTE, Double.NaN, 1, "WCR_COP_V1", MINUTE);

        assertThatThrownBy(() -> repository.saveSuccesses(List.of(invalid)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(template);
    }

    @Test
    void rejectsUnsafeChildTableIdentifiersBeforeCallingTdengine() {
        IndicatorMinuteResult invalid = new IndicatorMinuteResult(
                "INDICATOR;DROP", "WCR_COP", "BLD001", "GROUP001",
                "EQUIP_WCR_B1", MINUTE, 5.8, 1, "WCR_COP_V1", MINUTE);

        assertThatThrownBy(() -> repository.saveSuccesses(List.of(invalid)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(template);
    }

    @Test
    void writesFormulaExceptionWithStableCodesAndJoinedMissingInputs() {
        repository.saveExceptions(List.of(new FormulaCalculationException(
                "INDICATOR_WCR_COP_B1", "WCR_COP", "BLD001", "GROUP001",
                "EQUIP_WCR_B1", MINUTE, FormulaCalculation.Status.MISSING_INPUT,
                "MISSING_REQUIRED_INPUT", List.of("CHILLER.OUTLET_TEMP", "CHILLER'POWER"),
                "WCR_COP_V1", MINUTE + 90_000L)));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(template).execute(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("st_formula_calc_exception")
                .contains("MISSING_INPUT")
                .contains("MISSING_REQUIRED_INPUT")
                .contains("CHILLER.OUTLET_TEMP,CHILLER''POWER")
                .contains("VALUES ('" + new Timestamp(MINUTE));
    }

    @Test
    void emptyBatchesDoNotTouchTdengine() {
        repository.saveSuccesses(List.of());
        repository.saveExceptions(List.of());
        repository.deleteSuccesses(Set.of());
        verifyNoInteractions(template);
    }

    @Test
    void batchDeleteOnlyTargetsExplicitIndicatorMinuteKeys() {
        long nextMinute = MINUTE + 60_000L;

        repository.deleteSuccesses(Set.of(
                new IndicatorMinuteKey("INDICATOR_A", MINUTE),
                new IndicatorMinuteKey("INDICATOR_B", nextMinute)));

        ArgumentCaptor<String[]> sqlCaptor = ArgumentCaptor.forClass(String[].class);
        verify(template).batchUpdate(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue()).containsExactly(
                "DELETE FROM iot_telemetry.st_indicator_minute_INDICATOR_A"
                        + " WHERE ts='" + new Timestamp(MINUTE) + "'",
                "DELETE FROM iot_telemetry.st_indicator_minute_INDICATOR_B"
                        + " WHERE ts='" + new Timestamp(nextMinute) + "'");
        assertThat(sqlCaptor.getValue())
                .allSatisfy(sql -> assertThat(sql)
                        .doesNotContain("building_id")
                        .doesNotContain("ts>="));
    }

    @Test
    void rejectsUnsafeDeleteIdentifierBeforeCallingTdengine() {
        assertThatThrownBy(() -> repository.deleteSuccesses(Set.of(
                new IndicatorMinuteKey("INDICATOR;DROP", MINUTE))))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(template);
    }

    @Test
    void latestSuccessAndExceptionUseLastRowPerCompleteIndicatorIdentity() {
        when(template.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());

        repository.findLatestSuccesses(List.of("INDICATOR_B", "INDICATOR_A"));
        repository.findLatestExceptions(List.of("INDICATOR_B", "INDICATOR_A"));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(template, times(2)).query(
                sqlCaptor.capture(),
                any(org.springframework.jdbc.core.RowMapper.class));
        String successSql = sqlCaptor.getAllValues().get(0);
        String exceptionSql = sqlCaptor.getAllValues().get(1);
        String identityPartition = "PARTITION BY indicator_id,indicator_code,"
                + "building_id,system_group_id,equip_id";

        assertThat(successSql)
                .contains("st_indicator_minute")
                .doesNotContain("st_formula_calc_exception")
                .contains(
                        "LAST_ROW(ts) AS ts",
                        "LAST_ROW(val) AS val",
                        "LAST_ROW(data_quality) AS data_quality",
                        "LAST_ROW(formula_version) AS formula_version",
                        "LAST_ROW(calculated_at) AS calculated_at",
                        identityPartition)
                .doesNotContain("ORDER BY ts DESC LIMIT 1");
        assertThat(exceptionSql)
                .contains("st_formula_calc_exception")
                .doesNotContain("FROM iot_telemetry.st_indicator_minute")
                .contains(
                        "LAST_ROW(ts) AS ts",
                        "LAST_ROW(calc_status) AS calc_status",
                        "LAST_ROW(reason_code) AS reason_code",
                        "LAST_ROW(missing_inputs) AS missing_inputs",
                        "LAST_ROW(formula_version) AS formula_version",
                        "LAST_ROW(calculated_at) AS calculated_at",
                        identityPartition)
                .doesNotContain("ORDER BY ts DESC");
    }

    @Test
    void emptyLatestIndicatorIdsDoNotTouchTdengine() {
        assertThat(repository.findLatestSuccesses(List.of())).isEmpty();
        assertThat(repository.findLatestExceptions(List.of())).isEmpty();
        verifyNoInteractions(template);
    }

    @Test
    void latestResultsAreSortedDeterministicallyByIndicatorId() {
        IndicatorMinuteResult successA = new IndicatorMinuteResult(
                "INDICATOR_A", "A", "BLD001", null, null,
                MINUTE, 1.0, 1, "A_V1", MINUTE);
        IndicatorMinuteResult successB = new IndicatorMinuteResult(
                "INDICATOR_B", "B", "BLD001", null, null,
                MINUTE, 2.0, 1, "B_V1", MINUTE);
        FormulaCalculationException exceptionA = new FormulaCalculationException(
                "INDICATOR_A", "A", "BLD001", null, null,
                MINUTE, FormulaCalculation.Status.MISSING_INPUT,
                "MISSING_A", List.of("A.INPUT"), "A_V1", MINUTE);
        FormulaCalculationException exceptionB = new FormulaCalculationException(
                "INDICATOR_B", "B", "BLD001", null, null,
                MINUTE, FormulaCalculation.Status.INVALID_INPUT,
                "INVALID_B", List.of(), "B_V1", MINUTE);
        when(template.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(successB, successA))
                .thenReturn(List.of(exceptionB, exceptionA));

        assertThat(repository.findLatestSuccesses(List.of("INDICATOR_B", "INDICATOR_A")))
                .extracting(IndicatorMinuteResult::indicatorId)
                .containsExactly("INDICATOR_A", "INDICATOR_B");
        assertThat(repository.findLatestExceptions(List.of("INDICATOR_B", "INDICATOR_A")))
                .extracting(FormulaCalculationException::indicatorId)
                .containsExactly("INDICATOR_A", "INDICATOR_B");
    }

    @Test
    void oneMinuteTrendsReturnExactSuccessfulRowsForAllRequestedIndicators() {
        when(template.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());

        repository.findTrends(
                List.of("INDICATOR_B", "INDICATOR_A"),
                MINUTE,
                MINUTE + 3_600_000L,
                1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(template).query(sql.capture(), any(org.springframework.jdbc.core.RowMapper.class));
        assertThat(sql.getValue())
                .contains("indicator_id IN ('INDICATOR_A','INDICATOR_B')")
                .contains("ts AS bucket_time", "val AS average_value")
                .contains("val AS minimum_value", "val AS maximum_value")
                .contains("1 AS sample_count", "data_quality")
                .doesNotContain("INTERVAL(")
                .contains("ORDER BY indicator_id,ts");
    }

    @Test
    void fiveMinuteTrendsAggregatePerIndicatorAndKeepWorstQuality() {
        when(template.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());

        repository.findTrends(
                List.of("INDICATOR_A", "INDICATOR_B"),
                MINUTE,
                MINUTE + 86_400_001L,
                5);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(template).query(sql.capture(), any(org.springframework.jdbc.core.RowMapper.class));
        assertThat(sql.getValue())
                .contains("AVG(val) AS average_value")
                .contains("MIN(val) AS minimum_value")
                .contains("MAX(val) AS maximum_value")
                .contains("COUNT(*) AS sample_count")
                .contains("MAX(data_quality) AS data_quality")
                .contains("PARTITION BY indicator_id,indicator_code,building_id,system_group_id,equip_id")
                .contains("INTERVAL(5m)")
                .contains("ORDER BY indicator_id,_wstart");
    }

    @Test
    void trendsRejectUnsupportedResolutionAndSkipEmptyIds() {
        assertThat(repository.findTrends(List.of(), MINUTE, MINUTE + 60_000L, 1))
                .isEmpty();
        assertThatThrownBy(() -> repository.findTrends(
                List.of("INDICATOR_A"), MINUTE, MINUTE + 60_000L, 15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1、5、30");
        verifyNoInteractions(template);
    }

    @Test
    void successfulKeysAreReadWithOneStableQuery() {
        when(template.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(new IndicatorMinuteKey("INDICATOR_A", MINUTE)));

        assertThat(repository.findSuccessfulKeys(
                List.of("INDICATOR_B", "INDICATOR_A"), MINUTE, MINUTE + 60_000L))
                .containsExactly(new IndicatorMinuteKey("INDICATOR_A", MINUTE));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(template).query(sqlCaptor.capture(), any(org.springframework.jdbc.core.RowMapper.class));
        assertThat(sqlCaptor.getValue())
                .contains("st_indicator_minute")
                .contains("INDICATOR_A", "INDICATOR_B")
                .contains("ORDER BY indicator_id, ts");
    }

    @Test
    void latestAttemptTimeReadsSuccessExceptionAndAppendOnlyAttemptTables() {
        doNothing().when(template).query(
                anyString(), any(RowCallbackHandler.class));

        assertThat(repository.findLatestAttemptAt(Set.of(
                new IndicatorMinuteKey("INDICATOR_A", MINUTE))))
                .isEqualTo(Map.of());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(template, times(3)).query(
                sql.capture(), any(RowCallbackHandler.class));
        assertThat(sql.getAllValues().get(0))
                .contains("st_indicator_minute")
                .contains("indicator_id='INDICATOR_A'")
                .contains("calculated_at");
        assertThat(sql.getAllValues().get(1))
                .contains("st_formula_calc_exception")
                .contains("indicator_id='INDICATOR_A'")
                .contains("calculated_at");
        assertThat(sql.getAllValues().get(2))
                .contains("st_formula_calc_attempt_v2")
                .contains("minute_start")
                .contains("indicator_id='INDICATOR_A'");
    }

    @Test
    void savesAppendOnlyAttemptsAndCurrentProjectionToSeparateStables() {
        when(template.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        repository.saveAttempts(List.of(new FormulaCalculationAttempt(
                "ATTEMPT001", "INDICATOR_A", "WCR_COP", "BLD001",
                "GROUP001", "EQUIP001", MINUTE, MINUTE + 1_000L,
                "QUALITY_NOT_ALLOWED", "QUALITY_NOT_ALLOWED",
                "INDICATOR_CALCULATION", "HVAC_FORMULA_V1", "[]", 8)));
        repository.saveStates(List.of(new IndicatorMinuteState(
                "INDICATOR_A", "WCR_COP", "BLD001", "GROUP001", "EQUIP001",
                MINUTE, "QUALITY_NOT_ALLOWED", null, "ATTEMPT001",
                MINUTE + 1_000L, 8)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(template, times(2)).execute(sql.capture());
        assertThat(sql.getAllValues().get(0))
                .contains("st_formula_calc_attempt_v2_INDICATOR_A")
                .contains("attempt_id", "minute_start", "config_revision")
                .contains("ATTEMPT001", "QUALITY_NOT_ALLOWED");
        assertThat(sql.getAllValues().get(1))
                .contains("st_indicator_minute_state_INDICATOR_A")
                .contains("current_status", "state_updated_at", "config_revision");
    }

    @Test
    void latestStateQueryUsesFullIdentityPartition() {
        IndicatorMinuteState state = new IndicatorMinuteState(
                "INDICATOR_A", "WCR_COP", "BLD001", "GROUP001", "EQUIP001",
                MINUTE, "QUALITY_NOT_ALLOWED", null, "ATTEMPT001",
                MINUTE + 1_000L, 8);
        when(template.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(state));

        assertThat(repository.findLatestStates(List.of("INDICATOR_A")))
                .containsEntry("INDICATOR_A", state);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(template).query(sql.capture(), any(org.springframework.jdbc.core.RowMapper.class));
        assertThat(sql.getValue())
                .contains("LAST_ROW(ts) AS ts")
                .contains("LAST_ROW(current_status) AS current_status")
                .contains("indicator_id IN ('INDICATOR_A')")
                .contains("PARTITION BY indicator_id,indicator_code,building_id,system_group_id,equip_id");
    }
}
