package com.platform.iot.dataquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.DataQualityProperties;
import com.platform.iot.aggregation.HvacMinuteBatchFrozenEvent;
import com.platform.iot.dataquality.event.HvacMinuteQualityReadyEvent;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.FillSourceType;
import com.platform.iot.dataquality.model.FillTaskEvidence;
import com.platform.iot.dataquality.model.TaskReconciliation;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.MinuteQualityWriteResult;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterpolationFillServiceTest {

    private static final long MINUTE_MILLIS = 60_000L;
    private static final long RIGHT_MINUTE = 1_800_000_360_000L;
    private static final long FINALIZED_AT = RIGHT_MINUTE + 45_000L;
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    @Mock private DataPointConfigProvider pointConfigProvider;
    @Mock private HvacMinuteRepository minuteRepository;
    @Mock private FillTaskRepository fillTaskRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private FillTaskEvidenceCodec evidenceCodec;
    private InterpolationFillService service;

    @BeforeEach
    void setUp() {
        DataQualityProperties properties = new DataQualityProperties();
        properties.getInterpolation().setMaxGapMinutes(5);
        evidenceCodec = new FillTaskEvidenceCodec(
                new ObjectMapper().findAndRegisterModules());
        service = new InterpolationFillService(
                properties,
                pointConfigProvider,
                minuteRepository,
                fillTaskRepository,
                evidenceCodec,
                new LinearMinuteInterpolator(),
                eventPublisher);
    }

    @Test
    void fiveMinuteGapUsesOneRangeReadOneTaskAndOneBatchWrite() {
        PointRuntimeConfig point = point("P1", "ONLINE", "ANALOG", 1,
                BigDecimal.ZERO, new BigDecimal("100"));
        long leftMinute = RIGHT_MINUTE - 6 * MINUTE_MILLIS;
        RawMinuteAggregate left = real(point, leftMinute, 0.0);
        RawMinuteAggregate right = real(point, RIGHT_MINUTE, 60.0);
        RawMinuteAggregate oldQ2 = generated(
                point, RIGHT_MINUTE - 3 * MINUTE_MILLIS, 30.0, 2, "OLD-Q2");
        when(pointConfigProvider.findAll()).thenReturn(List.of(point));
        when(minuteRepository.findRange(
                Set.of("P1"), leftMinute, RIGHT_MINUTE + MINUTE_MILLIS))
                .thenReturn(List.of(left, oldQ2, right));
        stubTask("TASK-Q1");
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenAnswer(invocation -> writeResults(
                        invocation.getArgument(0)));

        service.fillFromRightEndpoints(List.of(right), FINALIZED_AT);

        ArgumentCaptor<BizDataQualityFillTask> taskCaptor =
                ArgumentCaptor.forClass(BizDataQualityFillTask.class);
        verify(fillTaskRepository).getOrCreate(taskCaptor.capture());
        BizDataQualityFillTask task = taskCaptor.getValue();
        assertThat(task.getIdempotencyKey())
                .isEqualTo("Q1:P1:" + leftMinute + ":" + RIGHT_MINUTE
                        + ":LINEAR_V1");
        assertThat(task.getStartMinute()).isEqualTo(local(leftMinute + MINUTE_MILLIS));
        assertThat(task.getEndMinute()).isEqualTo(local(RIGHT_MINUTE));
        assertThat(task.getMinuteCount()).isEqualTo(5);
        assertThat(task.getDataQuality()).isEqualTo(1);
        assertThat(task.getSourceType()).isEqualTo(FillSourceType.INTERPOLATION);
        FillTaskEvidence.Interpolation evidence =
                (FillTaskEvidence.Interpolation) evidenceCodec.decode(
                        FillSourceType.INTERPOLATION, task.getEvidenceJson());
        assertThat(evidence.leftMinute()).isEqualTo(leftMinute);
        assertThat(evidence.leftValue()).isEqualTo(0.0);
        assertThat(evidence.rightMinute()).isEqualTo(RIGHT_MINUTE);
        assertThat(evidence.rightValue()).isEqualTo(60.0);
        assertThat(evidence.algorithmVersion()).isEqualTo("LINEAR_V1");

        ArgumentCaptor<List<RawMinuteAggregate>> rowsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(minuteRepository).saveAllWithQualityPriority(
                rowsCaptor.capture(), isNull());
        assertThat(rowsCaptor.getValue())
                .hasSize(5)
                .extracting(RawMinuteAggregate::averageValue)
                .containsExactly(10.0, 20.0, 30.0, 40.0, 50.0);
        assertThat(rowsCaptor.getValue()).allSatisfy(row -> {
            assertThat(row.dataQuality()).isEqualTo(1);
            assertThat(row.minimumValue()).isEqualTo(row.averageValue());
            assertThat(row.maximumValue()).isEqualTo(row.averageValue());
            assertThat(row.sampleCount()).isZero();
            assertThat(row.firstReceivedTime()).isNull();
            assertThat(row.lastReceivedTime()).isNull();
            assertThat(row.qualityTaskId()).isEqualTo("TASK-Q1");
        });

        ArgumentCaptor<TaskReconciliation> reconciliation =
                ArgumentCaptor.forClass(TaskReconciliation.class);
        verify(fillTaskRepository).reconcile(reconciliation.capture());
        assertThat(reconciliation.getValue().minuteCount()).isEqualTo(5);
        assertThat(reconciliation.getValue().appliedCount()).isEqualTo(5);
        assertThat(reconciliation.getValue().failedCount()).isZero();
        assertThat(reconciliation.getValue().replacedCount()).isZero();
        assertThat(reconciliation.getValue().applyStatus())
                .isEqualTo(FillApplyStatus.APPLIED);
        verify(eventPublisher, times(5)).publishEvent(
                any(HvacMinuteQualityReadyEvent.class));
    }

    @Test
    void multipleRightEndpointsShareOneRangeQueryWithoutPointOrMinuteNPlusOne() {
        PointRuntimeConfig first = point(
                "P1", "ONLINE", "ANALOG", 1,
                BigDecimal.ZERO, new BigDecimal("100"));
        PointRuntimeConfig second = point(
                "P2", "ONLINE", "ANALOG", 1,
                BigDecimal.ZERO, new BigDecimal("100"));
        long leftMinute = RIGHT_MINUTE - 2 * MINUTE_MILLIS;
        RawMinuteAggregate firstRight = real(first, RIGHT_MINUTE, 20.0);
        RawMinuteAggregate secondRight = real(second, RIGHT_MINUTE, 30.0);
        when(pointConfigProvider.findAll()).thenReturn(List.of(first, second));
        when(minuteRepository.findRange(
                Set.of("P1", "P2"),
                RIGHT_MINUTE - 6 * MINUTE_MILLIS,
                RIGHT_MINUTE + MINUTE_MILLIS))
                .thenReturn(List.of(
                        real(first, leftMinute, 10.0),
                        firstRight,
                        real(second, leftMinute, 20.0),
                        secondRight));
        when(fillTaskRepository.getOrCreate(any())).thenAnswer(invocation -> {
            BizDataQualityFillTask task = invocation.getArgument(0);
            task.setTaskId("TASK-" + task.getPointId());
            task.setApplyStatus(FillApplyStatus.WAITING);
            return task;
        });
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenAnswer(invocation -> writeResults(
                        invocation.getArgument(0)));

        service.fillFromRightEndpoints(
                List.of(firstRight, secondRight), FINALIZED_AT);

        verify(minuteRepository, times(1)).findRange(
                Set.of("P1", "P2"),
                RIGHT_MINUTE - 6 * MINUTE_MILLIS,
                RIGHT_MINUTE + MINUTE_MILLIS);
        verify(minuteRepository, times(2))
                .saveAllWithQualityPriority(anyList(), isNull());
        verify(fillTaskRepository, times(2)).getOrCreate(any());
    }

    @Test
    void requiresQualityZeroRightEndpoint() {
        PointRuntimeConfig point = point(
                "P1", "ONLINE", "ANALOG", 1,
                BigDecimal.ZERO, new BigDecimal("100"));
        RawMinuteAggregate rightQ1 = generated(
                point, RIGHT_MINUTE, 20.0, 1, "TASK-Q1");
        when(pointConfigProvider.findAll()).thenReturn(List.of(point));

        service.fillFromRightEndpoints(List.of(rightQ1), FINALIZED_AT);

        verifyNoInteractions(minuteRepository, fillTaskRepository, eventPublisher);
    }

    @Test
    void filtersOfflineDigitalAndNonCalculationPointsBeforeTdengineQuery() {
        PointRuntimeConfig offline = point(
                "P1", "OFFLINE", "ANALOG", 1,
                BigDecimal.ZERO, new BigDecimal("100"));
        PointRuntimeConfig digital = point(
                "P2", "ONLINE", "DIGITAL", 1,
                BigDecimal.ZERO, new BigDecimal("100"));
        PointRuntimeConfig nonCalculation = point(
                "P3", "ONLINE", "ANALOG", 0,
                BigDecimal.ZERO, new BigDecimal("100"));
        when(pointConfigProvider.findAll()).thenReturn(
                List.of(offline, digital, nonCalculation));

        service.fillFromRightEndpoints(
                List.of(
                        real(offline, RIGHT_MINUTE, 10.0),
                        real(digital, RIGHT_MINUTE, 10.0),
                        real(nonCalculation, RIGHT_MINUTE, 10.0)),
                FINALIZED_AT);

        verifyNoInteractions(minuteRepository, fillTaskRepository, eventPublisher);
    }

    @Test
    void requiresNearestLeftEndpointToBeQualityZero() {
        PointRuntimeConfig point = point(
                "P1", "ONLINE", "ANALOG", 1,
                BigDecimal.ZERO, new BigDecimal("100"));
        long leftMinute = RIGHT_MINUTE - 2 * MINUTE_MILLIS;
        RawMinuteAggregate right = real(point, RIGHT_MINUTE, 20.0);
        when(pointConfigProvider.findAll()).thenReturn(List.of(point));
        when(minuteRepository.findRange(
                Set.of("P1"),
                RIGHT_MINUTE - 6 * MINUTE_MILLIS,
                RIGHT_MINUTE + MINUTE_MILLIS))
                .thenReturn(List.of(
                        generated(point, leftMinute, 10.0, 1, "OLD-Q1"),
                        right));

        service.fillFromRightEndpoints(List.of(right), FINALIZED_AT);

        verify(fillTaskRepository, never()).getOrCreate(any());
        verify(minuteRepository, never())
                .saveAllWithQualityPriority(anyList(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void skipsWholeGapWhenAnInterpolatedValueIsOutsidePointRange() {
        PointRuntimeConfig point = point(
                "P1", "ONLINE", "ANALOG", 1,
                BigDecimal.ZERO, new BigDecimal("15"));
        long leftMinute = RIGHT_MINUTE - 2 * MINUTE_MILLIS;
        RawMinuteAggregate right = real(point, RIGHT_MINUTE, 20.0);
        when(pointConfigProvider.findAll()).thenReturn(List.of(point));
        when(minuteRepository.findRange(
                Set.of("P1"),
                RIGHT_MINUTE - 6 * MINUTE_MILLIS,
                RIGHT_MINUTE + MINUTE_MILLIS))
                .thenReturn(List.of(
                        real(point, leftMinute, 20.0), right));

        service.fillFromRightEndpoints(List.of(right), FINALIZED_AT);

        verify(fillTaskRepository, never()).getOrCreate(any());
        verify(minuteRepository, never())
                .saveAllWithQualityPriority(anyList(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void keepsExistingQualityZeroOrOneAndPublishesOnlyActualWrite() {
        PointRuntimeConfig point = point(
                "P1", "ONLINE", "ANALOG", 1,
                BigDecimal.ZERO, new BigDecimal("100"));
        long leftMinute = RIGHT_MINUTE - 4 * MINUTE_MILLIS;
        RawMinuteAggregate left = real(point, leftMinute, 0.0);
        RawMinuteAggregate existingQ1 = generated(
                point, leftMinute + 2 * MINUTE_MILLIS, 20.0, 1, "OLD-Q1");
        RawMinuteAggregate right = real(point, RIGHT_MINUTE, 40.0);
        when(pointConfigProvider.findAll()).thenReturn(List.of(point));
        when(minuteRepository.findRange(
                Set.of("P1"),
                RIGHT_MINUTE - 6 * MINUTE_MILLIS,
                RIGHT_MINUTE + MINUTE_MILLIS))
                .thenReturn(List.of(left, existingQ1, right));
        stubTask("TASK-Q1");
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenAnswer(invocation -> {
                    List<RawMinuteAggregate> rows = invocation.getArgument(0);
                    return List.of(
                            result(rows.get(0),
                                    MinuteQualityWriteResult.Outcome.IDEMPOTENT),
                            result(rows.get(1),
                                    MinuteQualityWriteResult.Outcome.UPGRADED));
                });

        service.fillFromRightEndpoints(List.of(right), FINALIZED_AT);

        ArgumentCaptor<List<RawMinuteAggregate>> rowsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(minuteRepository).saveAllWithQualityPriority(
                rowsCaptor.capture(), isNull());
        assertThat(rowsCaptor.getValue())
                .extracting(RawMinuteAggregate::minuteStart)
                .containsExactly(
                        leftMinute + MINUTE_MILLIS,
                        leftMinute + 3 * MINUTE_MILLIS);
        ArgumentCaptor<HvacMinuteQualityReadyEvent> eventCaptor =
                ArgumentCaptor.forClass(HvacMinuteQualityReadyEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().minuteStart())
                .isEqualTo(leftMinute + 3 * MINUTE_MILLIS);
        assertThat(eventCaptor.getValue().affectedPointIds())
                .containsExactly("P1");
    }

    @Test
    void repeatedRightEndpointReusesDeterministicTaskAndDoesNotRepublishIdempotentRows() {
        PointRuntimeConfig point = point(
                "P1", "ONLINE", "ANALOG", 1,
                BigDecimal.ZERO, new BigDecimal("100"));
        long leftMinute = RIGHT_MINUTE - 2 * MINUTE_MILLIS;
        RawMinuteAggregate right = real(point, RIGHT_MINUTE, 20.0);
        when(pointConfigProvider.findAll()).thenReturn(List.of(point));
        when(minuteRepository.findRange(
                Set.of("P1"),
                RIGHT_MINUTE - 6 * MINUTE_MILLIS,
                RIGHT_MINUTE + MINUTE_MILLIS))
                .thenReturn(List.of(
                        real(point, leftMinute, 10.0), right));
        stubTask("TASK-Q1");
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenAnswer(invocation -> {
                    RawMinuteAggregate row =
                            invocation.<List<RawMinuteAggregate>>getArgument(0)
                                    .getFirst();
                    return List.of(result(
                            row, MinuteQualityWriteResult.Outcome.IDEMPOTENT));
                });

        service.fillFromRightEndpoints(List.of(right), FINALIZED_AT);
        service.fillFromRightEndpoints(List.of(right), FINALIZED_AT);

        ArgumentCaptor<BizDataQualityFillTask> taskCaptor =
                ArgumentCaptor.forClass(BizDataQualityFillTask.class);
        verify(fillTaskRepository, times(2)).getOrCreate(taskCaptor.capture());
        assertThat(taskCaptor.getAllValues())
                .extracting(BizDataQualityFillTask::getIdempotencyKey)
                .containsOnly("Q1:P1:" + leftMinute + ":" + RIGHT_MINUTE
                        + ":LINEAR_V1");
        verify(minuteRepository, times(2))
                .saveAllWithQualityPriority(anyList(), isNull());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void tdengineFailureRecordsEveryCandidateAndPublishesNothing() {
        PointRuntimeConfig point = point(
                "P1", "ONLINE", "ANALOG", 1,
                BigDecimal.ZERO, new BigDecimal("100"));
        long leftMinute = RIGHT_MINUTE - 3 * MINUTE_MILLIS;
        RawMinuteAggregate right = real(point, RIGHT_MINUTE, 30.0);
        when(pointConfigProvider.findAll()).thenReturn(List.of(point));
        when(minuteRepository.findRange(
                Set.of("P1"),
                RIGHT_MINUTE - 6 * MINUTE_MILLIS,
                RIGHT_MINUTE + MINUTE_MILLIS))
                .thenReturn(List.of(
                        real(point, leftMinute, 0.0), right));
        stubTask("TASK-FAIL");
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenThrow(new IllegalStateException("TDengine unavailable"));

        service.fillFromRightEndpoints(List.of(right), FINALIZED_AT);

        verify(fillTaskRepository).recordFailure(
                "TASK-FAIL", leftMinute + MINUTE_MILLIS, "TDengine unavailable");
        verify(fillTaskRepository).recordFailure(
                "TASK-FAIL", leftMinute + 2 * MINUTE_MILLIS,
                "TDengine unavailable");
        verify(fillTaskRepository, never()).reconcile(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void reconciliationFailureDoesNotSuppressSuccessfulMinuteReady() {
        PointRuntimeConfig point = point(
                "P1", "ONLINE", "ANALOG", 1,
                BigDecimal.ZERO, new BigDecimal("100"));
        long leftMinute = RIGHT_MINUTE - 2 * MINUTE_MILLIS;
        RawMinuteAggregate right = real(point, RIGHT_MINUTE, 20.0);
        when(pointConfigProvider.findAll()).thenReturn(List.of(point));
        when(minuteRepository.findRange(
                Set.of("P1"),
                RIGHT_MINUTE - 6 * MINUTE_MILLIS,
                RIGHT_MINUTE + MINUTE_MILLIS))
                .thenReturn(List.of(
                        real(point, leftMinute, 10.0), right));
        stubTask("TASK-RECONCILE");
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenAnswer(invocation -> writeResults(
                        invocation.getArgument(0)));
        org.mockito.Mockito.doThrow(
                        new IllegalStateException("mysql unavailable"))
                .when(fillTaskRepository)
                .reconcile(any(TaskReconciliation.class));

        service.fillFromRightEndpoints(List.of(right), FINALIZED_AT);

        verify(fillTaskRepository).markFirstApplied("TASK-RECONCILE");
        verify(eventPublisher).publishEvent(
                any(HvacMinuteQualityReadyEvent.class));
        verify(fillTaskRepository, never()).recordFailure(
                any(), anyLong(), any());
    }

    @Test
    void readyFailureRecordsTaskFailureBeforeAnyAppliedOrClosedState() {
        PointRuntimeConfig point = point(
                "P1", "ONLINE", "ANALOG", 1,
                BigDecimal.ZERO, new BigDecimal("100"));
        long leftMinute = RIGHT_MINUTE - 2 * MINUTE_MILLIS;
        RawMinuteAggregate right = real(point, RIGHT_MINUTE, 20.0);
        when(pointConfigProvider.findAll()).thenReturn(List.of(point));
        when(minuteRepository.findRange(
                Set.of("P1"),
                RIGHT_MINUTE - 6 * MINUTE_MILLIS,
                RIGHT_MINUTE + MINUTE_MILLIS))
                .thenReturn(List.of(
                        real(point, leftMinute, 10.0), right));
        stubTask("TASK-READY-FAIL");
        when(minuteRepository.saveAllWithQualityPriority(anyList(), isNull()))
                .thenAnswer(invocation -> writeResults(
                        invocation.getArgument(0)));
        org.mockito.Mockito.doThrow(
                        new IllegalStateException("formula unavailable"))
                .when(eventPublisher)
                .publishEvent(any(HvacMinuteQualityReadyEvent.class));

        service.fillFromRightEndpoints(List.of(right), FINALIZED_AT);

        verify(fillTaskRepository).recordFailure(
                "TASK-READY-FAIL",
                leftMinute + MINUTE_MILLIS,
                "formula unavailable");
        verify(fillTaskRepository, never()).markFirstApplied(anyString());
        verify(fillTaskRepository, never()).reconcile(any());
    }

    @Test
    void completionPublishesCurrentReadyBeforeStartingHistoricalBackfill() {
        DataPointConfigProvider configs =
                org.mockito.Mockito.mock(DataPointConfigProvider.class);
        HvacMinuteRepository minutes =
                org.mockito.Mockito.mock(HvacMinuteRepository.class);
        TypicalValueFillService typical =
                org.mockito.Mockito.mock(TypicalValueFillService.class);
        InterpolationFillService interpolation =
                org.mockito.Mockito.mock(InterpolationFillService.class);
        ApplicationEventPublisher publisher =
                org.mockito.Mockito.mock(ApplicationEventPublisher.class);
        PointRuntimeConfig point = point(
                "P1", "ONLINE", "ANALOG", 1,
                BigDecimal.ZERO, new BigDecimal("100"));
        RawMinuteAggregate right = real(point, RIGHT_MINUTE, 20.0);
        when(configs.findAll()).thenReturn(List.of(point));
        HvacMinuteQualityCompletionService completion =
                new HvacMinuteQualityCompletionService(
                        configs, minutes, typical, interpolation, publisher);

        completion.onMinuteFrozen(new HvacMinuteBatchFrozenEvent(
                RIGHT_MINUTE, FINALIZED_AT, false,
                Set.of("BLD001"), List.of(right)));

        InOrder order = inOrder(publisher, interpolation);
        order.verify(publisher).publishEvent(
                any(HvacMinuteQualityReadyEvent.class));
        order.verify(interpolation).fillFromRightEndpoints(
                List.of(right), FINALIZED_AT);
    }

    private void stubTask(String taskId) {
        when(fillTaskRepository.getOrCreate(any())).thenAnswer(invocation -> {
            BizDataQualityFillTask task = invocation.getArgument(0);
            task.setTaskId(taskId);
            task.setApplyStatus(FillApplyStatus.WAITING);
            return task;
        });
    }

    private List<MinuteQualityWriteResult> writeResults(
            List<RawMinuteAggregate> rows) {
        List<MinuteQualityWriteResult> results = new ArrayList<>();
        for (RawMinuteAggregate row : rows) {
            results.add(result(
                    row, MinuteQualityWriteResult.Outcome.INSERTED));
        }
        return results;
    }

    private MinuteQualityWriteResult result(
            RawMinuteAggregate row,
            MinuteQualityWriteResult.Outcome outcome) {
        return new MinuteQualityWriteResult(
                row.pointId(), row.minuteStart(), outcome, null, null);
    }

    private PointRuntimeConfig point(
            String pointId,
            String status,
            String dataType,
            int isForCalc,
            BigDecimal min,
            BigDecimal max) {
        return new PointRuntimeConfig(
                pointId, pointId, pointId, "BLD001", "GROUP001",
                "E1", "WCR1", "WCR", "MAIN", "TWin",
                dataType, "℃", status, isForCalc, min, max);
    }

    private RawMinuteAggregate real(
            PointRuntimeConfig point, long minute, double value) {
        return aggregate(point, minute, value, 1, 0,
                minute + 1_000L, minute + 2_000L, null);
    }

    private RawMinuteAggregate generated(
            PointRuntimeConfig point,
            long minute,
            double value,
            int quality,
            String taskId) {
        return aggregate(point, minute, value, 0, quality,
                null, null, taskId);
    }

    private RawMinuteAggregate aggregate(
            PointRuntimeConfig point,
            long minute,
            double value,
            int sampleCount,
            int quality,
            Long firstReceived,
            Long lastReceived,
            String taskId) {
        return new RawMinuteAggregate(
                point.pointId(), point.pointCode(), point.buildingId(),
                point.systemGroupId(), point.equipId(), point.equipCode(),
                point.familyCode(), point.componentCode(), point.suffixCode(),
                point.isForCalc(), minute, value, value, value,
                sampleCount, quality, firstReceived, lastReceived,
                FINALIZED_AT, taskId);
    }

    private LocalDateTime local(long epochMillis) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMillis), PROJECT_ZONE);
    }
}
