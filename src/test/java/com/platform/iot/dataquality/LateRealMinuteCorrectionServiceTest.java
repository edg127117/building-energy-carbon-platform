package com.platform.iot.dataquality;

import com.platform.cache.IndicatorLatestCacheService;
import com.platform.config.DataQualityProperties;
import com.platform.config.FormulaProperties;
import com.platform.hvac.model.entity.BizIndicator;
import com.platform.iot.aggregation.HvacPointMinuteAggregator;
import com.platform.iot.dataquality.event.HvacLateRealEventStoredEvent;
import com.platform.iot.dataquality.event.HvacMinuteQualityReadyEvent;
import com.platform.iot.dataquality.model.QualityEventSource;
import com.platform.iot.formula.HvacFormulaEngine;
import com.platform.iot.formula.IndicatorConfigProvider;
import com.platform.iot.formula.IndicatorRealtimePublisher;
import com.platform.iot.formula.model.IndicatorMinuteResult;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.IndicatorMinuteRepository;
import com.platform.iot.temporal.model.MinuteQualityWriteResult;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LateRealMinuteCorrectionServiceTest {

    private static final long MINUTE = 1_800_000_000_000L;
    private static final long RECEIVED_AT = MINUTE + 120_000L;

    @Mock private DataPointConfigProvider configProvider;
    @Mock private HvacRawEventRepository rawRepository;
    @Mock private HvacMinuteRepository minuteRepository;
    @Mock private FillTaskRepository fillTaskRepository;
    @Mock private InterpolationFillService interpolationFillService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private LateRealMinuteCorrectionService service;

    @BeforeEach
    void setUp() {
        DataQualityProperties properties = new DataQualityProperties();
        properties.setLateRealCorrectionHours(24);
        service = new LateRealMinuteCorrectionService(
                configProvider,
                rawRepository,
                minuteRepository,
                fillTaskRepository,
                interpolationFillService,
                eventPublisher,
                new HvacPointMinuteAggregator(),
                new MinuteQualityLockRegistry(),
                new SimpleMeterRegistry(),
                properties);
        lenient().when(configProvider.findByPointId("P1"))
                .thenReturn(Optional.of(point()));
    }

    @Test
    void asynchronousListenerUsesTheBoundedLateCorrectionExecutor() throws Exception {
        Async async = LateRealMinuteCorrectionService.class
                .getMethod("onLateRealEventStored", HvacLateRealEventStoredEvent.class)
                .getAnnotation(Async.class);

        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("lateRealCorrectionExecutor");
    }

    @Test
    void upgradesGeneratedMinuteToRealAndPublishesBeforeInterpolation() {
        RawMinuteAggregate current = minute(18.0, 2, 0, null, null, "TASK-Q2");
        List<RawTelemetryEvent> events = List.of(
                event(10.0, 1_000L, 2_000L, false),
                event(14.0, 40_000L, 90_000L, true));
        when(minuteRepository.findPointMinute("P1", MINUTE))
                .thenReturn(Optional.of(current));
        when(rawRepository.findWindow(MINUTE, MINUTE + 60_000L, true))
                .thenReturn(events);
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenReturn(List.of(new MinuteQualityWriteResult(
                        "P1", MINUTE,
                        MinuteQualityWriteResult.Outcome.UPGRADED,
                        2, "TASK-Q2")));
        when(minuteRepository.findByMinute(MINUTE, Set.of("B1")))
                .thenAnswer(invocation -> List.of(capturedWrittenMinute()));

        service.onLateRealEventStored(lateEvent());

        RawMinuteAggregate written = capturedWrittenMinute();
        assertThat(written.averageValue()).isEqualTo(12.0);
        assertThat(written.sampleCount()).isEqualTo(2);
        assertThat(written.dataQuality()).isZero();
        assertThat(written.firstReceivedTime()).isEqualTo(MINUTE + 2_000L);
        assertThat(written.lastReceivedTime()).isEqualTo(MINUTE + 90_000L);
        assertThat(written.qualityTaskId()).isNull();
        verify(fillTaskRepository).incrementReplacedCount("TASK-Q2", 1);

        InOrder order = inOrder(eventPublisher, interpolationFillService);
        order.verify(eventPublisher).publishEvent(argThat(
                (Object event) ->
                        event instanceof HvacMinuteQualityReadyEvent ready
                                && ready.source()
                                == QualityEventSource.LATE_REAL_CORRECTION
                                && ready.affectedPointIds()
                                .equals(Set.of("P1"))
                                && ready.finalizedAt()
                                == written.finalizedAt()));
        order.verify(interpolationFillService)
                .fillFromRightEndpoints(
                        List.of(written), written.finalizedAt());
    }

    @Test
    void existingRealMinuteIsIdempotentWhenCompleteEvidenceIsUnchanged() {
        when(minuteRepository.findPointMinute("P1", MINUTE))
                .thenReturn(Optional.of(
                        minute(12.0, 0, 1,
                                MINUTE + 2_000L, MINUTE + 2_000L, null)));
        when(rawRepository.findWindow(MINUTE, MINUTE + 60_000L, true))
                .thenReturn(List.of(
                        event(12.0, 1_000L, 2_000L, false)));

        service.onLateRealEventStored(lateEvent());

        verify(rawRepository).findWindow(
                MINUTE, MINUTE + 60_000L, true);
        verify(minuteRepository, never())
                .saveAllWithQualityPriority(anyList(), any());
        verifyNoInteractions(eventPublisher, interpolationFillService);
    }

    @Test
    void realMinuteIsUpdatedWhenAnotherLateSampleWasStoredAfterFirstAggregation() {
        RawMinuteAggregate incomplete = minute(
                10.0, 0, 1,
                MINUTE + 2_000L, MINUTE + 2_000L, null);
        List<RawTelemetryEvent> completeEvidence = List.of(
                event(10.0, 1_000L, 2_000L, false),
                event(14.0, 40_000L, 90_000L, true));
        when(minuteRepository.findPointMinute("P1", MINUTE))
                .thenReturn(Optional.of(incomplete));
        when(rawRepository.findWindow(MINUTE, MINUTE + 60_000L, true))
                .thenReturn(completeEvidence);
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenReturn(List.of(new MinuteQualityWriteResult(
                        "P1", MINUTE,
                        MinuteQualityWriteResult.Outcome.UPDATED_REAL,
                        0, null)));
        when(minuteRepository.findByMinute(MINUTE, Set.of("B1")))
                .thenAnswer(invocation -> List.of(capturedWrittenMinute()));

        service.onLateRealEventStored(lateEvent());

        RawMinuteAggregate updated = capturedWrittenMinute();
        assertThat(updated.averageValue()).isEqualTo(12.0);
        assertThat(updated.sampleCount()).isEqualTo(2);
        verify(eventPublisher).publishEvent(
                any(HvacMinuteQualityReadyEvent.class));
        verify(fillTaskRepository, never())
                .incrementReplacedCount(any(), anyInt());
    }

    @Test
    void outOfOrderNotificationStillAdvancesCorrectionFinalizedTime() {
        long currentFinalizedAt = RECEIVED_AT + 5_000L;
        RawMinuteAggregate incomplete = minute(
                10.0, 0, 1,
                MINUTE + 2_000L, MINUTE + 2_000L, null,
                currentFinalizedAt);
        when(minuteRepository.findPointMinute("P1", MINUTE))
                .thenReturn(Optional.of(incomplete));
        when(rawRepository.findWindow(MINUTE, MINUTE + 60_000L, true))
                .thenReturn(List.of(
                        event(10.0, 1_000L, 2_000L, false),
                        event(14.0, 40_000L, 90_000L, true)));
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenReturn(List.of(new MinuteQualityWriteResult(
                        "P1", MINUTE,
                        MinuteQualityWriteResult.Outcome.UPDATED_REAL,
                        0, null)));
        when(minuteRepository.findByMinute(MINUTE, Set.of("B1")))
                .thenAnswer(invocation -> List.of(capturedWrittenMinute()));
        HvacLateRealEventStoredEvent olderNotification =
                new HvacLateRealEventStoredEvent(
                        "P1", "B1", MINUTE, RECEIVED_AT - 1_000L);

        service.onLateRealEventStored(olderNotification);

        RawMinuteAggregate updated = capturedWrittenMinute();
        assertThat(updated.finalizedAt())
                .isGreaterThan(currentFinalizedAt);
        verify(eventPublisher).publishEvent(argThat(
                (Object event) ->
                        event instanceof HvacMinuteQualityReadyEvent ready
                                && ready.finalizedAt()
                                == updated.finalizedAt()));
        verify(interpolationFillService).fillFromRightEndpoints(
                List.of(updated), updated.finalizedAt());
    }

    @Test
    void eventOutsideAutomaticWindowKeepsRawEvidenceOnly() {
        service.onLateRealEventStored(new HvacLateRealEventStoredEvent(
                "P1", "B1", MINUTE,
                MINUTE + 24L * 60L * 60L * 1_000L + 1L));

        verifyNoInteractions(rawRepository, minuteRepository,
                fillTaskRepository, eventPublisher, interpolationFillService);
    }

    @Test
    void formalMinuteWriteFailureDoesNotPublishReady() {
        when(minuteRepository.findPointMinute("P1", MINUTE))
                .thenReturn(Optional.of(
                        minute(18.0, 2, 0, null, null, "TASK-Q2")));
        when(rawRepository.findWindow(MINUTE, MINUTE + 60_000L, true))
                .thenReturn(List.of(event(12.0, 1_000L, 90_000L, true)));
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenThrow(new IllegalStateException("TDengine unavailable"));

        service.onLateRealEventStored(lateEvent());

        verifyNoInteractions(eventPublisher, interpolationFillService);
        verify(fillTaskRepository, never()).incrementReplacedCount(any(), anyInt());
    }

    @Test
    void missingFormalMinuteCanBeInsertedFromLateRealEvidence() {
        when(minuteRepository.findPointMinute("P1", MINUTE))
                .thenReturn(Optional.empty());
        when(rawRepository.findWindow(MINUTE, MINUTE + 60_000L, true))
                .thenReturn(List.of(event(12.0, 1_000L, 90_000L, true)));
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenReturn(List.of(new MinuteQualityWriteResult(
                        "P1", MINUTE,
                        MinuteQualityWriteResult.Outcome.INSERTED,
                        null, null)));
        when(minuteRepository.findByMinute(MINUTE, Set.of("B1")))
                .thenAnswer(invocation -> List.of(capturedWrittenMinute()));

        service.onLateRealEventStored(lateEvent());

        verify(eventPublisher).publishEvent(
                any(HvacMinuteQualityReadyEvent.class));
        verify(fillTaskRepository, never()).incrementReplacedCount(any(), anyInt());
    }

    @Test
    void threeLateTowerPointsProduceQ0AndTowerEfficiencyResult() {
        PointRuntimeConfig inlet = towerPoint("T-IN", "TWin");
        PointRuntimeConfig outlet = towerPoint("T-OUT", "TWout");
        PointRuntimeConfig wetBulb = towerPoint("T-WB", "TWB");
        List<PointRuntimeConfig> points = List.of(inlet, outlet, wetBulb);
        Map<String, RawMinuteAggregate> storedMinutes = new LinkedHashMap<>();
        List<RawTelemetryEvent> evidence = List.of(
                towerEvent(inlet, 35.0),
                towerEvent(outlet, 30.0),
                towerEvent(wetBulb, 25.0));
        when(configProvider.findByPointId(anyString()))
                .thenAnswer(invocation -> points.stream()
                        .filter(point -> point.pointId().equals(
                                invocation.getArgument(0)))
                        .findFirst());
        when(configProvider.findAll()).thenReturn(points);
        when(minuteRepository.findPointMinute(anyString(), eq(MINUTE)))
                .thenAnswer(invocation -> Optional.ofNullable(
                        storedMinutes.get(invocation.getArgument(0))));
        when(rawRepository.findWindow(MINUTE, MINUTE + 60_000L, true))
                .thenReturn(evidence);
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenAnswer(invocation -> {
                    List<RawMinuteAggregate> rows = invocation.getArgument(0);
                    RawMinuteAggregate row = rows.getFirst();
                    storedMinutes.put(row.pointId(), row);
                    return List.of(new MinuteQualityWriteResult(
                            row.pointId(), row.minuteStart(),
                            MinuteQualityWriteResult.Outcome.INSERTED,
                            null, null));
                });
        when(minuteRepository.findByMinute(MINUTE, Set.of("B1")))
                .thenAnswer(invocation -> List.copyOf(storedMinutes.values()));

        IndicatorConfigProvider indicatorConfig =
                mock(IndicatorConfigProvider.class);
        IndicatorMinuteRepository indicatorMinutes =
                mock(IndicatorMinuteRepository.class);
        when(indicatorConfig.findAllActive()).thenReturn(List.of(towerIndicator()));
        HvacFormulaEngine formulaEngine = new HvacFormulaEngine(
                indicatorConfig,
                minuteRepository,
                indicatorMinutes,
                mock(IndicatorLatestCacheService.class),
                mock(IndicatorRealtimePublisher.class),
                configProvider,
                new FormulaProperties());
        ApplicationEventPublisher formulaPublisher = event -> {
            if (event instanceof HvacMinuteQualityReadyEvent ready) {
                formulaEngine.onMinuteQualityReady(ready);
            }
        };
        DataQualityProperties properties = new DataQualityProperties();
        properties.setLateRealCorrectionHours(24);
        LateRealMinuteCorrectionService linkedService =
                new LateRealMinuteCorrectionService(
                        configProvider,
                        rawRepository,
                        minuteRepository,
                        fillTaskRepository,
                        interpolationFillService,
                        formulaPublisher,
                        new HvacPointMinuteAggregator(),
                        new MinuteQualityLockRegistry(),
                        new SimpleMeterRegistry(),
                        properties);

        for (PointRuntimeConfig point : points) {
            linkedService.onLateRealEventStored(new HvacLateRealEventStoredEvent(
                    point.pointId(), "B1", MINUTE, RECEIVED_AT));
        }

        assertThat(storedMinutes.values())
                .hasSize(3)
                .allSatisfy(row -> assertThat(row.dataQuality()).isZero());
        @SuppressWarnings("unchecked")
        var successRows = org.mockito.ArgumentCaptor.forClass(
                (Class<List<IndicatorMinuteResult>>) (Class<?>) List.class);
        verify(indicatorMinutes).saveSuccesses(successRows.capture());
        assertThat(successRows.getValue()).singleElement().satisfies(result -> {
            assertThat(result.indicatorCode()).isEqualTo("TOWER_EFF");
            assertThat(result.formulaVersion()).isEqualTo("TOWER_EFF_V1");
            assertThat(result.minuteStart()).isEqualTo(MINUTE);
            assertThat(result.value()).isCloseTo(
                    50.0, org.assertj.core.data.Offset.offset(1.0e-8));
        });
    }

    @Test
    void downstreamReadyIsRetriedWithoutWritingQualityZeroAgain() {
        RawMinuteAggregate current =
                minute(18.0, 2, 0, null, null, "TASK-Q2");
        List<RawTelemetryEvent> evidence =
                List.of(event(12.0, 1_000L, 90_000L, true));
        RawMinuteAggregate rebuilt = new HvacPointMinuteAggregator()
                .aggregate(point(), MINUTE, evidence, RECEIVED_AT + 1L);
        when(minuteRepository.findPointMinute("P1", MINUTE))
                .thenReturn(Optional.of(current));
        when(rawRepository.findWindow(MINUTE, MINUTE + 60_000L, true))
                .thenReturn(evidence);
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenReturn(List.of(new MinuteQualityWriteResult(
                        "P1", MINUTE,
                        MinuteQualityWriteResult.Outcome.UPGRADED,
                        2, "TASK-Q2")));
        when(minuteRepository.findByMinute(MINUTE, Set.of("B1")))
                .thenReturn(List.of(rebuilt));
        doThrow(new IllegalStateException("listener unavailable"))
                .doThrow(new IllegalStateException("listener unavailable"))
                .doNothing()
                .when(eventPublisher).publishEvent(any(Object.class));

        service.onLateRealEventStored(lateEvent());

        verify(minuteRepository, times(1))
                .saveAllWithQualityPriority(anyList(), isNull());
        verify(minuteRepository, times(3))
                .findByMinute(MINUTE, Set.of("B1"));
        verify(eventPublisher, times(3)).publishEvent(any(Object.class));
        verify(interpolationFillService, times(1))
                .fillFromRightEndpoints(
                        List.of(rebuilt), rebuilt.finalizedAt());
        verify(fillTaskRepository, times(1))
                .incrementReplacedCount("TASK-Q2", 1);
    }

    @Test
    void concurrentDifferentLateSamplesRefreshAnAlreadyWrittenRealMinute()
            throws Exception {
        AtomicReference<RawMinuteAggregate> stored = new AtomicReference<>(
                minute(18.0, 2, 0, null, null, "TASK-Q2"));
        when(minuteRepository.findPointMinute("P1", MINUTE))
                .thenAnswer(invocation -> Optional.of(stored.get()));
        when(rawRepository.findWindow(MINUTE, MINUTE + 60_000L, true))
                .thenReturn(
                        List.of(event(10.0, 1_000L, 80_000L, true)),
                        List.of(
                                event(10.0, 1_000L, 80_000L, true),
                                event(14.0, 40_000L, 90_000L, true)));
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenAnswer(invocation -> {
                    List<RawMinuteAggregate> rows = invocation.getArgument(0);
                    RawMinuteAggregate previous = stored.get();
                    stored.set(rows.getFirst());
                    return List.of(new MinuteQualityWriteResult(
                            "P1", MINUTE,
                            previous.dataQuality() == 0
                                    ? MinuteQualityWriteResult.Outcome.UPDATED_REAL
                                    : MinuteQualityWriteResult.Outcome.UPGRADED,
                            previous.dataQuality(),
                            previous.qualityTaskId()));
                });
        when(minuteRepository.findByMinute(MINUTE, Set.of("B1")))
                .thenAnswer(invocation -> List.of(stored.get()));
        CountDownLatch start = new CountDownLatch(1);
        Thread first = Thread.ofVirtual().start(() -> {
            await(start);
            service.onLateRealEventStored(lateEvent());
        });
        Thread second = Thread.ofVirtual().start(() -> {
            await(start);
            service.onLateRealEventStored(lateEvent());
        });

        start.countDown();
        first.join(TimeUnit.SECONDS.toMillis(2));
        second.join(TimeUnit.SECONDS.toMillis(2));

        assertThat(first.isAlive()).isFalse();
        assertThat(second.isAlive()).isFalse();
        assertThat(stored.get().sampleCount()).isEqualTo(2);
        assertThat(stored.get().averageValue()).isEqualTo(12.0);
        verify(minuteRepository, times(2))
                .saveAllWithQualityPriority(anyList(), isNull());
        verify(fillTaskRepository, times(1))
                .incrementReplacedCount("TASK-Q2", 1);
        verify(eventPublisher, times(2))
                .publishEvent(any(HvacMinuteQualityReadyEvent.class));
        verify(interpolationFillService, times(2))
                .fillFromRightEndpoints(anyList(), anyLong());
    }

    @Test
    void concurrentCorrectionsReleaseSingleMinuteLocksBeforeBatchDownstream()
            throws Exception {
        PointRuntimeConfig firstPoint = towerPoint("LOCK-A", "TWin");
        PointRuntimeConfig secondPoint = towerPoint("LOCK-B", "TWout");
        assertThat(stripeIndex(firstPoint.pointId()))
                .isNotEqualTo(stripeIndex(secondPoint.pointId()));
        List<PointRuntimeConfig> points = List.of(firstPoint, secondPoint);
        List<MinuteQualityLockRegistry.MinuteKey> batchKeys = points.stream()
                .map(point -> new MinuteQualityLockRegistry.MinuteKey(
                        point.pointId(), MINUTE))
                .toList();
        MinuteQualityLockRegistry sharedLocks =
                new MinuteQualityLockRegistry();
        Map<String, RawMinuteAggregate> stored = new ConcurrentHashMap<>();
        CountDownLatch bothQ0WritesStarted = new CountDownLatch(2);
        when(configProvider.findByPointId(anyString()))
                .thenAnswer(invocation -> points.stream()
                        .filter(point -> point.pointId().equals(
                                invocation.getArgument(0)))
                        .findFirst());
        when(minuteRepository.findPointMinute(anyString(), eq(MINUTE)))
                .thenReturn(Optional.empty());
        when(rawRepository.findWindow(MINUTE, MINUTE + 60_000L, true))
                .thenReturn(List.of(
                        towerEvent(firstPoint, 35.0),
                        towerEvent(secondPoint, 30.0)));
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenAnswer(invocation -> {
                    List<RawMinuteAggregate> rows = invocation.getArgument(0);
                    RawMinuteAggregate row = rows.getFirst();
                    stored.put(row.pointId(), row);
                    bothQ0WritesStarted.countDown();
                    await(bothQ0WritesStarted);
                    return List.of(new MinuteQualityWriteResult(
                            row.pointId(), row.minuteStart(),
                            MinuteQualityWriteResult.Outcome.INSERTED,
                            null, null));
                });
        when(minuteRepository.findByMinute(MINUTE, Set.of("B1")))
                .thenAnswer(invocation -> List.copyOf(stored.values()));
        doAnswer(invocation -> sharedLocks.withLocks(batchKeys, () -> null))
                .when(interpolationFillService)
                .fillFromRightEndpoints(anyList(), anyLong());
        DataQualityProperties properties = new DataQualityProperties();
        properties.setLateRealCorrectionHours(24);
        LateRealMinuteCorrectionService concurrentService =
                new LateRealMinuteCorrectionService(
                        configProvider,
                        rawRepository,
                        minuteRepository,
                        fillTaskRepository,
                        interpolationFillService,
                        eventPublisher,
                        new HvacPointMinuteAggregator(),
                        sharedLocks,
                        new SimpleMeterRegistry(),
                        properties);
        CountDownLatch start = new CountDownLatch(1);
        Thread first = Thread.ofVirtual().start(() -> {
            await(start);
            concurrentService.onLateRealEventStored(
                    lateEvent(firstPoint.pointId()));
        });
        Thread second = Thread.ofVirtual().start(() -> {
            await(start);
            concurrentService.onLateRealEventStored(
                    lateEvent(secondPoint.pointId()));
        });

        start.countDown();
        first.join(TimeUnit.SECONDS.toMillis(2));
        second.join(TimeUnit.SECONDS.toMillis(2));

        assertThat(first.isAlive()).isFalse();
        assertThat(second.isAlive()).isFalse();
        verify(interpolationFillService, times(2))
                .fillFromRightEndpoints(anyList(), anyLong());
    }

    private RawMinuteAggregate capturedWrittenMinute() {
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor
                .forClass((Class<List<RawMinuteAggregate>>) (Class<?>) List.class);
        verify(minuteRepository).saveAllWithQualityPriority(
                captor.capture(), isNull());
        return captor.getValue().getFirst();
    }

    private HvacLateRealEventStoredEvent lateEvent() {
        return lateEvent("P1");
    }

    private HvacLateRealEventStoredEvent lateEvent(String pointId) {
        return new HvacLateRealEventStoredEvent(
                pointId, "B1", MINUTE, RECEIVED_AT);
    }

    private PointRuntimeConfig point() {
        return new PointRuntimeConfig(
                "P1", "WCR1_TWin", "冷冻水进水温度", "B1", "G1",
                "E1", "WCR1", "WCR", "MAIN", "TWin",
                "ANALOG", "℃", "ONLINE", 1, null, null);
    }

    private PointRuntimeConfig towerPoint(
            String pointId,
            String suffixCode) {
        return new PointRuntimeConfig(
                pointId, pointId, pointId, "B1", "G1",
                "TOWER1", "TOWER1", "TOWER", "CT", suffixCode,
                "ANALOG", "℃", "ONLINE", 1,
                new BigDecimal("-50"), new BigDecimal("100"));
    }

    private RawTelemetryEvent towerEvent(
            PointRuntimeConfig point,
            double value) {
        return new RawTelemetryEvent(
                point.pointId(), point.pointCode(), "MQTT_FREEZE_V1",
                point.pointCode(), point.equipCode(), point.buildingId(),
                point.systemGroupId(), point.equipId(), point.equipCode(),
                point.familyCode(), point.componentCode(), point.suffixCode(),
                value, MINUTE + 1_000L, RECEIVED_AT,
                0, 1, true);
    }

    private BizIndicator towerIndicator() {
        BizIndicator indicator = new BizIndicator();
        indicator.setIndicatorId("IND-TOWER-EFF");
        indicator.setIndicatorCode("TOWER_EFF");
        indicator.setBuildingId("B1");
        indicator.setSystemGroupId("G1");
        indicator.setEquipId("TOWER1");
        indicator.setStatus(1);
        return indicator;
    }

    private RawTelemetryEvent event(
            double value,
            long eventOffset,
            long receivedOffset,
            boolean late) {
        return new RawTelemetryEvent(
                "P1", "WCR1_TWin", "MQTT_FREEZE_V1",
                "WCR1_TWin", "WCR1", "B1", "G1", "E1", "WCR1",
                "WCR", "MAIN", "TWin", value,
                MINUTE + eventOffset, MINUTE + receivedOffset,
                0, 1, late);
    }

    private RawMinuteAggregate minute(
            double value,
            int quality,
            int sampleCount,
            Long firstReceived,
            Long lastReceived,
            String taskId) {
        return minute(
                value, quality, sampleCount,
                firstReceived, lastReceived, taskId, RECEIVED_AT);
    }

    private RawMinuteAggregate minute(
            double value,
            int quality,
            int sampleCount,
            Long firstReceived,
            Long lastReceived,
            String taskId,
            long finalizedAt) {
        return new RawMinuteAggregate(
                "P1", "WCR1_TWin", "B1", "G1", "E1", "WCR1",
                "WCR", "MAIN", "TWin", 1,
                MINUTE, value, value, value, sampleCount, quality,
                firstReceived, lastReceived, finalizedAt, taskId);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private int stripeIndex(String pointId) {
        return Math.floorMod(
                31 * pointId.hashCode() + Long.hashCode(MINUTE),
                256);
    }
}
