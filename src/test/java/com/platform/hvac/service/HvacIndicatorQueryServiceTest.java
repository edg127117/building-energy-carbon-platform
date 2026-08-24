package com.platform.hvac.service;

import com.platform.cache.IndicatorLatestCacheService;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.model.dto.HvacIndicatorDtos;
import com.platform.hvac.model.entity.BizIndicator;
import com.platform.hvac.model.entity.Building;
import com.platform.iot.formula.HvacFormulaEngine;
import com.platform.iot.formula.IndicatorConfigProvider;
import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.formula.model.FormulaCalculationException;
import com.platform.iot.formula.model.IndicatorLatestState;
import com.platform.iot.formula.model.IndicatorMinuteState;
import com.platform.iot.formula.model.IndicatorMinuteResult;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.IndicatorMinuteRepository;
import com.platform.iot.temporal.model.IndicatorTrendQueryRow;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HvacIndicatorQueryServiceTest {

    private static final long MINUTE = 1_800_000_000_000L;
    private static final Long USER_ID = 1L;
    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");

    @Mock private BuildingService buildingService;
    @Mock private BuildingScopeService buildingScopeService;
    @Mock private IndicatorConfigProvider configProvider;
    @Mock private IndicatorLatestCacheService cache;
    @Mock private IndicatorMinuteRepository indicatorRepository;
    @Mock private HvacMinuteRepository minuteRepository;
    @Mock private HvacFormulaEngine formulaEngine;

    private HvacIndicatorQueryService service;

    @BeforeEach
    void setUp() {
        service = new HvacIndicatorQueryService(
                buildingService,
                buildingScopeService,
                configProvider,
                cache,
                indicatorRepository,
                minuteRepository,
                formulaEngine);
    }

    @Test
    void latestReturnsEveryActiveIndicatorInStableOrderAndCacheHitsAvoidTdengine() {
        allowBuilding("BLD001");
        BizIndicator pump = indicator("I2", "PUMP_EFF", "BLD001", "PUMP");
        BizIndicator chiller = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
        BizIndicator other = indicator("I3", "AHU_POW_EFF", "BLD002", "AHU");
        when(configProvider.findAllActive()).thenReturn(List.of(chiller, other, pump));
        when(cache.get("I1")).thenReturn(Optional.of(
                latest(chiller, MINUTE, FormulaCalculation.Status.SUCCESS, 4.2)));
        when(cache.get("I2")).thenReturn(Optional.of(
                latest(pump, MINUTE, FormulaCalculation.Status.INVALID_INPUT, null)));

        HvacIndicatorDtos.LatestResponse response =
                service.latest("BLD001", USER_ID, ADMIN);

        assertThat(response.buildingId()).isEqualTo("BLD001");
        assertThat(response.indicators())
                .extracting(HvacIndicatorDtos.LatestIndicator::indicatorId)
                .containsExactly("I2", "I1");
        assertThat(response.indicators())
                .extracting(HvacIndicatorDtos.LatestIndicator::status)
                .containsExactly("INVALID_INPUT", "SUCCESS");
        verify(indicatorRepository).findLatestStates(anyList());
        verifyNoInteractions(minuteRepository);
    }

    @Test
    void latestCacheMissMergesSuccessExceptionAndNoDataBySourceMinute() {
        allowBuilding("BLD001");
        BizIndicator equalMinute = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
        BizIndicator newerFailure = indicator("I2", "PUMP_EFF", "BLD001", "PUMP");
        BizIndicator noData = indicator("I3", "TOWER_EFF", "BLD001", "TOWER");
        when(configProvider.findAllActive())
                .thenReturn(List.of(equalMinute, newerFailure, noData));
        when(cache.get("I1")).thenReturn(Optional.empty());
        when(cache.get("I2")).thenReturn(Optional.empty());
        when(cache.get("I3")).thenReturn(Optional.empty());
        when(indicatorRepository.findLatestSuccesses(List.of("I2", "I3", "I1")))
                .thenReturn(List.of(
                        success(equalMinute, MINUTE, 4.2, "WCR_COP_V1"),
                        success(newerFailure, MINUTE, 75.0, "PUMP_EFF_V1")));
        when(indicatorRepository.findLatestExceptions(List.of("I2", "I3", "I1")))
                .thenReturn(List.of(
                        failure(equalMinute, MINUTE, "OLD_FAILURE", List.of()),
                        failure(newerFailure, MINUTE + 60_000L,
                                "PUMP_FLOW_NON_POSITIVE", List.of("Pc/GW"))));

        HvacIndicatorDtos.LatestResponse response =
                service.latest("BLD001", USER_ID, ADMIN);

        assertThat(response.indicators()).hasSize(3);
        HvacIndicatorDtos.LatestIndicator pump = response.indicators().get(0);
        assertThat(pump.indicatorId()).isEqualTo("I2");
        assertThat(pump.minuteStart()).isEqualTo(MINUTE + 60_000L);
        assertThat(pump.status()).isEqualTo("MISSING_INPUT");
        assertThat(pump.value()).isNull();
        assertThat(pump.reasonCode()).isEqualTo("PUMP_FLOW_NON_POSITIVE");
        HvacIndicatorDtos.LatestIndicator tower = response.indicators().get(1);
        assertThat(tower.indicatorId()).isEqualTo("I3");
        assertThat(tower.status()).isEqualTo("NO_DATA");
        assertThat(tower.minuteStart()).isNull();
        HvacIndicatorDtos.LatestIndicator chiller = response.indicators().get(2);
        assertThat(chiller.indicatorId()).isEqualTo("I1");
        assertThat(chiller.status()).isEqualTo("SUCCESS");
        assertThat(chiller.value()).isEqualTo(4.2);
    }

    @Test
    void latestRejectsCacheStateWhoseIdentityDoesNotMatchMetadata() {
        allowBuilding("BLD001");
        BizIndicator chiller = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
        when(configProvider.findAllActive()).thenReturn(List.of(chiller));
        when(cache.get("I1")).thenReturn(Optional.of(new IndicatorLatestState(
                "I1", "WCR_COP", "BLD002", "CHILLER", MINUTE,
                FormulaCalculation.Status.SUCCESS, 99.0, 0, "WCR_COP_V1",
                null, List.of(), List.of(), List.of())));
        when(indicatorRepository.findLatestSuccesses(List.of("I1")))
                .thenReturn(List.of(success(chiller, MINUTE, 4.2, "WCR_COP_V1")));
        when(indicatorRepository.findLatestExceptions(List.of("I1")))
                .thenReturn(List.of());

        HvacIndicatorDtos.LatestResponse response =
                service.latest("BLD001", USER_ID, ADMIN);

        assertThat(response.indicators()).singleElement().satisfies(item -> {
            assertThat(item.value()).isEqualTo(4.2);
            assertThat(item.indicatorId()).isEqualTo("I1");
        });
    }

    @Test
    void latestProjectionPreventsOlderSuccessCacheFromHidingNewerPolicyFailure() {
        allowBuilding("BLD001");
        BizIndicator indicator = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
        IndicatorMinuteState projection = new IndicatorMinuteState(
                "I1", "WCR_COP", "BLD001", "GROUP001", "CHILLER",
                MINUTE + 60_000L, "QUALITY_NOT_ALLOWED", null, "ATTEMPT001",
                MINUTE + 61_000L, 9);
        when(configProvider.findAllActive()).thenReturn(List.of(indicator));
        when(cache.get("I1")).thenReturn(Optional.of(
                latest(indicator, MINUTE, FormulaCalculation.Status.SUCCESS, 4.2)));
        when(indicatorRepository.findLatestStates(List.of("I1")))
                .thenReturn(Map.of("I1", projection));
        when(indicatorRepository.findLatestSuccesses(List.of("I1")))
                .thenReturn(List.of(success(indicator, MINUTE, 4.2, "WCR_COP_V1")));
        when(indicatorRepository.findLatestExceptions(List.of("I1")))
                .thenReturn(List.of());

        HvacIndicatorDtos.LatestIndicator response = service.latest(
                "BLD001", USER_ID, ADMIN).indicators().getFirst();

        assertThat(response.minuteStart()).isEqualTo(MINUTE + 60_000L);
        assertThat(response.status()).isEqualTo("QUALITY_NOT_ALLOWED");
        assertThat(response.value()).isNull();
    }

    @Test
    void historyValidatesRangeAndReturnsOnlyPersistedSuccessRowsInTimeOrder() {
        BizIndicator indicator = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
        allowIndicator(indicator);
        when(indicatorRepository.findHistory(
                "I1", MINUTE, MINUTE + 180_000L))
                .thenReturn(List.of(
                        success(indicator, MINUTE + 120_000L, 5.0, "WCR_COP_V1"),
                        success(indicator, MINUTE, 4.2, "WCR_COP_V1")));

        HvacIndicatorDtos.HistoryResponse response = service.history(
                "I1", MINUTE, MINUTE + 180_000L, USER_ID, ADMIN);

        assertThat(response.records())
                .extracting(HvacIndicatorDtos.HistoryRecord::minuteStart)
                .containsExactly(MINUTE, MINUTE + 120_000L);
        assertThat(response.records())
                .extracting(HvacIndicatorDtos.HistoryRecord::value)
                .containsExactly(4.2, 5.0);

        assertBadRequest(() -> service.history(
                "I1", MINUTE, MINUTE, USER_ID, ADMIN));
        assertBadRequest(() -> service.history(
                "I1", MINUTE,
                MINUTE + Duration.ofDays(31).toMillis() + 1,
                USER_ID, ADMIN));
    }

    @Test
    void trendsKeepRequestedOrderDeduplicateIdsAndPreserveEmptySeries() {
        BizIndicator cop = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
        BizIndicator pump = indicator("I2", "PUMP_EFF", "BLD001", "PUMP");
        allowBuilding("BLD001");
        when(configProvider.findAllActive()).thenReturn(List.of(cop, pump));
        when(indicatorRepository.findTrends(
                List.of("I2", "I1"),
                MINUTE,
                MINUTE + Duration.ofHours(24).toMillis(),
                1))
                .thenReturn(List.of(trend(
                        pump, MINUTE, 58.0, 57.0, 59.0, 1, 2)));

        HvacIndicatorDtos.TrendResponse response = service.trends(
                "BLD001",
                " I2,I1,I2 ",
                MINUTE,
                MINUTE + Duration.ofHours(24).toMillis(),
                USER_ID,
                ADMIN);

        assertThat(response.resolutionMinutes()).isEqualTo(1);
        assertThat(response.series())
                .extracting(HvacIndicatorDtos.TrendSeries::indicatorId)
                .containsExactly("I2", "I1");
        assertThat(response.series().get(0).records()).singleElement().satisfies(record -> {
            assertThat(record.average()).isEqualTo(58.0);
            assertThat(record.minimum()).isEqualTo(57.0);
            assertThat(record.maximum()).isEqualTo(59.0);
            assertThat(record.sampleCount()).isEqualTo(1);
            assertThat(record.dataQuality()).isEqualTo(2);
        });
        assertThat(response.series().get(1).records()).isEmpty();
    }

    @Test
    void trendsUseOneFiveAndThirtyMinuteResolutionAtBoundaries() {
        BizIndicator cop = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
        allowBuilding("BLD001");
        when(configProvider.findAllActive()).thenReturn(List.of(cop));
        when(indicatorRepository.findTrends(anyList(), anyLong(), anyLong(), anyInt()))
                .thenReturn(List.of());

        assertThat(service.trends(
                "BLD001", "I1", MINUTE,
                MINUTE + Duration.ofHours(24).toMillis(), USER_ID, ADMIN)
                .resolutionMinutes()).isEqualTo(1);
        assertThat(service.trends(
                "BLD001", "I1", MINUTE,
                MINUTE + Duration.ofHours(24).toMillis() + 1, USER_ID, ADMIN)
                .resolutionMinutes()).isEqualTo(5);
        assertThat(service.trends(
                "BLD001", "I1", MINUTE,
                MINUTE + Duration.ofDays(7).toMillis(), USER_ID, ADMIN)
                .resolutionMinutes()).isEqualTo(5);
        assertThat(service.trends(
                "BLD001", "I1", MINUTE,
                MINUTE + Duration.ofDays(7).toMillis() + 1, USER_ID, ADMIN)
                .resolutionMinutes()).isEqualTo(30);
    }

    @Test
    void trendsAllowFourIndicatorsInTheRequestedOrder() {
        List<BizIndicator> indicators = List.of(
                indicator("I1", "WCR_COP", "BLD001", "CHILLER"),
                indicator("I2", "TOWER_EFF", "BLD001", "TOWER"),
                indicator("I3", "PUMP_EFF", "BLD001", "PUMP"),
                indicator("I4", "AHU_POW_EFF", "BLD001", "AHU"));
        allowBuilding("BLD001");
        when(configProvider.findAllActive()).thenReturn(indicators);
        when(indicatorRepository.findTrends(
                List.of("I4", "I2", "I1", "I3"),
                MINUTE, MINUTE + 60_000L, 1))
                .thenReturn(List.of());

        assertThat(service.trends(
                "BLD001", "I4,I2,I1,I3", MINUTE, MINUTE + 60_000L,
                USER_ID, ADMIN).series())
                .extracting(HvacIndicatorDtos.TrendSeries::indicatorId)
                .containsExactly("I4", "I2", "I1", "I3");
    }

    @Test
    void trendsRejectInvalidIndicatorSetsBeforeTdengine() {
        allowBuilding("BLD001");
        assertBadRequest(() -> service.trends(
                "BLD001", "I1", null, MINUTE + 60_000L, USER_ID, ADMIN));
        assertBadRequest(() -> service.trends(
                "BLD001", "I1", MINUTE, MINUTE, USER_ID, ADMIN));
        assertBadRequest(() -> service.trends(
                "BLD001", "I1", Long.MIN_VALUE, Long.MAX_VALUE, USER_ID, ADMIN));
        assertBadRequest(() -> service.trends(
                "BLD001", "I1", MINUTE,
                MINUTE + Duration.ofDays(31).toMillis() + 1, USER_ID, ADMIN));
        assertBadRequest(() -> service.trends(
                "BLD001", null, MINUTE, MINUTE + 60_000L, USER_ID, ADMIN));
        assertBadRequest(() -> service.trends(
                "BLD001", " , ", MINUTE, MINUTE + 60_000L, USER_ID, ADMIN));
        assertBadRequest(() -> service.trends(
                "BLD001", "I1,I2,I3,I4,I5", MINUTE,
                MINUTE + 60_000L, USER_ID, ADMIN));

        BizIndicator inactive = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
        inactive.setStatus(0);
        BizIndicator other = indicator("I2", "PUMP_EFF", "BLD002", "PUMP");
        when(configProvider.findAllActive()).thenReturn(List.of(inactive, other));
        assertBadRequest(() -> service.trends(
                "BLD001", "I1", MINUTE, MINUTE + 60_000L, USER_ID, ADMIN));
        assertBadRequest(() -> service.trends(
                "BLD001", "I2", MINUTE, MINUTE + 60_000L, USER_ID, ADMIN));
        verifyNoInteractions(indicatorRepository);
    }

    @Test
    void trendsFilterMismatchedTdengineIdentityAndSanitizeFailures() {
        BizIndicator cop = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
        allowBuilding("BLD001");
        when(configProvider.findAllActive()).thenReturn(List.of(cop));
        when(indicatorRepository.findTrends(
                List.of("I1"), MINUTE, MINUTE + 60_000L, 1))
                .thenReturn(List.of(new IndicatorTrendQueryRow(
                        "I1", "WCR_COP", "BLD001", "OTHER_GROUP", "CHILLER",
                        MINUTE, 5.8, 5.8, 5.8, 1, 0)));

        assertThat(service.trends(
                "BLD001", "I1", MINUTE, MINUTE + 60_000L, USER_ID, ADMIN)
                .series().getFirst().records()).isEmpty();

        when(indicatorRepository.findTrends(
                List.of("I1"), MINUTE, MINUTE + 120_000L, 1))
                .thenThrow(new DataAccessResourceFailureException("trend sql secret"));
        assertSanitized503(() -> service.trends(
                "BLD001", "I1", MINUTE, MINUTE + 120_000L, USER_ID, ADMIN));
    }

    @Test
    void detailUsesCachedInputsAndStepsOnlyForTheRequestedLatestMinute() {
        BizIndicator indicator = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
        allowIndicator(indicator);
        FormulaCalculation.Input input = new FormulaCalculation.Input(
                "MAIN/GW", "P1", "WCR1_GW", 36.0, "m3/h", 0);
        FormulaCalculation.Step step = new FormulaCalculation.Step(
                "5-2", "Q0/Ni", 4.2, null);
        when(cache.get("I1")).thenReturn(Optional.of(new IndicatorLatestState(
                "I1", "WCR_COP", "BLD001", "CHILLER", MINUTE,
                FormulaCalculation.Status.SUCCESS, 4.2, 0, "WCR_COP_V1",
                null, List.of(), List.of(input), List.of(step))));

        HvacIndicatorDtos.CalculationDetail response = service.detail(
                "I1", MINUTE, USER_ID, ADMIN);

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.inputs()).containsExactly(input);
        assertThat(response.steps()).containsExactly(step);
        verify(indicatorRepository).findStates(anySet());
        verifyNoInteractions(minuteRepository, formulaEngine);
    }

    @Test
    void historicalSuccessWinsOverSameMinuteExceptionAndExplainsPersistedVersion() {
        BizIndicator indicator = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
        allowIndicator(indicator);
        IndicatorMinuteResult persisted =
                success(indicator, MINUTE, 4.2, "WCR_COP_V1");
        RawMinuteAggregate aggregate = aggregate("P1", "BLD001", "CHILLER");
        FormulaCalculation explained = new FormulaCalculation(
                FormulaCalculation.Status.SUCCESS, "WCR_COP", "WCR_COP_V1",
                4.2, 1,
                List.of(new FormulaCalculation.Input(
                        "MAIN/GW", "P1", "WCR1_GW", 36.0, null, 1)),
                List.of(new FormulaCalculation.Step(
                        "5-2", "Q0/Ni", 4.2, null)),
                null, List.of());
        when(cache.get("I1")).thenReturn(Optional.empty());
        when(indicatorRepository.findSuccess("I1", MINUTE))
                .thenReturn(Optional.of(persisted));
        when(indicatorRepository.findException("I1", MINUTE))
                .thenReturn(Optional.of(failure(
                        indicator, MINUTE, "OLD_FAILURE", List.of())));
        when(minuteRepository.findByMinute(MINUTE, Set.of("BLD001")))
                .thenReturn(List.of(aggregate));
        when(formulaEngine.explain(
                indicator, MINUTE, List.of(aggregate), "WCR_COP_V1"))
                .thenReturn(explained);

        HvacIndicatorDtos.CalculationDetail response = service.detail(
                "I1", MINUTE, USER_ID, ADMIN);

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.value()).isEqualTo(4.2);
        assertThat(response.dataQuality()).isEqualTo(1);
        assertThat(response.steps()).extracting(FormulaCalculation.Step::code)
                .containsExactly("5-2");
        verify(formulaEngine).explain(
                indicator, MINUTE, List.of(aggregate), "WCR_COP_V1");
    }

    @Test
    void historicalFailureReturnsAuditReasonAndMissingInputs() {
        BizIndicator indicator = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
        allowIndicator(indicator);
        when(cache.get("I1")).thenReturn(Optional.empty());
        FormulaCalculationException audit = failure(
                indicator, MINUTE, "CHILLER_INPUT_MISSING", List.of("MAIN/GW"));
        when(indicatorRepository.findSuccess("I1", MINUTE))
                .thenReturn(Optional.empty());
        when(indicatorRepository.findException("I1", MINUTE))
                .thenReturn(Optional.of(audit));

        HvacIndicatorDtos.CalculationDetail response = service.detail(
                "I1", MINUTE, USER_ID, ADMIN);

        assertThat(response.status()).isEqualTo("MISSING_INPUT");
        assertThat(response.value()).isNull();
        assertThat(response.reasonCode()).isEqualTo("CHILLER_INPUT_MISSING");
        assertThat(response.missingInputs()).containsExactly("MAIN/GW");
        assertThat(response.inputs()).isEmpty();
        assertThat(response.steps()).isEmpty();
        verifyNoInteractions(minuteRepository, formulaEngine);
    }

    @Test
    void detailWithoutPersistedAttemptReturnsNoData() {
        BizIndicator indicator = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
        allowIndicator(indicator);
        when(cache.get("I1")).thenReturn(Optional.empty());
        when(indicatorRepository.findSuccess("I1", MINUTE))
                .thenReturn(Optional.empty());
        when(indicatorRepository.findException("I1", MINUTE))
                .thenReturn(Optional.empty());

        HvacIndicatorDtos.CalculationDetail response = service.detail(
                "I1", MINUTE, USER_ID, ADMIN);

        assertThat(response.status()).isEqualTo("NO_DATA");
        assertThat(response.minuteStart()).isEqualTo(MINUTE);
    }

    @Test
    void unknownDisabledAndCrossBuildingIndicatorsDoNotReachTdengine() {
        when(configProvider.findActive("UNKNOWN")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.history(
                "UNKNOWN", MINUTE, MINUTE + 60_000L, USER_ID, ADMIN))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(404);
                    assertThat(error.getMessage()).doesNotContain("UNKNOWN");
                });

        BizIndicator disabled =
                indicator("DISABLED", "TOWER_EFF", "BLD001", "TOWER");
        disabled.setStatus(0);
        when(configProvider.findActive("DISABLED"))
                .thenReturn(Optional.of(disabled));
        assertThatThrownBy(() -> service.detail(
                "DISABLED", MINUTE, USER_ID, ADMIN))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(404);
                    assertThat(error.getMessage()).doesNotContain("DISABLED");
                });

        BizIndicator crossBuilding =
                indicator("I2", "PUMP_EFF", "BLD002", "PUMP");
        when(configProvider.findActive("I2")).thenReturn(Optional.of(crossBuilding));
        allowBuilding("BLD002");
        doThrow(new BusinessException(403, "无权访问该建筑"))
                .when(buildingScopeService)
                .checkAccess(USER_ID, ADMIN, "BLD002");
        assertThatThrownBy(() -> service.detail(
                "I2", MINUTE, USER_ID, ADMIN))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(403));

        verifyNoInteractions(indicatorRepository, minuteRepository);
    }

    @Test
    void tdengineFailuresBecomeSanitized503BusinessErrors() {
        allowBuilding("BLD001");
        BizIndicator indicator = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
        when(configProvider.findAllActive()).thenReturn(List.of(indicator));
        when(cache.get("I1")).thenReturn(Optional.empty());
        when(indicatorRepository.findLatestSuccesses(anyList()))
                .thenThrow(new DataAccessResourceFailureException(
                        "jdbc:TAOS sql secret"));

        assertThatThrownBy(() -> service.latest("BLD001", USER_ID, ADMIN))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(503);
                    assertThat(error.getMessage())
                            .contains("暂不可用")
                            .doesNotContain("jdbc")
                            .doesNotContain("secret");
                });
    }

    @Test
    void historyAndDetailTdengineFailuresAlsoBecomeSanitized503() {
        BizIndicator indicator = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
        allowIndicator(indicator);
        when(indicatorRepository.findHistory(
                "I1", MINUTE, MINUTE + 60_000L))
                .thenThrow(new DataAccessResourceFailureException("history sql secret"));

        assertSanitized503(() -> service.history(
                "I1", MINUTE, MINUTE + 60_000L, USER_ID, ADMIN));

        when(cache.get("I1")).thenReturn(Optional.empty());
        when(indicatorRepository.findSuccess("I1", MINUTE))
                .thenThrow(new DataAccessResourceFailureException("detail sql secret"));

        assertSanitized503(() -> service.detail(
                "I1", MINUTE, USER_ID, ADMIN));
    }

    @Test
    void rawMinuteFailureDuringHistoricalExplainAlsoBecomesSanitized503() {
        BizIndicator indicator = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
        allowIndicator(indicator);
        when(cache.get("I1")).thenReturn(Optional.empty());
        when(indicatorRepository.findSuccess("I1", MINUTE))
                .thenReturn(Optional.of(success(
                        indicator, MINUTE, 4.2, "WCR_COP_V1")));
        when(indicatorRepository.findException("I1", MINUTE))
                .thenReturn(Optional.empty());
        when(minuteRepository.findByMinute(MINUTE, Set.of("BLD001")))
                .thenThrow(new DataAccessResourceFailureException(
                        "raw minute sql secret"));

        assertSanitized503(() -> service.detail(
                "I1", MINUTE, USER_ID, ADMIN));
        verifyNoInteractions(formulaEngine);
    }

    @Test
    void unsupportedHistoricalFormulaVersionRemainsConflict() {
        BizIndicator indicator = indicator("I1", "WCR_COP", "BLD001", "CHILLER");
        allowIndicator(indicator);
        IndicatorMinuteResult persisted =
                success(indicator, MINUTE, 4.2, "WCR_COP_V0");
        RawMinuteAggregate aggregate = aggregate("P1", "BLD001", "CHILLER");
        when(cache.get("I1")).thenReturn(Optional.empty());
        when(indicatorRepository.findSuccess("I1", MINUTE))
                .thenReturn(Optional.of(persisted));
        when(indicatorRepository.findException("I1", MINUTE))
                .thenReturn(Optional.empty());
        when(minuteRepository.findByMinute(MINUTE, Set.of("BLD001")))
                .thenReturn(List.of(aggregate));
        when(formulaEngine.explain(
                indicator, MINUTE, List.of(aggregate), "WCR_COP_V0"))
                .thenThrow(new BusinessException(
                        409, "公式版本不受当前服务支持"));

        assertThatThrownBy(() -> service.detail(
                "I1", MINUTE, USER_ID, ADMIN))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getMessage())
                            .isEqualTo("公式版本不受当前服务支持");
                });
    }

    private void allowIndicator(BizIndicator indicator) {
        when(configProvider.findActive(indicator.getIndicatorId()))
                .thenReturn(Optional.of(indicator));
        allowBuilding(indicator.getBuildingId());
    }

    private void allowBuilding(String buildingId) {
        Building building = new Building();
        building.setBuildingId(buildingId);
        when(buildingService.getById(buildingId)).thenReturn(building);
    }

    private BizIndicator indicator(
            String id, String code, String buildingId, String equipId) {
        BizIndicator indicator = new BizIndicator();
        indicator.setIndicatorId(id);
        indicator.setIndicatorCode(code);
        indicator.setBuildingId(buildingId);
        indicator.setEquipId(equipId);
        indicator.setSystemGroupId("GROUP001");
        indicator.setStatus(1);
        return indicator;
    }

    private IndicatorLatestState latest(
            BizIndicator indicator,
            long minute,
            FormulaCalculation.Status status,
            Double value) {
        return new IndicatorLatestState(
                indicator.getIndicatorId(),
                indicator.getIndicatorCode(),
                indicator.getBuildingId(),
                indicator.getEquipId(),
                minute,
                status,
                value,
                value == null ? null : 0,
                indicator.getIndicatorCode() + "_V1",
                value == null ? "INVALID" : null,
                List.of(),
                List.of(),
                List.of());
    }

    private IndicatorMinuteResult success(
            BizIndicator indicator,
            long minute,
            double value,
            String version) {
        return new IndicatorMinuteResult(
                indicator.getIndicatorId(),
                indicator.getIndicatorCode(),
                indicator.getBuildingId(),
                indicator.getSystemGroupId(),
                indicator.getEquipId(),
                minute,
                value,
                0,
                version,
                minute + 1_000L);
    }

    private IndicatorTrendQueryRow trend(
            BizIndicator indicator,
            long time,
            double average,
            double minimum,
            double maximum,
            long sampleCount,
            int dataQuality) {
        return new IndicatorTrendQueryRow(
                indicator.getIndicatorId(),
                indicator.getIndicatorCode(),
                indicator.getBuildingId(),
                indicator.getSystemGroupId(),
                indicator.getEquipId(),
                time,
                average,
                minimum,
                maximum,
                sampleCount,
                dataQuality);
    }

    private FormulaCalculationException failure(
            BizIndicator indicator,
            long minute,
            String reason,
            List<String> missing) {
        FormulaCalculation.Status status = missing.isEmpty()
                ? FormulaCalculation.Status.INVALID_INPUT
                : FormulaCalculation.Status.MISSING_INPUT;
        return new FormulaCalculationException(
                indicator.getIndicatorId(),
                indicator.getIndicatorCode(),
                indicator.getBuildingId(),
                indicator.getSystemGroupId(),
                indicator.getEquipId(),
                minute,
                status,
                reason,
                missing,
                indicator.getIndicatorCode() + "_V1",
                minute + 1_000L);
    }

    private RawMinuteAggregate aggregate(
            String pointId, String buildingId, String equipId) {
        return new RawMinuteAggregate(
                pointId, pointId, buildingId, "GROUP001", equipId, equipId,
                "WCR", "MAIN", "GW", 1, MINUTE,
                36.0, 36.0, 36.0, 1, 0,
                MINUTE, MINUTE, MINUTE + 1_000L);
    }

    private void assertBadRequest(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(400));
    }

    private void assertSanitized503(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(503);
                    assertThat(error.getMessage())
                            .contains("暂不可用")
                            .doesNotContain("sql")
                            .doesNotContain("secret");
                });
    }
}
