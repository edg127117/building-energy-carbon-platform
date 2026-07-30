package com.platform.iot.formula;

import com.platform.cache.IndicatorLatestCacheService;
import com.platform.config.FormulaProperties;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.controller.HvacIndicatorController;
import com.platform.hvac.model.entity.BizIndicator;
import com.platform.hvac.service.HvacIndicatorQueryService;
import com.platform.iot.dataquality.event.HvacMinuteQualityReadyEvent;
import com.platform.iot.dataquality.model.QualityEventSource;
import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.formula.model.FormulaCalculationException;
import com.platform.iot.formula.model.IndicatorLatestState;
import com.platform.iot.formula.model.IndicatorMinuteKey;
import com.platform.iot.formula.model.IndicatorMinuteResult;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.IndicatorMinuteRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
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
    private final DataPointConfigProvider pointConfigProvider =
            mock(DataPointConfigProvider.class);
    private final FormulaDependencyResolver dependencyResolver =
            mock(FormulaDependencyResolver.class);

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
                "onMinuteQualityReady", HvacMinuteQualityReadyEvent.class);
        assertThat(listener.getAnnotation(EventListener.class)).isNotNull();
        assertThat(listener.getAnnotation(Async.class)).isNull();

        Constructor<HvacFormulaEngine> productionConstructor =
                HvacFormulaEngine.class.getConstructor(
                        IndicatorConfigProvider.class,
                        HvacMinuteRepository.class,
                        IndicatorMinuteRepository.class,
                        IndicatorLatestCacheService.class,
                        IndicatorRealtimePublisher.class,
                        DataPointConfigProvider.class,
                        FormulaProperties.class);
        assertThat(productionConstructor.getAnnotation(Autowired.class)).isNotNull();
    }

    @Test
    void springContextStartsWithExplicitProductionConstructor() {
        new ApplicationContextRunner()
                .withPropertyValues("formula.enabled=true")
                .withBean(IndicatorConfigProvider.class, () -> configProvider)
                .withBean(HvacMinuteRepository.class, () -> minuteRepository)
                .withBean(IndicatorMinuteRepository.class, () -> indicatorRepository)
                .withBean(IndicatorLatestCacheService.class, () -> cache)
                .withBean(IndicatorRealtimePublisher.class, () -> publisher)
                .withBean(DataPointConfigProvider.class, () -> pointConfigProvider)
                .withBean(FormulaProperties.class, FormulaProperties::new)
                .withUserConfiguration(HvacFormulaEngine.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(HvacFormulaEngine.class);
                });
    }

    @Test
    void formulaDisabledContextOmitsEngineQueryServiceAndController() {
        new ApplicationContextRunner()
                .withPropertyValues("formula.enabled=false")
                .withUserConfiguration(
                        HvacFormulaEngine.class,
                        HvacIndicatorQueryService.class,
                        HvacIndicatorController.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(HvacFormulaEngine.class);
                    assertThat(context).doesNotHaveBean(HvacIndicatorQueryService.class);
                    assertThat(context).doesNotHaveBean(HvacIndicatorController.class);
                });
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
    void explainRequiresExactPersistedFormulaVersionBeforeAssemblingInputs() {
        BizIndicator indicator = indicator("I1", "WCR_COP", "BLD001");
        IndicatorFormula formula = formula("WCR_COP", "WCR_COP_V1");

        assertThatThrownBy(() -> engine(List.of(formula)).explain(
                indicator, MINUTE, List.of(aggregate("P1", "BLD001")),
                "WCR_COP_V0"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getMessage())
                            .isEqualTo("公式版本不受当前服务支持");
                });

        verifyNoInteractions(assembler);
        verify(formula, never()).calculate(any());
    }

    @Test
    void explainUsesExactCodeVersionAndReturnsCalculationProcess() {
        BizIndicator indicator = indicator("I1", "WCR_COP", "BLD001");
        RawMinuteAggregate aggregate = aggregate("P1", "BLD001");
        IndicatorFormula formula = formula("WCR_COP", "WCR_COP_V1");
        FormulaInputs inputs = new FormulaInputs(List.of());
        FormulaCalculation expected = success("WCR_COP", "WCR_COP_V1", 4.2);
        when(assembler.assemble(indicator, MINUTE, List.of(aggregate)))
                .thenReturn(inputs);
        when(formula.calculate(inputs)).thenReturn(expected);

        FormulaCalculation actual = engine(List.of(formula)).explain(
                indicator, MINUTE, List.of(aggregate), "WCR_COP_V1");

        assertThat(actual).isSameAs(expected);
        verify(assembler).assemble(indicator, MINUTE, List.of(aggregate));
        verify(formula).calculate(inputs);
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

        engine(List.of(formula)).onMinuteQualityReady(
                new HvacMinuteQualityReadyEvent(
                        MINUTE, FINALIZED_AT, false, List.of(payload)));

        verify(minuteRepository, never()).findByMinute(any(Long.class), any());
        verify(assembler).assemble(indicator, MINUTE, List.of(payload));
    }

    @Test
    void productionEngineCalculatesAllFourIndicatorsFromNormalEventPayload() {
        BizIndicator chiller = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
        BizIndicator tower = indicator("I2", "TOWER_EFF", "BLD001", "TOWER");
        BizIndicator pump = indicator("I3", "PUMP_EFF", "BLD001", "PUMP");
        BizIndicator ahu = indicator("I4", "AHU_POW_EFF", "BLD001", "AHU");
        when(configProvider.findAllActive())
                .thenReturn(List.of(chiller, tower, pump, ahu));
        when(cache.setIfNotOlder(any(), eq(false))).thenReturn(true);

        List<RawMinuteAggregate> aggregates = List.of(
                point("C1", "CHILLER", "MAIN", "TWin", 12.0),
                point("C2", "CHILLER", "MAIN", "TWout", 7.0),
                point("C3", "CHILLER", "MAIN", "GW", 36.0),
                point("C4", "CHILLER", "MAIN", "PPE", 100.0),
                point("T1", "TOWER", "CT", "TWin", 35.0),
                point("T2", "TOWER", "CT", "TWout", 30.0),
                point("T3", "TOWER", "CT", "TWB", 25.0),
                point("P1", "PUMP", "Pc", "GW", 1.0),
                point("P2", "PUMP", "Pc", "Pout", 200_000.0),
                point("P3", "PUMP", "Pc", "Pin", 100_000.0),
                point("P4", "PUMP", "Pc", "Z", 0.0),
                point("P5", "PUMP", "Pc", "PPE", 100.0),
                point("A1", "AHU", "MAIN", "TotalPress", 1_000.0),
                point("A2", "AHU", "MAIN", "EtaT", 80.0));

        new HvacFormulaEngine(
                configProvider, minuteRepository, indicatorRepository, cache,
                publisher, pointConfigProvider, new FormulaProperties())
                .onMinuteQualityReady(new HvacMinuteQualityReadyEvent(
                        MINUTE, FINALIZED_AT, false, aggregates));

        ArgumentCaptor<List<IndicatorMinuteResult>> rows = listCaptor();
        ArgumentCaptor<IndicatorLatestState> states =
                ArgumentCaptor.forClass(IndicatorLatestState.class);
        verify(indicatorRepository).saveSuccesses(rows.capture());
        verify(indicatorRepository, never()).saveExceptions(any());
        verify(cache, org.mockito.Mockito.times(4))
                .setIfNotOlder(states.capture(), eq(false));
        verify(publisher, org.mockito.Mockito.times(4)).publish(any());
        verify(minuteRepository, never()).findByMinute(any(Long.class), any());
        assertThat(rows.getValue())
                .hasSize(4)
                .extracting(IndicatorMinuteResult::indicatorCode)
                .containsExactly("WCR_COP", "TOWER_EFF", "PUMP_EFF", "AHU_POW_EFF");
        assertThat(rows.getValue())
                .extracting(IndicatorMinuteResult::minuteStart)
                .containsOnly(MINUTE);
        assertThat(states.getAllValues())
                .hasSize(4)
                .allSatisfy(state -> assertThat(state.minuteStart()).isEqualTo(MINUTE));
    }

    @Test
    void normalRecoveryRequeriesCompleteMinuteAndCalculatesAllBuildingIndicators() {
        RawMinuteAggregate triggerOne = aggregate("P1", "BLD001");
        RawMinuteAggregate triggerTwo = aggregate("P2", "BLD002");
        RawMinuteAggregate recovered = aggregate("P3", "BLD001");
        BizIndicator indicatorOne = indicator("I1", "WCR_COP", "BLD001");
        BizIndicator indicatorTwo = indicator("I2", "WCR_COP", "BLD001");
        IndicatorFormula formula = formula("WCR_COP", "V1");
        FormulaInputs inputs = new FormulaInputs(List.of());
        when(minuteRepository.findByMinute(
                MINUTE, Set.of("BLD001", "BLD002"))).thenReturn(List.of(recovered));
        when(configProvider.findAllActive())
                .thenReturn(List.of(indicatorOne, indicatorTwo));
        when(assembler.assemble(indicatorOne, MINUTE, List.of(recovered)))
                .thenReturn(inputs);
        when(assembler.assemble(indicatorTwo, MINUTE, List.of(recovered)))
                .thenReturn(inputs);
        when(formula.calculate(inputs)).thenReturn(success("WCR_COP", "V1", 4.2));

        engine(List.of(formula)).onMinuteQualityReady(
                new HvacMinuteQualityReadyEvent(
                        MINUTE, FINALIZED_AT, true, List.of(triggerOne, triggerTwo)));

        verify(minuteRepository).findByMinute(
                MINUTE, Set.of("BLD001", "BLD002"));
        verify(assembler).assemble(indicatorOne, MINUTE, List.of(recovered));
        verify(assembler).assemble(indicatorTwo, MINUTE, List.of(recovered));
        ArgumentCaptor<List<IndicatorMinuteResult>> successes = listCaptor();
        verify(indicatorRepository).saveSuccesses(successes.capture());
        assertThat(successes.getValue())
                .extracting(IndicatorMinuteResult::indicatorId)
                .containsExactly("I1", "I2");
        verifyNoInteractions(dependencyResolver);
    }

    @Test
    void recoveryWithNoRowsStillPersistsMissingInputForPayloadBuilding() {
        RawMinuteAggregate trigger = aggregate("P1", "BLD001");
        BizIndicator indicator = indicator("I1", "WCR_COP", "BLD001");
        when(minuteRepository.findByMinute(MINUTE, Set.of("BLD001")))
                .thenReturn(List.of());
        when(configProvider.findAllActive()).thenReturn(List.of(indicator));

        new HvacFormulaEngine(
                configProvider, minuteRepository, indicatorRepository, cache,
                publisher, new FormulaInputAssembler(), dependencyResolver,
                List.of(new ChillerCopFormula()))
                .onMinuteQualityReady(new HvacMinuteQualityReadyEvent(
                        MINUTE, FINALIZED_AT, true, List.of(trigger)));

        ArgumentCaptor<List<FormulaCalculationException>> failures = listCaptor();
        verify(minuteRepository).findByMinute(MINUTE, Set.of("BLD001"));
        verify(indicatorRepository, never()).saveSuccesses(any());
        verify(indicatorRepository).saveExceptions(failures.capture());
        assertThat(failures.getValue()).singleElement().satisfies(row -> {
            assertThat(row.indicatorId()).isEqualTo("I1");
            assertThat(row.minuteStart()).isEqualTo(MINUTE);
            assertThat(row.status()).isEqualTo(FormulaCalculation.Status.MISSING_INPUT);
            assertThat(row.reasonCode()).isEqualTo("CHILLER_INPUT_MISSING");
        });
        verifyNoInteractions(dependencyResolver);
    }

    @Test
    void qualityReadyWithNoRowsStillPersistsMissingInputForDeclaredBuilding() {
        BizIndicator indicator = indicator("I1", "WCR_COP", "BLD001");
        when(configProvider.findAllActive()).thenReturn(List.of(indicator));

        new HvacFormulaEngine(
                configProvider, minuteRepository, indicatorRepository, cache,
                publisher, pointConfigProvider, new FormulaProperties())
                .onMinuteQualityReady(new HvacMinuteQualityReadyEvent(
                        MINUTE,
                        FINALIZED_AT,
                        QualityEventSource.NORMAL_FREEZE,
                        Set.of("BLD001"),
                        List.of(),
                        Set.of()));

        ArgumentCaptor<List<FormulaCalculationException>> failures = listCaptor();
        verify(indicatorRepository, never()).saveSuccesses(any());
        verify(indicatorRepository).saveExceptions(failures.capture());
        assertThat(failures.getValue()).singleElement().satisfies(row -> {
            assertThat(row.indicatorId()).isEqualTo("I1");
            assertThat(row.status()).isEqualTo(FormulaCalculation.Status.MISSING_INPUT);
            assertThat(row.reasonCode()).isEqualTo("CHILLER_INPUT_MISSING");
        });
        verify(minuteRepository, never()).findByMinute(any(Long.class), any());
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

        engine(List.of(formula)).onMinuteQualityReady(
                new HvacMinuteQualityReadyEvent(
                        MINUTE, FINALIZED_AT, false, List.of(aggregate)));

        verify(assembler).assemble(affected, MINUTE, List.of(aggregate));
        verify(assembler, never()).assemble(eq(unaffected), eq(MINUTE), any());
    }

    @Test
    void packageCalculationEntryPointFiltersRequestedIndicatorIds() {
        RawMinuteAggregate aggregate = aggregate("P1", "BLD001");
        BizIndicator excluded = indicator("I1", "PUMP_EFF", "BLD001");
        BizIndicator requested = indicator("I2", "WCR_COP", "BLD001");
        IndicatorFormula excludedFormula = formula("PUMP_EFF", "PUMP_V1");
        IndicatorFormula requestedFormula = formula("WCR_COP", "COP_V1");
        FormulaInputs inputs = new FormulaInputs(List.of());
        when(configProvider.findAllActive()).thenReturn(List.of(excluded, requested));
        when(assembler.assemble(requested, MINUTE, List.of(aggregate))).thenReturn(inputs);
        when(requestedFormula.calculate(inputs))
                .thenReturn(success("WCR_COP", "COP_V1", 5.5));

        engine(List.of(excludedFormula, requestedFormula)).calculateAndPersist(
                MINUTE, FINALIZED_AT, List.of(aggregate), Set.of("I2"));

        ArgumentCaptor<List<IndicatorMinuteResult>> rows = listCaptor();
        verify(indicatorRepository).saveSuccesses(rows.capture());
        verify(assembler, never()).assemble(eq(excluded), eq(MINUTE), any());
        assertThat(rows.getValue()).singleElement()
                .extracting(IndicatorMinuteResult::indicatorId)
                .isEqualTo("I2");
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
        when(cache.setIfNotOlder(any(), eq(false))).thenReturn(true);

        engine(List.of(failureFormula, successFormula)).onMinuteQualityReady(
                new HvacMinuteQualityReadyEvent(
                        MINUTE, FINALIZED_AT, false, List.of(aggregate)));

        ArgumentCaptor<List<IndicatorMinuteResult>> successes = listCaptor();
        ArgumentCaptor<List<FormulaCalculationException>> failures = listCaptor();
        ArgumentCaptor<IndicatorLatestState> successState =
                ArgumentCaptor.forClass(IndicatorLatestState.class);
        ArgumentCaptor<IndicatorLatestState> failureState =
                ArgumentCaptor.forClass(IndicatorLatestState.class);
        InOrder ordered = inOrder(indicatorRepository, cache, publisher);
        ordered.verify(indicatorRepository).saveSuccesses(successes.capture());
        ordered.verify(indicatorRepository).saveExceptions(failures.capture());
        ordered.verify(cache).setIfNotOlder(successState.capture(), eq(false));
        ordered.verify(publisher).publish(successState.getValue());
        ordered.verify(cache).setIfNotOlder(failureState.capture(), eq(false));
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

        assertThatThrownBy(() -> engine(List.of(formula)).onMinuteQualityReady(
                new HvacMinuteQualityReadyEvent(
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

        assertThatThrownBy(() -> engine(List.of(formula)).onMinuteQualityReady(
                new HvacMinuteQualityReadyEvent(
                        MINUTE, FINALIZED_AT, false, List.of(aggregate))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("tdengine unavailable");

        verify(indicatorRepository, never()).saveSuccesses(any());
        verifyNoInteractions(cache, publisher);
    }

    @Test
    void laterExceptionWriteFailureAlsoBlocksEarlierSuccessCacheUpdate() {
        RawMinuteAggregate aggregate = aggregate("P1", "BLD001");
        BizIndicator succeeding = indicator("I1", "WCR_COP", "BLD001");
        BizIndicator failing = indicator("I2", "PUMP_EFF", "BLD001");
        IndicatorFormula successFormula = formula("WCR_COP", "COP_V1");
        IndicatorFormula failureFormula = formula("PUMP_EFF", "PUMP_V1");
        FormulaInputs successInputs = new FormulaInputs(List.of());
        FormulaInputs failureInputs = new FormulaInputs(List.of());
        when(configProvider.findAllActive()).thenReturn(List.of(succeeding, failing));
        when(assembler.assemble(succeeding, MINUTE, List.of(aggregate)))
                .thenReturn(successInputs);
        when(assembler.assemble(failing, MINUTE, List.of(aggregate)))
                .thenReturn(failureInputs);
        when(successFormula.calculate(successInputs))
                .thenReturn(success("WCR_COP", "COP_V1", 4.2));
        when(failureFormula.calculate(failureInputs)).thenReturn(new FormulaCalculation(
                FormulaCalculation.Status.INVALID_INPUT,
                "PUMP_EFF", "PUMP_V1", null, null, List.of(), List.of(),
                "PUMP_INPUT_INVALID", List.of()));
        doThrow(new IllegalStateException("exception write failed"))
                .when(indicatorRepository).saveExceptions(any());

        assertThatThrownBy(() -> engine(List.of(successFormula, failureFormula))
                .onMinuteQualityReady(new HvacMinuteQualityReadyEvent(
                        MINUTE, FINALIZED_AT, false, List.of(aggregate))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("exception write failed");

        verify(indicatorRepository).saveSuccesses(any());
        verifyNoInteractions(cache, publisher);
    }

    @Test
    void correctionFailureDeletesOldSuccessBeforeAuditAndPublishesAuthoritativeState() {
        RawMinuteAggregate trigger = aggregate("P1", "BLD001");
        BizIndicator indicator = indicator("I1", "PUMP_EFF", "BLD001");
        IndicatorFormula formula = formula("PUMP_EFF", "V1");
        FormulaInputs inputs = new FormulaInputs(List.of());
        when(configProvider.findAllActive()).thenReturn(List.of(indicator));
        when(dependencyResolver.resolve(any(), any(), any()))
                .thenReturn(Set.of("I1"));
        when(minuteRepository.findByMinute(MINUTE, Set.of("BLD001")))
                .thenReturn(List.of(trigger));
        when(assembler.assemble(indicator, MINUTE, List.of(trigger))).thenReturn(inputs);
        when(formula.calculate(inputs)).thenReturn(new FormulaCalculation(
                FormulaCalculation.Status.MISSING_INPUT,
                "PUMP_EFF", "V1", null, null, List.of(), List.of(),
                "PUMP_INPUT_MISSING", List.of(FormulaKeys.PUMP_PPE)));
        when(cache.setIfNotOlder(any(), eq(true))).thenReturn(true);

        engine(List.of(formula)).onMinuteQualityReady(new HvacMinuteQualityReadyEvent(
                MINUTE, FINALIZED_AT, QualityEventSource.LATE_REAL_CORRECTION,
                Set.of("BLD001"), List.of(trigger), Set.of("P1")));

        IndicatorMinuteKey key = new IndicatorMinuteKey("I1", MINUTE);
        InOrder ordered = inOrder(indicatorRepository, cache, publisher);
        ordered.verify(indicatorRepository).deleteSuccesses(Set.of(key));
        ordered.verify(indicatorRepository).saveExceptions(any());
        ordered.verify(cache).setIfNotOlder(any(), eq(true));
        ordered.verify(publisher).publish(any());
        verify(indicatorRepository, never()).saveSuccesses(any());
    }

    @Test
    void correctionSuccessUpsertsWithoutDeletingNewSuccess() {
        RawMinuteAggregate trigger = aggregate("P1", "BLD001");
        BizIndicator indicator = indicator("I1", "WCR_COP", "BLD001");
        IndicatorFormula formula = formula("WCR_COP", "V1");
        FormulaInputs inputs = new FormulaInputs(List.of());
        when(configProvider.findAllActive()).thenReturn(List.of(indicator));
        when(dependencyResolver.resolve(any(), any(), any()))
                .thenReturn(Set.of("I1"));
        when(minuteRepository.findByMinute(MINUTE, Set.of("BLD001")))
                .thenReturn(List.of(trigger));
        when(assembler.assemble(indicator, MINUTE, List.of(trigger))).thenReturn(inputs);
        when(formula.calculate(inputs)).thenReturn(success("WCR_COP", "V1", 4.5));

        engine(List.of(formula)).onMinuteQualityReady(new HvacMinuteQualityReadyEvent(
                MINUTE, FINALIZED_AT, QualityEventSource.INTERPOLATION_CORRECTION,
                Set.of("BLD001"), List.of(trigger), Set.of("P1")));

        verify(indicatorRepository).saveSuccesses(any());
        verify(indicatorRepository, never()).deleteSuccesses(any());
        verify(indicatorRepository, never()).saveExceptions(any());
    }

    @Test
    void normalFreezeFailureDoesNotDeleteAbsentSuccess() {
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

        engine(List.of(formula)).onMinuteQualityReady(new HvacMinuteQualityReadyEvent(
                MINUTE, FINALIZED_AT, QualityEventSource.NORMAL_FREEZE,
                Set.of("BLD001"), List.of(aggregate), Set.of()));

        verify(indicatorRepository, never()).deleteSuccesses(any());
        verify(indicatorRepository).saveExceptions(any());
    }

    @Test
    void correctionDeleteFailurePropagatesAndBlocksAuditCacheAndWebSocket() {
        RawMinuteAggregate trigger = aggregate("P1", "BLD001");
        BizIndicator indicator = indicator("I1", "PUMP_EFF", "BLD001");
        IndicatorFormula formula = formula("PUMP_EFF", "V1");
        FormulaInputs inputs = new FormulaInputs(List.of());
        when(configProvider.findAllActive()).thenReturn(List.of(indicator));
        when(dependencyResolver.resolve(any(), any(), any()))
                .thenReturn(Set.of("I1"));
        when(minuteRepository.findByMinute(MINUTE, Set.of("BLD001")))
                .thenReturn(List.of(trigger));
        when(assembler.assemble(indicator, MINUTE, List.of(trigger))).thenReturn(inputs);
        when(formula.calculate(inputs)).thenReturn(new FormulaCalculation(
                FormulaCalculation.Status.INVALID_INPUT,
                "PUMP_EFF", "V1", null, null, List.of(), List.of(),
                "PUMP_INPUT_INVALID", List.of()));
        doThrow(new IllegalStateException("delete failed"))
                .when(indicatorRepository).deleteSuccesses(any());

        assertThatThrownBy(() -> engine(List.of(formula)).onMinuteQualityReady(
                new HvacMinuteQualityReadyEvent(
                        MINUTE, FINALIZED_AT, QualityEventSource.MANUAL_RECALCULATION,
                        Set.of("BLD001"), List.of(trigger), Set.of("P1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("delete failed");

        verify(indicatorRepository, never()).saveExceptions(any());
        verifyNoInteractions(cache, publisher);
    }

    @Test
    void correctionAuditFailureAfterDeletionStillBlocksCacheAndWebSocket() {
        RawMinuteAggregate trigger = aggregate("P1", "BLD001");
        BizIndicator indicator = indicator("I1", "PUMP_EFF", "BLD001");
        IndicatorFormula formula = formula("PUMP_EFF", "V1");
        FormulaInputs inputs = new FormulaInputs(List.of());
        when(configProvider.findAllActive()).thenReturn(List.of(indicator));
        when(dependencyResolver.resolve(any(), any(), any()))
                .thenReturn(Set.of("I1"));
        when(minuteRepository.findByMinute(MINUTE, Set.of("BLD001")))
                .thenReturn(List.of(trigger));
        when(assembler.assemble(indicator, MINUTE, List.of(trigger))).thenReturn(inputs);
        when(formula.calculate(inputs)).thenReturn(new FormulaCalculation(
                FormulaCalculation.Status.INVALID_INPUT,
                "PUMP_EFF", "V1", null, null, List.of(), List.of(),
                "PUMP_INPUT_INVALID", List.of()));
        doThrow(new IllegalStateException("exception write failed"))
                .when(indicatorRepository).saveExceptions(any());

        assertThatThrownBy(() -> engine(List.of(formula)).onMinuteQualityReady(
                new HvacMinuteQualityReadyEvent(
                        MINUTE, FINALIZED_AT, QualityEventSource.LATE_REAL_CORRECTION,
                        Set.of("BLD001"), List.of(trigger), Set.of("P1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("exception write failed");

        InOrder ordered = inOrder(indicatorRepository);
        ordered.verify(indicatorRepository)
                .deleteSuccesses(Set.of(new IndicatorMinuteKey("I1", MINUTE)));
        ordered.verify(indicatorRepository).saveExceptions(any());
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

        engine(List.of(malformedFormula, validFormula)).onMinuteQualityReady(
                new HvacMinuteQualityReadyEvent(
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedCalculations")
    void invalidCalculationContractBecomesEngineErrorWithoutStoppingOtherIndicators(
            String ignoredDescription,
            FormulaCalculation malformedCalculation) {
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
        when(malformedFormula.calculate(malformedInputs)).thenReturn(malformedCalculation);
        when(validFormula.calculate(validInputs))
                .thenReturn(success("WCR_COP", "COP_V1", 5.5));

        engine(List.of(malformedFormula, validFormula)).onMinuteQualityReady(
                new HvacMinuteQualityReadyEvent(
                        MINUTE, FINALIZED_AT, false, List.of(aggregate)));

        ArgumentCaptor<List<IndicatorMinuteResult>> successes = listCaptor();
        ArgumentCaptor<List<FormulaCalculationException>> failures = listCaptor();
        verify(indicatorRepository).saveSuccesses(successes.capture());
        verify(indicatorRepository).saveExceptions(failures.capture());
        assertThat(successes.getValue()).extracting(IndicatorMinuteResult::indicatorId)
                .containsExactly("I2");
        assertThat(failures.getValue()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(FormulaCalculation.Status.ENGINE_ERROR);
            assertThat(row.reasonCode()).isEqualTo("FORMULA_ENGINE_ERROR");
            assertThat(row.formulaVersion()).isEqualTo("PUMP_V1");
        });
    }

    private static Stream<Arguments> malformedCalculations() {
        return Stream.of(
                arguments("null status",
                        calculation(null, "PUMP_EFF", "PUMP_V1",
                                null, null, "MALFORMED")),
                arguments("blank indicator code",
                        calculation(FormulaCalculation.Status.SUCCESS, " ", "PUMP_V1",
                                1.0, 0, null)),
                arguments("mismatched indicator code",
                        calculation(FormulaCalculation.Status.SUCCESS, "OTHER", "PUMP_V1",
                                1.0, 0, null)),
                arguments("blank formula version",
                        calculation(FormulaCalculation.Status.SUCCESS, "PUMP_EFF", " ",
                                1.0, 0, null)),
                arguments("mismatched formula version",
                        calculation(FormulaCalculation.Status.SUCCESS, "PUMP_EFF", "PUMP_V2",
                                1.0, 0, null)),
                arguments("non-finite success value",
                        calculation(FormulaCalculation.Status.SUCCESS, "PUMP_EFF", "PUMP_V1",
                                Double.NaN, 0, null)),
                arguments("missing success quality",
                        calculation(FormulaCalculation.Status.SUCCESS, "PUMP_EFF", "PUMP_V1",
                                1.0, null, null)),
                arguments("negative success quality",
                        calculation(FormulaCalculation.Status.SUCCESS, "PUMP_EFF", "PUMP_V1",
                                1.0, -1, null)),
                arguments("success quality above two",
                        calculation(FormulaCalculation.Status.SUCCESS, "PUMP_EFF", "PUMP_V1",
                                1.0, 3, null)),
                arguments("blank failure reason",
                        calculation(FormulaCalculation.Status.INVALID_INPUT,
                                "PUMP_EFF", "PUMP_V1", null, null, " ")));
    }

    private static FormulaCalculation calculation(
            FormulaCalculation.Status status,
            String indicatorCode,
            String version,
            Double value,
            Integer quality,
            String reason) {
        return new FormulaCalculation(
                status, indicatorCode, version, value, quality,
                List.of(), List.of(), reason, List.of());
    }

    private HvacFormulaEngine engine(List<IndicatorFormula> formulas) {
        return new HvacFormulaEngine(
                configProvider, minuteRepository, indicatorRepository, cache,
                publisher, assembler, dependencyResolver, formulas);
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
        return indicator(id, code, buildingId, "EQUIP001");
    }

    private static BizIndicator indicator(
            String id, String code, String buildingId, String equipId) {
        BizIndicator indicator = new BizIndicator();
        indicator.setIndicatorId(id);
        indicator.setIndicatorCode(code);
        indicator.setBuildingId(buildingId);
        indicator.setSystemGroupId("GROUP001");
        indicator.setEquipId(equipId);
        indicator.setStatus(1);
        return indicator;
    }

    private static RawMinuteAggregate aggregate(String pointId, String buildingId) {
        return new RawMinuteAggregate(
                pointId, pointId, buildingId, "GROUP001", "EQUIP001", "EQUIP001",
                "WCR", "MAIN", "GW", 1, MINUTE, 100.0, 100.0, 100.0,
                1, 0, MINUTE, MINUTE, FINALIZED_AT);
    }

    private static RawMinuteAggregate point(
            String pointId,
            String equipId,
            String componentCode,
            String suffixCode,
            double value) {
        return new RawMinuteAggregate(
                pointId, pointId, "BLD001", "GROUP001", equipId, equipId,
                "EQUIPMENT", componentCode, suffixCode, 1, MINUTE,
                value, value, value, 1, 0, MINUTE, MINUTE, FINALIZED_AT);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ArgumentCaptor<List<T>> listCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }
}
