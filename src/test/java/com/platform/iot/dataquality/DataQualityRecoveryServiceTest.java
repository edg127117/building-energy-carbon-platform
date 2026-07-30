package com.platform.iot.dataquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.DataQualityProperties;
import com.platform.hvac.model.entity.BizIndicator;
import com.platform.iot.dataquality.event.HvacMinuteQualityReadyEvent;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.FillSourceType;
import com.platform.iot.dataquality.model.FillTaskEvidence;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import com.platform.iot.formula.HvacFormulaEngine;
import com.platform.iot.formula.IndicatorConfigProvider;
import com.platform.iot.formula.model.IndicatorMinuteKey;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.IndicatorMinuteRepository;
import com.platform.iot.temporal.model.LateRawMinuteEvidence;
import com.platform.iot.temporal.model.MinuteQualityWriteResult;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataQualityRecoveryServiceTest {

    private static final long HOUR = 1_800_000_000_000L;
    private static final long MINUTE = HOUR + 60_000L;

    @Mock private FillTaskRepository fillTaskRepository;
    @Mock private DataPointConfigProvider pointConfigProvider;
    @Mock private HvacRawEventRepository rawRepository;
    @Mock private HvacMinuteRepository minuteRepository;
    @Mock private IndicatorConfigProvider indicatorConfigProvider;
    @Mock private IndicatorMinuteRepository indicatorRepository;
    @Mock private HvacFormulaEngine formulaEngine;
    @Mock private LateRealMinuteCorrectionService lateCorrectionService;
    @Mock private InterpolationFillService interpolationFillService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ObjectMapper objectMapper;
    private FillTaskEvidenceCodec evidenceCodec;
    private DataQualityRecoveryService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        evidenceCodec = new FillTaskEvidenceCodec(objectMapper);
        DataQualityProperties properties = new DataQualityProperties();
        properties.setRetryDelayMs(600_000L);
        properties.setLateRealCorrectionHours(24);
        service = new DataQualityRecoveryService(
                properties,
                fillTaskRepository,
                evidenceCodec,
                objectMapper,
                pointConfigProvider,
                rawRepository,
                minuteRepository,
                indicatorConfigProvider,
                indicatorRepository,
                Optional.of(formulaEngine),
                lateCorrectionService,
                interpolationFillService,
                new LinearMinuteInterpolator(),
                eventPublisher,
                30);
    }

    @Test
    void isolatesInvalidEvidenceAndKeepsTaskFailed() {
        BizDataQualityFillTask invalid = baseTask(
                "BAD", FillSourceType.TYPICAL_VALUE);
        invalid.setEvidenceJson("{broken");
        invalid.setFailedMinutesJson(
                "[{\"minuteStart\":" + MINUTE + ",\"error\":\"write\"}]");
        when(fillTaskRepository.findRetryable(
                any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(invalid));

        service.recover(HOUR + 24 * 3_600_000L);

        verify(fillTaskRepository).incrementRetry("BAD");
        verify(fillTaskRepository).recordRetryError(
                eq("BAD"), contains("证据格式无效"));
        verify(minuteRepository, never()).saveAllWithQualityPriority(
                anyList(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void idempotentTypicalRetryRepairsMysqlWithoutRepeatingFreshReady() {
        BizDataQualityFillTask task = typicalTask("Q2");
        RawMinuteAggregate stored = generated(MINUTE, 18.5, 2, "Q2", HOUR + 90_000L);
        when(pointConfigProvider.findByPointId("P1"))
                .thenReturn(Optional.of(point()));
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenReturn(List.of(new MinuteQualityWriteResult(
                        "P1", MINUTE,
                        MinuteQualityWriteResult.Outcome.IDEMPOTENT,
                        2, "Q2")));
        when(minuteRepository.findRange(
                eq(Set.of("P1")), anyLong(), anyLong()))
                .thenReturn(List.of(stored));
        when(indicatorConfigProvider.findAllActive()).thenReturn(List.of());
        when(indicatorRepository.findLatestAttemptAt(Set.of()))
                .thenReturn(Map.of());
        when(fillTaskRepository.findRetryable(
                any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(task));

        service.recover(HOUR + 24 * 3_600_000L);

        verify(fillTaskRepository).markRetryRecovered(
                "Q2", FillApplyStatus.APPLIED, 0);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void readyFailureKeepsActuallyWrittenTaskFailedForNextIdempotentRetry() {
        BizDataQualityFillTask task = typicalTask("Q2");
        RawMinuteAggregate stored = generated(
                MINUTE, 18.5, 2, "Q2", HOUR + 90_000L);
        when(pointConfigProvider.findByPointId("P1"))
                .thenReturn(Optional.of(point()));
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenReturn(List.of(new MinuteQualityWriteResult(
                        "P1", MINUTE,
                        MinuteQualityWriteResult.Outcome.INSERTED,
                        null, null)));
        when(minuteRepository.findRange(
                eq(Set.of("P1")), anyLong(), anyLong()))
                .thenReturn(List.of(stored));
        when(indicatorConfigProvider.findAllActive()).thenReturn(List.of());
        when(indicatorRepository.findLatestAttemptAt(Set.of()))
                .thenReturn(Map.of());
        doThrow(new IllegalStateException("listener down"))
                .when(eventPublisher).publishEvent(
                        any(HvacMinuteQualityReadyEvent.class));
        when(fillTaskRepository.findRetryable(
                any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(task));

        service.recover(HOUR + 24 * 3_600_000L);

        verify(fillTaskRepository).recordRetryError(
                "Q2", "listener down");
        verify(fillTaskRepository, never()).markRetryRecovered(
                anyString(), any(), anyInt());
    }

    @Test
    void ownTaskReplacementIsAppliedOnlyInAtomicRecoveredTransition() {
        BizDataQualityFillTask task = typicalTask("Q2");
        when(pointConfigProvider.findByPointId("P1"))
                .thenReturn(Optional.of(point()));
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenReturn(List.of(new MinuteQualityWriteResult(
                        "P1", MINUTE,
                        MinuteQualityWriteResult.Outcome.REJECTED_HIGHER_QUALITY,
                        0, null)));
        when(minuteRepository.findRange(
                eq(Set.of("P1")), anyLong(), anyLong()))
                .thenReturn(List.of());
        when(fillTaskRepository.findRetryable(
                any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(task));

        service.recover(HOUR + 24 * 3_600_000L);

        verify(fillTaskRepository).markRetryRecovered(
                "Q2", FillApplyStatus.REPLACED, 1);
        verify(fillTaskRepository, never()).recordReplacements(
                argThat(counts -> counts.containsKey("Q2")));
    }

    @Test
    void q1ReplacementReadyFailureRetriesIdempotentlyAndCountsOldTaskOnce() {
        BizDataQualityFillTask task = interpolationTask("Q1");
        RawMinuteAggregate left = real(HOUR, 10.0, HOUR + 90_000L);
        RawMinuteAggregate right = real(
                HOUR + 120_000L, 20.0, HOUR + 210_000L);
        RawMinuteAggregate stored = generated(
                MINUTE, 15.0, 1, "Q1", HOUR + 300_000L);
        when(pointConfigProvider.findByPointId("P1"))
                .thenReturn(Optional.of(point()));
        when(minuteRepository.findRange(
                Set.of("P1"), HOUR, HOUR + 180_000L))
                .thenReturn(List.of(left, right));
        when(minuteRepository.findRange(
                Set.of("P1"), MINUTE, HOUR + 120_000L))
                .thenReturn(List.of(stored));
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenReturn(List.of(new MinuteQualityWriteResult(
                        "P1", MINUTE,
                        MinuteQualityWriteResult.Outcome.UPGRADED,
                        2, "OLD_Q2")))
                .thenReturn(List.of(new MinuteQualityWriteResult(
                        "P1", MINUTE,
                        MinuteQualityWriteResult.Outcome.IDEMPOTENT,
                        1, "Q1")));
        when(indicatorConfigProvider.findAllActive())
                .thenReturn(List.of(indicator("I1")));
        when(formulaEngine.resolveAffectedIndicatorIds(
                anyCollection(), eq(Set.of("P1"))))
                .thenReturn(Set.of("I1"));
        when(indicatorRepository.findLatestAttemptAt(
                Set.of(new IndicatorMinuteKey("I1", MINUTE))))
                .thenReturn(Map.of());
        doThrow(new IllegalStateException("ready failed"))
                .doNothing()
                .when(eventPublisher).publishEvent(
                        any(HvacMinuteQualityReadyEvent.class));
        when(fillTaskRepository.findRetryable(
                any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(task));

        long now = HOUR + 24 * 3_600_000L;
        service.recover(now);
        service.recover(now + 600_000L);

        verify(fillTaskRepository, times(1)).recordReplacements(
                Map.of("OLD_Q2", 1));
        verify(fillTaskRepository).markRetryRecovered(
                "Q1", FillApplyStatus.APPLIED, 0);
        verify(eventPublisher, times(2)).publishEvent(
                any(HvacMinuteQualityReadyEvent.class));
    }

    @Test
    void invalidatedInterpolationEndpointRemainsFailedForManualRecalculation() {
        BizDataQualityFillTask task = interpolationTask("Q1");
        when(pointConfigProvider.findByPointId("P1"))
                .thenReturn(Optional.of(point()));
        when(minuteRepository.findRange(
                Set.of("P1"), HOUR, HOUR + 180_000L))
                .thenReturn(List.of(
                        generated(HOUR, 10.0, 2, "OLD", HOUR + 90_000L),
                        real(HOUR + 120_000L, 20.0, HOUR + 210_000L)));
        when(fillTaskRepository.findRetryable(
                any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(task));

        service.recover(HOUR + 24 * 3_600_000L);

        verify(fillTaskRepository).recordRetryError(
                eq("Q1"), contains("端点证据已失效"));
        verify(fillTaskRepository, never()).markRetryRecovered(
                anyString(), any(), anyInt());
    }

    @Test
    void taskBuildingOrFrozenFieldsCannotBeAppliedToAnotherPointContext() {
        BizDataQualityFillTask task = typicalTask("Q2");
        task.setBuildingId("B2");
        when(pointConfigProvider.findByPointId("P1"))
                .thenReturn(Optional.of(point()));
        when(fillTaskRepository.findRetryable(
                any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(task));

        service.recover(HOUR + 24 * 3_600_000L);

        verify(fillTaskRepository).recordRetryError(
                eq("Q2"), contains("建筑与当前档案不一致"));
        verify(minuteRepository, never()).saveAllWithQualityPriority(
                anyList(), any());
    }

    @Test
    void lateEvidenceRunsBeforeStaleQ0ReadyAndInterpolation() {
        LateRawMinuteEvidence evidence =
                new LateRawMinuteEvidence("P1", "B1", MINUTE);
        RawMinuteAggregate lateQ0 = real(MINUTE, 12.0, HOUR + 200_000L);
        when(rawRepository.findLateMinuteEvidence(
                anyLong(), anyLong(), isNull(), isNull(), eq(100)))
                .thenReturn(List.of(evidence));
        when(minuteRepository.findLateRealMinutes(
                anyLong(), anyLong(), isNull(), isNull(), eq(30), eq(100)))
                .thenReturn(List.of(lateQ0));
        when(rawRepository.findLateEvidenceKeys(Set.of(
                new com.platform.iot.temporal.model.PointMinuteKey("P1", MINUTE))))
                .thenReturn(Set.of(
                        new com.platform.iot.temporal.model.PointMinuteKey(
                                "P1", MINUTE)));
        BizIndicator indicator = indicator("I1");
        when(indicatorConfigProvider.findAllActive())
                .thenReturn(List.of(indicator));
        when(formulaEngine.resolveAffectedIndicatorIds(
                anyCollection(), eq(Set.of("P1"))))
                .thenReturn(Set.of("I1"));
        when(indicatorRepository.findLatestAttemptAt(
                Set.of(new IndicatorMinuteKey("I1", MINUTE))))
                .thenReturn(Map.of());
        when(fillTaskRepository.findRetryable(
                any(LocalDateTime.class), eq(100))).thenReturn(List.of());

        service.recover(HOUR + 24 * 3_600_000L);

        var order = inOrder(lateCorrectionService, eventPublisher,
                interpolationFillService);
        order.verify(lateCorrectionService).recoverStoredEvent(any());
        order.verify(eventPublisher).publishEvent(
                any(HvacMinuteQualityReadyEvent.class));
        order.verify(interpolationFillService).fillFromRightEndpoints(
                List.of(lateQ0), lateQ0.finalizedAt());
    }

    @Test
    void freshFailureAttemptPreventsRepeatedLateQ0ReadyButStillRetriesInterpolation() {
        LateRawMinuteEvidence evidence =
                new LateRawMinuteEvidence("P1", "B1", MINUTE);
        RawMinuteAggregate lateQ0 = real(MINUTE, 12.0, HOUR + 200_000L);
        when(rawRepository.findLateMinuteEvidence(
                anyLong(), anyLong(), isNull(), isNull(), eq(100)))
                .thenReturn(List.of(evidence));
        when(minuteRepository.findLateRealMinutes(
                anyLong(), anyLong(), isNull(), isNull(), eq(30), eq(100)))
                .thenReturn(List.of(lateQ0));
        when(rawRepository.findLateEvidenceKeys(Set.of(
                new com.platform.iot.temporal.model.PointMinuteKey("P1", MINUTE))))
                .thenReturn(Set.of(
                        new com.platform.iot.temporal.model.PointMinuteKey(
                                "P1", MINUTE)));
        when(indicatorConfigProvider.findAllActive())
                .thenReturn(List.of(indicator("I1")));
        when(formulaEngine.resolveAffectedIndicatorIds(
                anyCollection(), eq(Set.of("P1"))))
                .thenReturn(Set.of("I1"));
        when(indicatorRepository.findLatestAttemptAt(
                Set.of(new IndicatorMinuteKey("I1", MINUTE))))
                .thenReturn(Map.of(
                        new IndicatorMinuteKey("I1", MINUTE),
                        lateQ0.finalizedAt()));
        when(fillTaskRepository.findRetryable(
                any(LocalDateTime.class), eq(100))).thenReturn(List.of());

        service.recover(HOUR + 24 * 3_600_000L);

        verify(eventPublisher, never()).publishEvent(any());
        verify(interpolationFillService).fillFromRightEndpoints(
                List.of(lateQ0), lateQ0.finalizedAt());
    }

    @Test
    void advancesLateEvidenceSeekCursorBetweenRuns() {
        LateRawMinuteEvidence first =
                new LateRawMinuteEvidence("P1", "B1", MINUTE);
        when(rawRepository.findLateMinuteEvidence(
                anyLong(), anyLong(), isNull(), isNull(), eq(100)))
                .thenReturn(List.of(first));
        when(rawRepository.findLateMinuteEvidence(
                anyLong(), anyLong(), eq(MINUTE), eq("P1"), eq(100)))
                .thenReturn(List.of());
        when(minuteRepository.findLateRealMinutes(
                anyLong(), anyLong(), nullable(Long.class),
                nullable(String.class), eq(30), eq(100))).thenReturn(List.of());
        when(fillTaskRepository.findRetryable(
                any(LocalDateTime.class), eq(100))).thenReturn(List.of());

        service.recover(HOUR + 24 * 3_600_000L);
        service.recover(HOUR + 24 * 3_600_000L + 600_000L);

        verify(rawRepository).findLateMinuteEvidence(
                anyLong(), anyLong(), eq(MINUTE), eq("P1"), eq(100));
    }

    @Test
    void waitingQ1PublishesStaleDownstreamBeforeAtomicRecoveryClose() {
        BizDataQualityFillTask task = interpolationTask("Q1");
        task.setApplyStatus(FillApplyStatus.WAITING);
        RawMinuteAggregate left = real(HOUR, 10.0, HOUR + 90_000L);
        RawMinuteAggregate right = real(
                HOUR + 120_000L, 20.0, HOUR + 210_000L);
        RawMinuteAggregate stored = generated(
                MINUTE, 15.0, 1, "Q1", HOUR + 150_000L);
        when(fillTaskRepository.findWaitingInterpolationTasks(
                any(LocalDateTime.class), eq(40)))
                .thenReturn(List.of(task));
        when(pointConfigProvider.findByPointId("P1"))
                .thenReturn(Optional.of(point()));
        when(minuteRepository.findRange(
                Set.of("P1"), HOUR, HOUR + 180_000L))
                .thenReturn(List.of(left, right));
        when(minuteRepository.saveAllWithQualityPriority(
                anyList(), isNull()))
                .thenReturn(List.of(new MinuteQualityWriteResult(
                        "P1", MINUTE,
                        MinuteQualityWriteResult.Outcome.IDEMPOTENT,
                        1, "Q1")));
        when(minuteRepository.findRange(
                Set.of("P1"), MINUTE, HOUR + 120_000L))
                .thenReturn(List.of(stored));
        when(indicatorConfigProvider.findAllActive())
                .thenReturn(List.of(indicator("I1")));
        when(formulaEngine.resolveAffectedIndicatorIds(
                anyCollection(), eq(Set.of("P1"))))
                .thenReturn(Set.of("I1"));
        when(indicatorRepository.findLatestAttemptAt(
                Set.of(new IndicatorMinuteKey("I1", MINUTE))))
                .thenReturn(Map.of());

        service.recover(HOUR + 24 * 3_600_000L);

        var order = inOrder(eventPublisher, fillTaskRepository);
        order.verify(eventPublisher).publishEvent(
                any(HvacMinuteQualityReadyEvent.class));
        order.verify(fillTaskRepository).markRetryRecovered(
                "Q1", FillApplyStatus.APPLIED, 0);
        verify(minuteRepository).saveAllWithQualityPriority(
                anyList(), isNull());
        verify(fillTaskRepository, never()).reconcile(any());
    }

    @Test
    void waitingQ1WithoutTdRowsReplaysFrozenGapInsteadOfPollingForever() {
        BizDataQualityFillTask task = interpolationTask("Q1");
        task.setApplyStatus(FillApplyStatus.WAITING);
        RawMinuteAggregate left = real(HOUR, 10.0, HOUR + 90_000L);
        RawMinuteAggregate right = real(
                HOUR + 120_000L, 20.0, HOUR + 210_000L);
        RawMinuteAggregate stored = generated(
                MINUTE, 15.0, 1, "Q1", HOUR + 300_000L);
        when(fillTaskRepository.findWaitingInterpolationTasks(
                any(LocalDateTime.class), eq(40)))
                .thenReturn(List.of(task));
        when(pointConfigProvider.findByPointId("P1"))
                .thenReturn(Optional.of(point()));
        when(minuteRepository.findRange(
                Set.of("P1"), HOUR, HOUR + 180_000L))
                .thenReturn(List.of(left, right));
        when(minuteRepository.saveAllWithQualityPriority(
                anyList(), isNull()))
                .thenReturn(List.of(new MinuteQualityWriteResult(
                        "P1", MINUTE,
                        MinuteQualityWriteResult.Outcome.INSERTED,
                        null, null)));
        when(minuteRepository.findRange(
                Set.of("P1"), MINUTE, HOUR + 120_000L))
                .thenReturn(List.of(stored));
        when(indicatorConfigProvider.findAllActive())
                .thenReturn(List.of());
        when(indicatorRepository.findLatestAttemptAt(Set.of()))
                .thenReturn(Map.of());

        service.recover(HOUR + 24 * 3_600_000L);

        verify(minuteRepository).saveAllWithQualityPriority(
                anyList(), isNull());
        verify(eventPublisher).publishEvent(
                any(HvacMinuteQualityReadyEvent.class));
        verify(fillTaskRepository).markRetryRecovered(
                "Q1", FillApplyStatus.APPLIED, 0);
    }

    @Test
    void waitingQ1WithInvalidEndpointBecomesFailedWithAuditReason() {
        BizDataQualityFillTask task = interpolationTask("Q1");
        task.setApplyStatus(FillApplyStatus.WAITING);
        when(fillTaskRepository.findWaitingInterpolationTasks(
                any(LocalDateTime.class), eq(40)))
                .thenReturn(List.of(task));
        when(pointConfigProvider.findByPointId("P1"))
                .thenReturn(Optional.of(point()));
        when(minuteRepository.findRange(
                Set.of("P1"), HOUR, HOUR + 180_000L))
                .thenReturn(List.of(
                        generated(HOUR, 10.0, 2, "OLD", HOUR + 90_000L),
                        real(HOUR + 120_000L, 20.0, HOUR + 210_000L)));

        service.recover(HOUR + 24 * 3_600_000L);

        verify(fillTaskRepository).recordFailure(
                eq("Q1"), eq(MINUTE), contains("端点证据已失效"));
        verify(minuteRepository, never()).saveAllWithQualityPriority(
                anyList(), any());
        verify(fillTaskRepository, never()).markRetryRecovered(
                anyString(), any(), anyInt());
    }

    @Test
    void invalidSourceTaskIsIsolatedWithoutStarvingNormalRetryQuery() {
        when(fillTaskRepository.findInvalidSourceRetryableTaskIds(
                any(LocalDateTime.class), eq(20)))
                .thenReturn(List.of("BAD_SOURCE"));
        when(fillTaskRepository.findWaitingInterpolationTasks(
                any(LocalDateTime.class), eq(40))).thenReturn(List.of());
        when(fillTaskRepository.findRetryable(
                any(LocalDateTime.class), eq(99))).thenReturn(List.of());

        service.recover(HOUR + 24 * 3_600_000L);

        verify(fillTaskRepository).incrementRetry("BAD_SOURCE");
        verify(fillTaskRepository).recordRetryError(
                eq("BAD_SOURCE"), contains("sourceType 非法"));
        verify(fillTaskRepository).findRetryable(
                any(LocalDateTime.class), eq(99));
    }

    @Test
    void rawPageFailureStillRecoversFormalLateQ0WithExactEvidence() {
        RawMinuteAggregate lateQ0 = real(
                MINUTE, 12.0, HOUR + 200_000L);
        doThrow(new IllegalStateException("raw page down"))
                .when(rawRepository).findLateMinuteEvidence(
                        anyLong(), anyLong(), isNull(), isNull(), eq(100));
        when(minuteRepository.findLateRealMinutes(
                anyLong(), anyLong(), isNull(), isNull(), eq(30), eq(100)))
                .thenReturn(List.of(lateQ0));
        var key = new com.platform.iot.temporal.model.PointMinuteKey(
                "P1", MINUTE);
        when(rawRepository.findLateEvidenceKeys(Set.of(key)))
                .thenReturn(Set.of(key));
        when(indicatorConfigProvider.findAllActive())
                .thenReturn(List.of(indicator("I1")));
        when(formulaEngine.resolveAffectedIndicatorIds(
                anyCollection(), eq(Set.of("P1"))))
                .thenReturn(Set.of("I1"));
        when(indicatorRepository.findLatestAttemptAt(
                Set.of(new IndicatorMinuteKey("I1", MINUTE))))
                .thenReturn(Map.of());

        service.recover(HOUR + 24 * 3_600_000L);

        verify(eventPublisher).publishEvent(
                any(HvacMinuteQualityReadyEvent.class));
        verify(interpolationFillService).fillFromRightEndpoints(
                List.of(lateQ0), lateQ0.finalizedAt());
    }

    private BizDataQualityFillTask typicalTask(String taskId) {
        BizDataQualityFillTask task = baseTask(
                taskId, FillSourceType.TYPICAL_VALUE);
        task.setEvidenceJson(evidenceCodec.encode(
                FillSourceType.TYPICAL_VALUE,
                new FillTaskEvidence.Typical(
                        "CFG", 1, BigDecimal.valueOf(18.5), "℃",
                        HOUR, null, HOUR, TypicalValueFillService.ALGORITHM_VERSION,
                        List.of())));
        task.setAlgorithmVersion(TypicalValueFillService.ALGORITHM_VERSION);
        task.setDataQuality(2);
        task.setTypicalConfigId("CFG");
        task.setTypicalConfigVersion(1);
        task.setFailedMinutesJson(
                "[{\"minuteStart\":" + MINUTE + ",\"error\":\"write\"}]");
        return task;
    }

    private BizDataQualityFillTask interpolationTask(String taskId) {
        BizDataQualityFillTask task = baseTask(
                taskId, FillSourceType.INTERPOLATION);
        task.setStartMinute(local(HOUR + 60_000L));
        task.setEndMinute(local(HOUR + 120_000L));
        task.setEvidenceJson(evidenceCodec.encode(
                FillSourceType.INTERPOLATION,
                new FillTaskEvidence.Interpolation(
                        HOUR, 10.0, HOUR + 120_000L, 20.0,
                        InterpolationFillService.ALGORITHM_VERSION)));
        task.setAlgorithmVersion(InterpolationFillService.ALGORITHM_VERSION);
        task.setDataQuality(1);
        task.setFailedMinutesJson(
                "[{\"minuteStart\":" + MINUTE + ",\"error\":\"write\"}]");
        return task;
    }

    private BizDataQualityFillTask baseTask(
            String taskId, FillSourceType sourceType) {
        BizDataQualityFillTask task = new BizDataQualityFillTask();
        task.setTaskId(taskId);
        task.setPointId("P1");
        task.setBuildingId("B1");
        task.setStartMinute(local(HOUR));
        task.setEndMinute(local(HOUR + 3_600_000L));
        task.setSourceType(sourceType);
        task.setApplyStatus(FillApplyStatus.FAILED);
        task.setFailedCount(1);
        task.setReplacedCount(0);
        task.setVoidedCount(0);
        return task;
    }

    private PointRuntimeConfig point() {
        return new PointRuntimeConfig(
                "P1", "P1", "point", "B1", "G1", "E1", "E1",
                "WCR", "MAIN", "TWin", "ANALOG", "℃", "ONLINE", 1,
                BigDecimal.ZERO, BigDecimal.valueOf(100));
    }

    private BizIndicator indicator(String id) {
        BizIndicator indicator = new BizIndicator();
        indicator.setIndicatorId(id);
        indicator.setIndicatorCode("CHILLER_COP");
        indicator.setBuildingId("B1");
        indicator.setEquipId("E1");
        indicator.setStatus(1);
        return indicator;
    }

    private RawMinuteAggregate generated(
            long minute, double value, int quality, String taskId, long finalizedAt) {
        return new RawMinuteAggregate(
                "P1", "P1", "B1", "G1", "E1", "E1",
                "WCR", "MAIN", "TWin", 1,
                minute, value, value, value, 0, quality,
                null, null, finalizedAt, taskId);
    }

    private RawMinuteAggregate real(
            long minute, double value, long finalizedAt) {
        return new RawMinuteAggregate(
                "P1", "P1", "B1", "G1", "E1", "E1",
                "WCR", "MAIN", "TWin", 1,
                minute, value, value, value, 1, 0,
                minute + 1_000L, minute + 20_000L, finalizedAt, null);
    }

    private LocalDateTime local(long epochMillis) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMillis), ZoneId.of("Asia/Shanghai"));
    }
}
