package com.platform.iot.dataquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.aggregation.HvacMinuteBatchFrozenEvent;
import com.platform.iot.dataquality.event.HvacMinuteQualityReadyEvent;
import com.platform.iot.dataquality.model.QualityEventSource;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HvacMinuteQualityCompletionServiceTest {

    private static final long MINUTE = 1_800_000_000_000L;

    @Mock private DataPointConfigProvider pointConfigProvider;
    @Mock private TypicalValueConfigProvider typicalValueConfigProvider;
    @Mock private FillTaskRepository fillTaskRepository;
    @Mock private HvacMinuteRepository minuteRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private TypicalValueFillService fillService;
    private HvacMinuteQualityCompletionService completionService;

    @BeforeEach
    void setUp() {
        fillService = new TypicalValueFillService(
                typicalValueConfigProvider,
                fillTaskRepository,
                new FillTaskEvidenceCodec(new ObjectMapper().findAndRegisterModules()),
                minuteRepository);
        completionService = new HvacMinuteQualityCompletionService(
                pointConfigProvider, minuteRepository, fillService, eventPublisher);
    }

    @Test
    void completeRealInputPublishesReadyWithoutTouchingFillStorage() {
        PointRuntimeConfig first = point("P1", "TWin");
        PointRuntimeConfig second = point("P2", "TWout");
        RawMinuteAggregate firstRow = aggregate(first, 12.0, 0, null);
        RawMinuteAggregate secondRow = aggregate(second, 7.0, 0, null);
        when(pointConfigProvider.findAll()).thenReturn(List.of(first, second));

        completionService.onMinuteFrozen(new HvacMinuteBatchFrozenEvent(
                MINUTE, MINUTE + 45_000L, false, Set.of("BLD001"),
                List.of(firstRow, secondRow)));

        HvacMinuteQualityReadyEvent ready = capturedReady();
        assertThat(ready.source()).isEqualTo(QualityEventSource.NORMAL_FREEZE);
        assertThat(ready.aggregates()).containsExactly(firstRow, secondRow);
        assertThat(ready.affectedPointIds()).isEmpty();
        verifyNoInteractions(typicalValueConfigProvider, fillTaskRepository);
        verify(minuteRepository, never()).findByMinute(any(Long.class), any());
    }

    @Test
    void missingPointWithNoLegalTypicalValueStillPublishesExplicitReadyBoundary() {
        PointRuntimeConfig first = point("P1", "TWin");
        PointRuntimeConfig missing = point("P2", "TWout");
        RawMinuteAggregate firstRow = aggregate(first, 12.0, 0, null);
        when(pointConfigProvider.findAll()).thenReturn(List.of(first, missing));
        when(typicalValueConfigProvider.findApproved("P2", MINUTE))
                .thenReturn(Optional.empty());

        completionService.onMinuteFrozen(new HvacMinuteBatchFrozenEvent(
                MINUTE, MINUTE + 45_000L, false, Set.of("BLD001"),
                List.of(firstRow)));

        HvacMinuteQualityReadyEvent ready = capturedReady();
        assertThat(ready.source()).isEqualTo(QualityEventSource.NORMAL_FREEZE);
        assertThat(ready.aggregates()).containsExactly(firstRow);
        verifyNoInteractions(fillTaskRepository);
    }

    @Test
    void successfulTypicalFillIsMergedBeforeSingleReadyPublication() {
        PointRuntimeConfig first = point("P1", "TWin");
        PointRuntimeConfig missing = point("P2", "TWout");
        RawMinuteAggregate firstRow = aggregate(first, 12.0, 0, null);
        RawMinuteAggregate generated = aggregate(missing, 7.0, 2, "TASK-Q2");
        TypicalValueFillService mockFillService =
                org.mockito.Mockito.mock(TypicalValueFillService.class);
        completionService = new HvacMinuteQualityCompletionService(
                pointConfigProvider, minuteRepository, mockFillService, eventPublisher);
        when(pointConfigProvider.findAll()).thenReturn(List.of(first, missing));
        when(mockFillService.fillMissing(missing, MINUTE, MINUTE + 45_000L))
                .thenReturn(Optional.of(generated));

        completionService.onMinuteFrozen(new HvacMinuteBatchFrozenEvent(
                MINUTE, MINUTE + 45_000L, false, Set.of("BLD001"),
                List.of(firstRow)));

        HvacMinuteQualityReadyEvent ready = capturedReady();
        assertThat(ready.source()).isEqualTo(QualityEventSource.TYPICAL_FILL);
        assertThat(ready.aggregates()).containsExactly(firstRow, generated);
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    @Test
    void concurrentRealResolutionDoesNotMislabelReadyAsTypicalFill() {
        PointRuntimeConfig target = point("P1", "TWin");
        RawMinuteAggregate concurrentReal = aggregate(
                target, 12.0, 0, null);
        TypicalValueFillService mockFillService =
                org.mockito.Mockito.mock(TypicalValueFillService.class);
        completionService = new HvacMinuteQualityCompletionService(
                pointConfigProvider, minuteRepository,
                mockFillService, eventPublisher);
        when(pointConfigProvider.findAll()).thenReturn(List.of(target));
        when(mockFillService.fillMissing(
                target, MINUTE, MINUTE + 45_000L))
                .thenReturn(Optional.of(concurrentReal));

        completionService.onMinuteFrozen(new HvacMinuteBatchFrozenEvent(
                MINUTE, MINUTE + 45_000L, false,
                Set.of("BLD001"), List.of()));

        HvacMinuteQualityReadyEvent ready = capturedReady();
        assertThat(ready.source()).isEqualTo(
                QualityEventSource.NORMAL_FREEZE);
        assertThat(ready.aggregates()).containsExactly(concurrentReal);
    }

    @Test
    void recoveryReadsCompleteMinuteAndMarksChangedPointIds() {
        PointRuntimeConfig point = point("P1", "TWin");
        PointRuntimeConfig secondPoint = point("P2", "TWout");
        RawMinuteAggregate partial = aggregate(point, 12.0, 0, null);
        RawMinuteAggregate complete = aggregate(secondPoint, 7.0, 0, null);
        when(pointConfigProvider.findAll()).thenReturn(List.of(point, secondPoint));
        when(minuteRepository.findByMinute(MINUTE, Set.of("BLD001")))
                .thenReturn(List.of(partial, complete));

        completionService.onMinuteFrozen(new HvacMinuteBatchFrozenEvent(
                MINUTE, MINUTE + 45_000L, true, Set.of("BLD001"), List.of(partial)));

        HvacMinuteQualityReadyEvent ready = capturedReady();
        assertThat(ready.aggregates()).containsExactly(partial, complete);
        assertThat(ready.affectedPointIds()).containsExactly("P1");
        verifyNoInteractions(typicalValueConfigProvider, fillTaskRepository);
    }

    @SuppressWarnings("unchecked")
    private HvacMinuteQualityReadyEvent capturedReady() {
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        return (HvacMinuteQualityReadyEvent) eventCaptor.getValue();
    }

    private PointRuntimeConfig point(String pointId, String suffixCode) {
        return new PointRuntimeConfig(
                pointId, pointId, pointId, "BLD001", "GROUP001",
                "E1", "WCR1", "WCR", "MAIN", suffixCode, "ANALOG", "℃",
                "ONLINE", 1, BigDecimal.ZERO, new BigDecimal("50"));
    }

    private RawMinuteAggregate aggregate(
            PointRuntimeConfig point,
            double value,
            int quality,
            String taskId) {
        return new RawMinuteAggregate(
                point.pointId(), point.pointCode(), point.buildingId(),
                point.systemGroupId(), point.equipId(), point.equipCode(),
                point.familyCode(), point.componentCode(), point.suffixCode(),
                point.isForCalc(), MINUTE, value, value, value,
                quality == 0 ? 1 : 0, quality,
                quality == 0 ? MINUTE + 1_000L : null,
                quality == 0 ? MINUTE + 2_000L : null,
                MINUTE + 45_000L, taskId);
    }
}
