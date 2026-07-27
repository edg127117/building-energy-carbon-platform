package com.platform.iot.formula;

import com.platform.config.FormulaProperties;
import com.platform.hvac.model.entity.BizIndicator;
import com.platform.iot.formula.model.IndicatorMinuteKey;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.IndicatorMinuteRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HvacFormulaRecoveryServiceTest {

    private static final long MINUTE = 1_800_000_000_000L;
    private static final String BUILDING_ID = "BLD001";

    @Mock private IndicatorConfigProvider configProvider;
    @Mock private IndicatorMinuteRepository indicatorRepository;
    @Mock private HvacMinuteRepository minuteRepository;
    @Mock private HvacFormulaEngine engine;

    private FormulaProperties properties;

    @BeforeEach
    void setUp() {
        properties = new FormulaProperties();
        properties.setRecoveryMinutes(3);
    }

    @Test
    void queriesSuccessfulKeysOnceForWholeConfiguredWindow() {
        when(configProvider.findAllActive()).thenReturn(List.of(wcr(), tower()));
        when(indicatorRepository.findSuccessfulKeys(
                List.of("IND_TOWER", "IND_WCR"),
                MINUTE, MINUTE + 180_000L))
                .thenReturn(Set.of(
                        new IndicatorMinuteKey("IND_WCR", MINUTE),
                        new IndicatorMinuteKey("IND_TOWER", MINUTE),
                        new IndicatorMinuteKey("IND_WCR", MINUTE + 60_000L),
                        new IndicatorMinuteKey("IND_TOWER", MINUTE + 60_000L),
                        new IndicatorMinuteKey("IND_WCR", MINUTE + 120_000L),
                        new IndicatorMinuteKey("IND_TOWER", MINUTE + 120_000L)));

        service().recover(MINUTE + 210_000L);

        verify(configProvider, times(1)).findAllActive();
        verify(indicatorRepository, times(1)).findSuccessfulKeys(
                List.of("IND_TOWER", "IND_WCR"),
                MINUTE, MINUTE + 180_000L);
        verifyNoInteractions(minuteRepository, engine);
    }

    @Test
    void nonPositiveRecoveryWindowIsClampedToOneMinute() {
        properties.setRecoveryMinutes(0);
        when(configProvider.findAllActive()).thenReturn(List.of(wcr()));
        when(indicatorRepository.findSuccessfulKeys(
                List.of("IND_WCR"), MINUTE, MINUTE + 60_000L))
                .thenReturn(Set.of(new IndicatorMinuteKey("IND_WCR", MINUTE)));

        service().recover(MINUTE + 90_000L);

        verify(indicatorRepository).findSuccessfulKeys(
                List.of("IND_WCR"), MINUTE, MINUTE + 60_000L);
        verifyNoInteractions(minuteRepository, engine);
    }

    @Test
    void skipsMinutesWhoseIndicatorsAreComplete() {
        properties.setRecoveryMinutes(1);
        when(configProvider.findAllActive()).thenReturn(List.of(wcr(), tower()));
        when(indicatorRepository.findSuccessfulKeys(
                List.of("IND_TOWER", "IND_WCR"),
                MINUTE, MINUTE + 60_000L))
                .thenReturn(Set.of(
                        new IndicatorMinuteKey("IND_WCR", MINUTE),
                        new IndicatorMinuteKey("IND_TOWER", MINUTE)));

        service().recover(MINUTE + 90_000L);

        verifyNoInteractions(minuteRepository, engine);
    }

    @Test
    void recalculatesOnlyMissingIndicatorIds() {
        properties.setRecoveryMinutes(1);
        List<RawMinuteAggregate> aggregates = completeAggregates(MINUTE);
        when(configProvider.findAllActive()).thenReturn(List.of(wcr(), tower()));
        when(indicatorRepository.findSuccessfulKeys(
                List.of("IND_TOWER", "IND_WCR"),
                MINUTE, MINUTE + 60_000L))
                .thenReturn(Set.of(new IndicatorMinuteKey("IND_WCR", MINUTE)));
        when(minuteRepository.findByMinute(MINUTE, Set.of(BUILDING_ID)))
                .thenReturn(aggregates);

        service().recover(MINUTE + 90_000L);

        verify(engine).calculateAndPersist(
                eq(MINUTE), eq(MINUTE + 90_000L), eq(aggregates),
                eq(Set.of("IND_TOWER")));
    }

    @Test
    void skipsMinuteWithNoFrozenInputs() {
        properties.setRecoveryMinutes(1);
        when(configProvider.findAllActive()).thenReturn(List.of(wcr()));
        when(indicatorRepository.findSuccessfulKeys(
                List.of("IND_WCR"), MINUTE, MINUTE + 60_000L))
                .thenReturn(Set.of());
        when(minuteRepository.findByMinute(MINUTE, Set.of(BUILDING_ID)))
                .thenReturn(List.of());

        service().recover(MINUTE + 90_000L);

        verify(engine, never()).calculateAndPersist(
                anyLong(), anyLong(), eq(List.of()), eq(Set.of("IND_WCR")));
    }

    @Test
    void failedMinuteDoesNotStopLaterMinutes() {
        properties.setRecoveryMinutes(2);
        List<RawMinuteAggregate> first = completeAggregates(MINUTE);
        List<RawMinuteAggregate> second = completeAggregates(MINUTE + 60_000L);
        when(configProvider.findAllActive()).thenReturn(List.of(wcr()));
        when(indicatorRepository.findSuccessfulKeys(
                List.of("IND_WCR"), MINUTE, MINUTE + 120_000L))
                .thenReturn(Set.of());
        when(minuteRepository.findByMinute(MINUTE, Set.of(BUILDING_ID)))
                .thenReturn(first);
        when(minuteRepository.findByMinute(
                MINUTE + 60_000L, Set.of(BUILDING_ID)))
                .thenReturn(second);
        doThrow(new IllegalStateException("TDengine unavailable"))
                .when(engine).calculateAndPersist(
                        MINUTE, MINUTE + 150_000L, first, Set.of("IND_WCR"));

        service().recover(MINUTE + 150_000L);

        InOrder order = inOrder(engine);
        order.verify(engine).calculateAndPersist(
                MINUTE, MINUTE + 150_000L, first, Set.of("IND_WCR"));
        order.verify(engine).calculateAndPersist(
                MINUTE + 60_000L, MINUTE + 150_000L,
                second, Set.of("IND_WCR"));
    }

    @Test
    void exactFinalizationBoundaryIncludesJustCompletedSourceMinute() {
        properties.setRecoveryMinutes(1);
        when(configProvider.findAllActive()).thenReturn(List.of(wcr()));
        when(indicatorRepository.findSuccessfulKeys(
                List.of("IND_WCR"), MINUTE, MINUTE + 60_000L))
                .thenReturn(Set.of());
        when(minuteRepository.findByMinute(MINUTE, Set.of(BUILDING_ID)))
                .thenReturn(List.of());

        service().recover(MINUTE + 90_000L);

        verify(indicatorRepository).findSuccessfulKeys(
                List.of("IND_WCR"), MINUTE, MINUTE + 60_000L);
    }

    @Test
    void oneMillisecondBeforeFinalizationBoundaryExcludesSourceMinute() {
        properties.setRecoveryMinutes(1);
        when(configProvider.findAllActive()).thenReturn(List.of(wcr()));
        when(indicatorRepository.findSuccessfulKeys(
                List.of("IND_WCR"), MINUTE - 60_000L, MINUTE))
                .thenReturn(Set.of());
        when(minuteRepository.findByMinute(
                MINUTE - 60_000L, Set.of(BUILDING_ID)))
                .thenReturn(List.of());

        service().recover(MINUTE + 89_999L);

        verify(indicatorRepository).findSuccessfulKeys(
                List.of("IND_WCR"), MINUTE - 60_000L, MINUTE);
        verify(minuteRepository, never())
                .findByMinute(eq(MINUTE), eq(Set.of(BUILDING_ID)));
    }

    private HvacFormulaRecoveryService service() {
        return new HvacFormulaRecoveryService(
                configProvider, indicatorRepository, minuteRepository,
                engine, properties, 30);
    }

    private BizIndicator wcr() {
        return indicator("IND_WCR", "WCR_COP");
    }

    private BizIndicator tower() {
        return indicator("IND_TOWER", "TOWER_EFF");
    }

    private BizIndicator indicator(String id, String code) {
        BizIndicator indicator = new BizIndicator();
        indicator.setIndicatorId(id);
        indicator.setIndicatorCode(code);
        indicator.setBuildingId(BUILDING_ID);
        indicator.setStatus(1);
        return indicator;
    }

    private List<RawMinuteAggregate> completeAggregates(long minuteStart) {
        return List.of(new RawMinuteAggregate(
                "POINT_1", "WCR1_TWin", BUILDING_ID, "GROUP_1",
                "EQUIP_1", "WCR1", "WCR", "MAIN", "TWin", 1,
                minuteStart, 12.0, 12.0, 12.0, 1, 0,
                minuteStart + 1_000L, minuteStart + 2_000L,
                minuteStart + 90_000L));
    }
}
