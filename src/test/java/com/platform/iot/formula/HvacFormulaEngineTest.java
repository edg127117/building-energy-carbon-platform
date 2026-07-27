package com.platform.iot.formula;

import com.platform.cache.IndicatorLatestCacheService;
import com.platform.hvac.model.entity.BizIndicator;
import com.platform.iot.aggregation.HvacMinuteBatchFrozenEvent;
import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.formula.model.FormulaCalculationException;
import com.platform.iot.formula.model.IndicatorLatestState;
import com.platform.iot.formula.model.IndicatorMinuteResult;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.IndicatorMinuteRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HvacFormulaEngineTest {

    private static final long MINUTE = 1_800_000_000_000L;
    private static final long FINALIZED_AT = MINUTE + 12_345L;

    private final IndicatorConfigProvider configProvider = mock(IndicatorConfigProvider.class);
    private final HvacMinuteRepository minuteRepository = mock(HvacMinuteRepository.class);
    private final IndicatorMinuteRepository indicatorRepository =
            mock(IndicatorMinuteRepository.class);
    private final IndicatorLatestCacheService cache = mock(IndicatorLatestCacheService.class);
    private final IndicatorRealtimePublisher publisher = mock(IndicatorRealtimePublisher.class);
    private final FormulaInputAssembler assembler = mock(FormulaInputAssembler.class);

    @Test
    void isConditionalSynchronousSpringEventListener() throws Exception {
        assertThat(HvacFormulaEngine.class).hasAnnotation(Component.class);
        ConditionalOnProperty condition =
                HvacFormulaEngine.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition.prefix()).isEqualTo("formula");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();

        Method listener = HvacFormulaEngine.class.getMethod(
                "onMinuteFrozen", HvacMinuteBatchFrozenEvent.class);
        assertThat(listener.getAnnotation(EventListener.class)).isNotNull();
        assertThat(listener.getAnnotation(Async.class)).isNull();
    }

    @Test
    void rejectsDuplicateIndicatorCodesAtConstruction() {
        IndicatorFormula first = formula("WCR_COP", "V1");
        IndicatorFormula duplicate = formula("WCR_COP", "V2");

        assertThatThrownBy(() -> engine(List.of(first, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WCR_COP");
    }

    @Test
    void normalEventUsesPayloadWithoutQueryingFrozenMinutes() {
        RawMinuteAggregate payload = aggregate("P1", "BLD001");
        BizIndicator indicator = indicator("I1", "WCR_COP", "BLD001");
        IndicatorFormula formula = formula("WCR_COP", "V1");
        FormulaInputs inputs = new FormulaInputs(List.of());
        when(configProvider.findAllActive()).thenReturn(List.of(indicator));
        when(assembler.assemble(indicator, MINUTE, List.of(payload))).thenReturn(inputs);
        when(formula.calculate(inputs)).thenReturn(success("WCR_COP", "V1", 4.2));

        engine(List.of(formula)).onMinuteFrozen(
                new HvacMinuteBatchFrozenEvent(
                        MINUTE, FINALIZED_AT, false, List.of(payload)));

        verify(minuteRepository, never()).findByMinute(any(Long.class), any());
        verify(assembler).assemble(indicator, MINUTE, List.of(payload));
    }

    @Test
    void recoveryEventRequeriesCompleteMinuteForPayloadBuildingIds() {
        RawMinuteAggregate triggerOne = aggregate("P1", "BLD001");
        RawMinuteAggregate triggerTwo = aggregate("P2", "BLD002");
        RawMinuteAggregate recovered = aggregate("P3", "BLD001");
        BizIndicator indicator = indicator("I1", "WCR_COP", "BLD001");
        IndicatorFormula formula = formula("WCR_COP", "V1");
        FormulaInputs inputs = new FormulaInputs(List.of());
        when(minuteRepository.findByMinute(
                MINUTE, Set.of("BLD001", "BLD002"))).thenReturn(List.of(recovered));
        when(configProvider.findAllActive()).thenReturn(List.of(indicator));
        when(assembler.assemble(indicator, MINUTE, List.of(recovered))).thenReturn(inputs);
        when(formula.calculate(inputs)).thenReturn(success("WCR_COP", "V1", 4.2));

        engine(List.of(formula)).onMinuteFrozen(
                new HvacMinuteBatchFrozenEvent(
                        MINUTE, FINALIZED_AT, true, List.of(triggerOne, triggerTwo)));

        verify(minuteRepository).findByMinute(
                MINUTE, Set.of("BLD001", "BLD002"));
        verify(assembler).assemble(indicator, MINUTE, List.of(recovered));
    }

    @Test
    void selectsOnlyActiveIndicatorsForAffectedBuildings() {
        RawMinuteAggregate aggregate = aggregate("P1", "BLD001");
        BizIndicator affected = indicator("I1", "WCR_COP", "BLD001");
        BizIndicator unaffected = indicator("I2", "WCR_COP", "BLD002");
        IndicatorFormula formula = formula("WCR_COP", "V1");
        FormulaInputs inputs = new FormulaInputs(List.of());
        when(configProvider.findAllActive()).thenReturn(List.of(affected, unaffected));
        when(assembler.assemble(affected, MINUTE, List.of(aggregate))).thenReturn(inputs);
        when(formula.calculate(inputs)).thenReturn(success("WCR_COP", "V1", 4.2));

        engine(List.of(formula)).onMinuteFrozen(
                new HvacMinuteBatchFrozenEvent(
                        MINUTE, FINALIZED_AT, false, List.of(aggregate)));

        verify(assembler).assemble(affected, MINUTE, List.of(aggregate));
        verify(assembler, never()).assemble(eq(unaffected), eq(MINUTE), any());
    }

    @Test
    void batchesRowsAndOrdersPersistenceBeforeCacheAndPublication() {
        RawMinuteAggregate aggregate = aggregate("P1", "BLD001");
        BizIndicator failing = indicator("I1", "PUMP_EFF", "BLD001");
        BizIndicator succeeding = indicator("I2", "WCR_COP", "BLD001");
        IndicatorFormula failureFormula = formula("PUMP_EFF", "PUMP_V1");
        IndicatorFormula successFormula = formula("WCR_COP", "COP_V1");
        FormulaInputs failureInputs = new FormulaInputs(List.of());
        FormulaInputs successInputs = new FormulaInputs(List.of());
        when(configProvider.findAllActive()).thenReturn(List.of(failing, succeeding));
        when(assembler.assemble(failing, MINUTE, List.of(aggregate)))
                .thenReturn(failureInputs);
        when(assembler.assemble(succeeding, MINUTE, List.of(aggregate)))
                .thenReturn(successInputs);
        when(failureFormula.calculate(failureInputs))
                .thenThrow(new IllegalStateException("bad strategy"));
        when(successFormula.calculate(successInputs))
                .thenReturn(success("WCR_COP", "COP_V1", 5.5));
        when(cache.setIfNotOlder(any())).thenReturn(true);

        engine(List.of(failureFormula, successFormula)).onMinuteFrozen(
                new HvacMinuteBatchFrozenEvent(
                        MINUTE, FINALIZED_AT, false, List.of(aggregate)));

        ArgumentCaptor<List<IndicatorMinuteResult>> successes = listCaptor();
        ArgumentCaptor<List<FormulaCalculationException>> failures = listCaptor();
        ArgumentCaptor<IndicatorLatestState> successState =
                ArgumentCaptor.forClass(IndicatorLatestState.class);
        ArgumentCaptor<IndicatorLatestState> failureState =
                ArgumentCaptor.forClass(IndicatorLatestState.class);
        InOrder ordered = inOrder(indicatorRepository, cache, publisher);
        ordered.verify(indicatorRepository).saveSuccesses(successes.capture());
        ordered.verify(cache).setIfNotOlder(successState.capture());
        ordered.verify(publisher).publish(successState.getValue());
        ordered.verify(indicatorRepository).saveExceptions(failures.capture());
        ordered.verify(cache).setIfNotOlder(failureState.capture());
        ordered.verify(publisher).publish(failureState.getValue());

        assertThat(successes.getValue()).singleElement().satisfies(row -> {
            assertThat(row.indicatorId()).isEqualTo("I2");
            assertThat(row.minuteStart()).isEqualTo(MINUTE);
            assertThat(row.calculatedAt()).isEqualTo(FINALIZED_AT);
        });
        assertThat(failures.getValue()).singleElement().satisfies(row -> {
            assertThat(row.indicatorId()).isEqualTo("I1");
            assertThat(row.minuteStart()).isEqualTo(MINUTE);
            assertThat(row.status()).isEqualTo(FormulaCalculation.Status.ENGINE_ERROR);
            assertThat(row.reasonCode()).isEqualTo("FORMULA_ENGINE_ERROR");
            assertThat(row.formulaVersion()).isEqualTo("PUMP_V1");
            assertThat(row.calculatedAt()).isEqualTo(FINALIZED_AT);
        });
        assertThat(successState.getValue().minuteStart()).isEqualTo(MINUTE);
        assertThat(failureState.getValue().minuteStart()).isEqualTo(MINUTE);
    }

    @Test
    void successRepositoryFailurePropagatesAndBlocksAllDownstreamEffects() {
        RawMinuteAggregate aggregate = aggregate("P1", "BLD001");
        BizIndicator indicator = indicator("I1", "WCR_COP", "BLD001");
        IndicatorFormula formula = formula("WCR_COP", "V1");
        FormulaInputs inputs = new FormulaInputs(List.of());
        when(configProvider.findAllActive()).thenReturn(List.of(indicator));
        when(assembler.assemble(indicator, MINUTE, List.of(aggregate))).thenReturn(inputs);
        when(formula.calculate(inputs)).thenReturn(success("WCR_COP", "V1", 4.2));
        doThrow(new IllegalStateException("tdengine unavailable"))
                .when(indicatorRepository).saveSuccesses(any());

        assertThatThrownBy(() -> engine(List.of(formula)).onMinuteFrozen(
                new HvacMinuteBatchFrozenEvent(
                        MINUTE, FINALIZED_AT, false, List.of(aggregate))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tdengine unavailable");

        verify(indicatorRepository, never()).saveExceptions(any());
        verifyNoInteractions(cache, publisher);
    }

    @Test
    void exceptionRepositoryFailurePropagatesAndBlocksFailureDownstreamEffects() {
        RawMinuteAggregate aggregate = aggregate("P1", "BLD001");
        BizIndicator indicator = indicator("I1", "PUMP_EFF", "BLD001");
        IndicatorFormula formula = formula("PUMP_EFF", "V1");
        FormulaInputs inputs = new FormulaInputs(List.of());
        when(configProvider.findAllActive()).thenReturn(List.of(indicator));
        when(assembler.assemble(indicator, MINUTE, List.of(aggregate))).thenReturn(inputs);
        when(formula.calculate(inputs)).thenReturn(new FormulaCalculation(
                FormulaCalculation.Status.INVALID_INPUT,
                "PUMP_EFF", "V1", null, null, List.of(), List.of(),
                "PUMP_INPUT_INVALID", List.of()));
        doThrow(new IllegalStateException("tdengine unavailable"))
                .when(indicatorRepository).saveExceptions(any());

        assertThatThrownBy(() -> engine(List.of(formula)).onMinuteFrozen(
                new HvacMinuteBatchFrozenEvent(
                        MINUTE, FINALIZED_AT, false, List.of(aggregate))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tdengine unavailable");

        verify(indicatorRepository, never()).saveSuccesses(any());
        verifyNoInteractions(cache, publisher);
    }

    @Test
    void malformedFormulaResultBecomesEngineErrorWithoutStoppingOtherIndicators() {
        RawMinuteAggregate aggregate = aggregate("P1", "BLD001");
        BizIndicator malformed = indicator("I1", "PUMP_EFF", "BLD001");
        BizIndicator valid = indicator("I2", "WCR_COP", "BLD001");
        IndicatorFormula malformedFormula = formula("PUMP_EFF", "PUMP_V1");
        IndicatorFormula validFormula = formula("WCR_COP", "COP_V1");
        FormulaInputs malformedInputs = new FormulaInputs(List.of());
        FormulaInputs validInputs = new FormulaInputs(List.of());
        when(configProvider.findAllActive()).thenReturn(List.of(malformed, valid));
        when(assembler.assemble(malformed, MINUTE, List.of(aggregate)))
                .thenReturn(malformedInputs);
        when(assembler.assemble(valid, MINUTE, List.of(aggregate)))
                .thenReturn(validInputs);
        when(malformedFormula.calculate(malformedInputs))
                .thenReturn(new FormulaCalculation(
                        FormulaCalculation.Status.SUCCESS,
                        "PUMP_EFF", "PUMP_V1", null, 0,
                        List.of(), List.of(), null, List.of()));
        when(validFormula.calculate(validInputs))
                .thenReturn(success("WCR_COP", "COP_V1", 5.5));

        engine(List.of(malformedFormula, validFormula)).onMinuteFrozen(
                new HvacMinuteBatchFrozenEvent(
                        MINUTE, FINALIZED_AT, false, List.of(aggregate)));

        ArgumentCaptor<List<IndicatorMinuteResult>> successes = listCaptor();
        ArgumentCaptor<List<FormulaCalculationException>> failures = listCaptor();
        verify(indicatorRepository).saveSuccesses(successes.capture());
        verify(indicatorRepository).saveExceptions(failures.capture());
        assertThat(successes.getValue()).extracting(IndicatorMinuteResult::indicatorId)
                .containsExactly("I2");
        assertThat(failures.getValue()).singleElement().satisfies(row -> {
            assertThat(row.indicatorId()).isEqualTo("I1");
            assertThat(row.status()).isEqualTo(FormulaCalculation.Status.ENGINE_ERROR);
            assertThat(row.reasonCode()).isEqualTo("FORMULA_ENGINE_ERROR");
        });
    }

    private HvacFormulaEngine engine(List<IndicatorFormula> formulas) {
        return new HvacFormulaEngine(
                configProvider, minuteRepository, indicatorRepository, cache,
                publisher, assembler, formulas);
    }

    private static IndicatorFormula formula(String code, String version) {
        IndicatorFormula formula = mock(IndicatorFormula.class);
        when(formula.indicatorCode()).thenReturn(code);
        when(formula.formulaVersion()).thenReturn(version);
        return formula;
    }

    private static FormulaCalculation success(String code, String version, double value) {
        return new FormulaCalculation(
                FormulaCalculation.Status.SUCCESS,
                code,
                version,
                value,
                0,
                List.of(),
                List.of(),
                null,
                List.of());
    }

    private static BizIndicator indicator(String id, String code, String buildingId) {
        BizIndicator indicator = new BizIndicator();
        indicator.setIndicatorId(id);
        indicator.setIndicatorCode(code);
        indicator.setBuildingId(buildingId);
        indicator.setSystemGroupId("GROUP001");
        indicator.setEquipId("EQUIP001");
        indicator.setStatus(1);
        return indicator;
    }

    private static RawMinuteAggregate aggregate(String pointId, String buildingId) {
        return new RawMinuteAggregate(
                pointId, pointId, buildingId, "GROUP001", "EQUIP001", "EQUIP001",
                "WCR", "MAIN", "GW", 1, MINUTE, 100.0, 100.0, 100.0,
                1, 0, MINUTE, MINUTE, FINALIZED_AT);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ArgumentCaptor<List<T>> listCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }
}
