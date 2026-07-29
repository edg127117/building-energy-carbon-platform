package com.platform.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.formula.model.IndicatorLatestState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IndicatorLatestCacheServiceTest {

    private static final long MINUTE = 1_800_000_000_000L;
    private static final String KEY = "iot:indicator:latest:IND001";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private IndicatorLatestCacheService cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        cache = new IndicatorLatestCacheService(redis, objectMapper);
    }

    @Test
    void jsonRoundTripKeepsCalculationDetails() throws Exception {
        IndicatorLatestState state = success("IND001", MINUTE, 5.8);
        when(valueOps.get(KEY)).thenReturn(objectMapper.writeValueAsString(state));

        Optional<IndicatorLatestState> cached = cache.get("IND001");
        assertThat(cached).contains(state);
        assertThat(cached.orElseThrow().inputs())
                .containsExactlyElementsOf(state.inputs());
        assertThat(cached.orElseThrow().steps())
                .containsExactlyElementsOf(state.steps());
        assertThat(cached.orElseThrow().missingInputs())
                .containsExactlyElementsOf(state.missingInputs());
        assertThat(cached.orElseThrow().dataQuality()).isZero();
    }

    @Test
    void writesIndicatorIdKeyAndOneHundredTwentySecondTtl() {
        when(valueOps.get(KEY)).thenReturn(null);

        assertThat(cache.setIfNotOlder(success("IND001", MINUTE, 5.8))).isTrue();

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(KEY), json.capture(), eq(Duration.ofSeconds(120)));
        assertThat(json.getValue()).contains("\"indicatorId\":\"IND001\"");
    }

    @Test
    void serializesCompareAndSetWithinOneApplicationInstance() throws Exception {
        int modifiers = IndicatorLatestCacheService.class
                .getMethod("setIfNotOlder", IndicatorLatestState.class)
                .getModifiers();

        assertThat(Modifier.isSynchronized(modifiers)).isTrue();
    }

    @Test
    void newerMinuteReplacesOlderState() throws Exception {
        when(valueOps.get(KEY)).thenReturn(objectMapper.writeValueAsString(
                success("IND001", MINUTE, 5.8)));

        assertThat(cache.setIfNotOlder(
                success("IND001", MINUTE + 60_000L, 5.9))).isTrue();

        verify(valueOps).set(eq(KEY), anyString(), eq(Duration.ofSeconds(120)));
    }

    @Test
    void newerFailureReplacesOlderSuccess() throws Exception {
        when(valueOps.get(KEY)).thenReturn(objectMapper.writeValueAsString(
                success("IND001", MINUTE, 5.8)));

        assertThat(cache.setIfNotOlder(
                failure("IND001", MINUTE + 60_000L))).isTrue();

        verify(valueOps).set(eq(KEY), anyString(), eq(Duration.ofSeconds(120)));
    }

    @Test
    void olderRecoveryDoesNotOverwriteNewerState() throws Exception {
        when(valueOps.get(KEY)).thenReturn(objectMapper.writeValueAsString(
                success("IND001", MINUTE + 60_000L, 5.9)));

        assertThat(cache.setIfNotOlder(
                success("IND001", MINUTE, 5.8))).isFalse();

        verify(valueOps, never()).set(eq(KEY), anyString(), any(Duration.class));
    }

    @Test
    void equalMinuteSuccessReplacesFailure() throws Exception {
        when(valueOps.get(KEY)).thenReturn(objectMapper.writeValueAsString(
                failure("IND001", MINUTE)));

        assertThat(cache.setIfNotOlder(success("IND001", MINUTE, 5.8))).isTrue();

        verify(valueOps).set(eq(KEY), anyString(), eq(Duration.ofSeconds(120)));
    }

    @Test
    void equalMinuteFailureDoesNotReplaceSuccess() throws Exception {
        when(valueOps.get(KEY)).thenReturn(objectMapper.writeValueAsString(
                success("IND001", MINUTE, 5.8)));

        assertThat(cache.setIfNotOlder(failure("IND001", MINUTE))).isFalse();

        verify(valueOps, never()).set(eq(KEY), anyString(), any(Duration.class));
    }

    @Test
    void authoritativeCorrectionAllowsEqualMinuteFailureToReplaceSuccess()
            throws Exception {
        when(valueOps.get(KEY)).thenReturn(objectMapper.writeValueAsString(
                success("IND001", MINUTE, 5.8)));

        assertThat(cache.setIfNotOlder(
                failure("IND001", MINUTE), true)).isTrue();

        verify(valueOps).set(eq(KEY), anyString(), eq(Duration.ofSeconds(120)));
    }

    @Test
    void authoritativeCorrectionStillRejectsOlderMinute() throws Exception {
        when(valueOps.get(KEY)).thenReturn(objectMapper.writeValueAsString(
                success("IND001", MINUTE + 60_000L, 5.9)));

        assertThat(cache.setIfNotOlder(
                failure("IND001", MINUTE), true)).isFalse();

        verify(valueOps, never()).set(eq(KEY), anyString(), any(Duration.class));
    }

    @Test
    void redisDataAccessFailuresAreCacheMissesAndRejectedWrites() {
        when(valueOps.get(KEY)).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(cache.get("IND001")).isEmpty();
        assertThat(cache.setIfNotOlder(success("IND001", MINUTE, 5.8))).isFalse();
        verify(valueOps, never()).set(eq(KEY), anyString(), any(Duration.class));
    }

    @Test
    void redisWriteFailureReturnsFalse() {
        when(valueOps.get(KEY)).thenReturn(null);
        doThrow(new DataAccessResourceFailureException("down"))
                .when(valueOps).set(eq(KEY), anyString(), eq(Duration.ofSeconds(120)));

        assertThat(cache.setIfNotOlder(success("IND001", MINUTE, 5.8))).isFalse();
    }

    private static IndicatorLatestState success(String indicatorId, long minute, double value) {
        return new IndicatorLatestState(
                indicatorId, "WCR_COP", "BLD001", "EQUIP001", minute,
                FormulaCalculation.Status.SUCCESS, value, 0, "WCR_COP_V1", null,
                List.of(),
                List.of(new FormulaCalculation.Input(
                        "MAIN/GW", "POINT001", "WCR1_GW", 500.0, "m3/h", 0)),
                List.of(new FormulaCalculation.Step(
                        "Q0", "GW * rho * c * deltaT / 3600", 580.0, "kW")));
    }

    private static IndicatorLatestState failure(String indicatorId, long minute) {
        return new IndicatorLatestState(
                indicatorId, "WCR_COP", "BLD001", "EQUIP001", minute,
                FormulaCalculation.Status.MISSING_INPUT, null, null, "WCR_COP_V1",
                "MISSING_REQUIRED_INPUT", List.of("MAIN/PPE"), List.of(), List.of());
    }
}
