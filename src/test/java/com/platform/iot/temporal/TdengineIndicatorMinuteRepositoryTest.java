package com.platform.iot.temporal;

import com.platform.config.TdengineProperties;
import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.formula.model.FormulaCalculationException;
import com.platform.iot.formula.model.IndicatorMinuteKey;
import com.platform.iot.formula.model.IndicatorMinuteResult;
import com.platform.iot.temporal.impl.TdengineIndicatorMinuteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

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
        verifyNoInteractions(template);
    }

    @Test
    void latestSuccessAndExceptionUseSeparateStableQueries() {
        when(template.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());

        repository.findLatestSuccesses(List.of("INDICATOR_B", "INDICATOR_A"));
        repository.findLatestExceptions(List.of("INDICATOR_B", "INDICATOR_A"));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(template, times(2)).query(sqlCaptor.capture(), any(org.springframework.jdbc.core.RowMapper.class));
        assertThat(sqlCaptor.getAllValues().get(0)).contains("st_indicator_minute").doesNotContain("st_formula_calc_exception");
        assertThat(sqlCaptor.getAllValues().get(1)).contains("st_formula_calc_exception").doesNotContain("st_indicator_minute");
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
}
