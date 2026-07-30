package com.platform.iot.dataquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.DataQualityProperties;
import com.platform.iot.aggregation.ManualRealMinuteAggregationService;
import com.platform.iot.dataquality.event.HvacMinuteQualityReadyEvent;
import com.platform.iot.dataquality.model.QualityEventSource;
import com.platform.iot.dataquality.model.RecalculationChunkStats;
import com.platform.iot.dataquality.model.RecalculationJobPhase;
import com.platform.iot.dataquality.model.RecalculationJobStatus;
import com.platform.iot.dataquality.model.RecalculationJobType;
import com.platform.iot.dataquality.model.entity.BizDataQualityRecalcJob;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacMinuteRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataQualityRecalculationServiceTest {

    private static final long MINUTE = 60_000L;
    private static final long BASE = 1_735_689_600_000L;
    private static final long NOW = BASE + 10_000_000L;
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    @Mock private RecalculationJobRepository jobRepository;
    @Mock private ManualRealMinuteAggregationService manualAggregator;
    @Mock private ManualQualitySelectionService selectionService;
    @Mock private HvacMinuteRepository minuteRepository;
    @Mock private DataPointConfigProvider pointConfigProvider;
    @Mock private ApplicationEventPublisher eventPublisher;

    private DataQualityRecalculationService service;

    @BeforeEach
    void setUp() {
        DataQualityProperties properties = new DataQualityProperties();
        properties.getInterpolation().setMaxGapMinutes(5);
        service = new DataQualityRecalculationService(
                jobRepository,
                manualAggregator,
                selectionService,
                minuteRepository,
                pointConfigProvider,
                eventPublisher,
                properties,
                new ObjectMapper());
    }

    @Test
    void executesOneBoundedChunkAndPublishesEveryTargetMinute() {
        BizDataQualityRecalcJob job = runningRangeJob(
                BASE, BASE + 3 * MINUTE, BASE, "[\"P1\",\"P2\"]");
        PointRuntimeConfig p1 = point("P1");
        PointRuntimeConfig p2 = point("P2");
        RawMinuteAggregate q0 = row(p1, BASE, 0, null);
        RawMinuteAggregate q1 = row(p1, BASE + MINUTE, 1, "Q1-TASK");
        RawMinuteAggregate q2 = row(p2, BASE + MINUTE, 2, "Q2-TASK");
        RecalculationChunkStats stats =
                new RecalculationChunkStats(2, 1, 1, 2);
        when(jobRepository.findById("JOB-1")).thenReturn(Optional.of(job));
        when(pointConfigProvider.findAll()).thenReturn(List.of(p2, p1));
        when(manualAggregator.aggregate(
                Set.of("P1", "P2"), BASE, BASE + 3 * MINUTE, NOW))
                .thenReturn(List.of(q0));
        when(selectionService.selectAndPersist(
                any(), any(), any(Long.class), any(Long.class),
                any(Long.class), any(Long.class), any(Long.class)))
                .thenReturn(new ManualQualitySelectionService.ChunkSelection(
                        List.of(q1), List.of(q2), List.of(), stats));

        service.processClaimedJob("JOB-1", NOW);

        InOrder order = inOrder(
                manualAggregator, minuteRepository,
                selectionService, eventPublisher, jobRepository);
        order.verify(manualAggregator).aggregate(
                Set.of("P1", "P2"), BASE, BASE + 3 * MINUTE, NOW);
        order.verify(minuteRepository)
                .saveAllWithQualityPriority(List.of(q0), null);
        order.verify(selectionService).selectAndPersist(
                job,
                java.util.Map.of("P1", p1, "P2", p2),
                BASE,
                BASE + 3 * MINUTE,
                BASE,
                BASE + 3 * MINUTE,
                NOW);

        ArgumentCaptor<HvacMinuteQualityReadyEvent> eventCaptor =
                ArgumentCaptor.forClass(HvacMinuteQualityReadyEvent.class);
        verify(eventPublisher, times(3)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .extracting(HvacMinuteQualityReadyEvent::minuteStart)
                .containsExactly(BASE, BASE + MINUTE, BASE + 2 * MINUTE);
        assertThat(eventCaptor.getAllValues()).allSatisfy(event -> {
            assertThat(event.source())
                    .isEqualTo(QualityEventSource.MANUAL_RECALCULATION);
            assertThat(event.buildingIds()).containsExactly("BLD001");
            assertThat(event.affectedPointIds())
                    .containsExactlyInAnyOrder("P1", "P2");
        });
        assertThat(eventCaptor.getAllValues().getLast().aggregates()).isEmpty();
        verify(jobRepository).advanceChunk(
                "JOB-1",
                local(BASE),
                local(BASE + 3 * MINUTE),
                stats,
                true,
                local(NOW));
        verify(jobRepository, never()).markFailed(any(), any(), any());
    }

    @Test
    void publisherFailureMarksUnchangedCursorAndStopsTheChunk() {
        BizDataQualityRecalcJob job = runningRangeJob(
                BASE, BASE + 4 * MINUTE, BASE, "[\"P1\"]");
        PointRuntimeConfig point = point("P1");
        when(jobRepository.findById("JOB-1")).thenReturn(Optional.of(job));
        when(pointConfigProvider.findAll()).thenReturn(List.of(point));
        when(manualAggregator.aggregate(
                Set.of("P1"), BASE, BASE + 4 * MINUTE, NOW))
                .thenReturn(List.of());
        when(selectionService.selectAndPersist(
                any(), any(), any(Long.class), any(Long.class),
                any(Long.class), any(Long.class), any(Long.class)))
                .thenReturn(new ManualQualitySelectionService.ChunkSelection(
                        List.of(), List.of(), List.of(),
                        new RecalculationChunkStats(0, 0, 0, 4)));
        doAnswer(invocation -> {
            HvacMinuteQualityReadyEvent event = invocation.getArgument(0);
            if (event.minuteStart() == BASE + MINUTE) {
                throw new IllegalStateException(
                        "JDBC jdbc:mysql://secret/internal_table");
            }
            return null;
        }).when(eventPublisher).publishEvent(any(Object.class));

        service.processClaimedJob("JOB-1", NOW);

        verify(eventPublisher, times(2)).publishEvent(any(Object.class));
        verify(jobRepository).markFailed(
                "JOB-1",
                local(BASE),
                DataQualityRecalculationService.EXECUTION_FAILURE_SUMMARY);
        verify(jobRepository, never()).advanceChunk(
                any(), any(), any(), any(), any(Boolean.class), any());
    }

    @Test
    void voidingIsRejectedBecauseSchedulerMustCompleteItFirst() {
        BizDataQualityRecalcJob voiding = runningVoidJob();
        when(jobRepository.findById("JOB-1"))
                .thenReturn(Optional.of(voiding));

        service.processClaimedJob("JOB-1", NOW);

        verify(jobRepository).markFailed(
                "JOB-1",
                local(BASE),
                DataQualityRecalculationService.EXECUTION_FAILURE_SUMMARY);
        verify(manualAggregator, never()).aggregate(
                any(), any(Long.class), any(Long.class), any(Long.class));
    }

    @Test
    void oneInvocationProcessesOnlyOneHourWithBoundedContext() {
        BizDataQualityRecalcJob job = runningRangeJob(
                BASE, BASE + 120 * MINUTE, BASE, "[\"P1\"]");
        PointRuntimeConfig point = point("P1");
        when(jobRepository.findById("JOB-1")).thenReturn(Optional.of(job));
        when(pointConfigProvider.findAll()).thenReturn(List.of(point));
        when(manualAggregator.aggregate(
                Set.of("P1"), BASE, BASE + 66 * MINUTE, NOW))
                .thenReturn(List.of());
        RecalculationChunkStats stats =
                new RecalculationChunkStats(0, 0, 0, 60);
        when(selectionService.selectAndPersist(
                any(), any(), any(Long.class), any(Long.class),
                any(Long.class), any(Long.class), any(Long.class)))
                .thenReturn(new ManualQualitySelectionService.ChunkSelection(
                        List.of(), List.of(), List.of(), stats));

        service.processClaimedJob("JOB-1", NOW);

        verify(manualAggregator, times(1)).aggregate(
                Set.of("P1"), BASE, BASE + 66 * MINUTE, NOW);
        verify(selectionService).selectAndPersist(
                job,
                java.util.Map.of("P1", point),
                BASE,
                BASE + 60 * MINUTE,
                BASE,
                BASE + 66 * MINUTE,
                NOW);
        verify(eventPublisher, times(60)).publishEvent(any(Object.class));
        verify(jobRepository).advanceChunk(
                "JOB-1",
                local(BASE),
                local(BASE + 60 * MINUTE),
                stats,
                false,
                local(NOW));
    }

    @Test
    void laterChunkDoesNotRewritePastContextQ0FinalizationTime() {
        long cursor = BASE + 60 * MINUTE;
        BizDataQualityRecalcJob job = runningRangeJob(
                BASE, BASE + 61 * MINUTE, cursor, "[\"P1\"]");
        PointRuntimeConfig point = point("P1");
        RawMinuteAggregate pastContext =
                row(point, cursor - MINUTE, 0, null);
        RawMinuteAggregate target =
                row(point, cursor, 0, null);
        when(jobRepository.findById("JOB-1")).thenReturn(Optional.of(job));
        when(pointConfigProvider.findAll()).thenReturn(List.of(point));
        when(manualAggregator.aggregate(
                Set.of("P1"),
                cursor - 6 * MINUTE,
                cursor + MINUTE,
                NOW))
                .thenReturn(List.of(pastContext, target));
        RecalculationChunkStats stats =
                new RecalculationChunkStats(1, 0, 0, 0);
        when(selectionService.selectAndPersist(
                any(), any(), any(Long.class), any(Long.class),
                any(Long.class), any(Long.class), any(Long.class)))
                .thenReturn(new ManualQualitySelectionService.ChunkSelection(
                        List.of(), List.of(), List.of(), stats));

        service.processClaimedJob("JOB-1", NOW);

        verify(minuteRepository)
                .saveAllWithQualityPriority(List.of(target), null);
        verify(selectionService).selectAndPersist(
                job,
                java.util.Map.of("P1", point),
                cursor,
                cursor + MINUTE,
                cursor - 6 * MINUTE,
                cursor + MINUTE,
                NOW);
        verify(eventPublisher, times(1))
                .publishEvent(any(Object.class));
        verify(jobRepository).advanceChunk(
                "JOB-1",
                local(cursor),
                local(cursor + MINUTE),
                stats,
                true,
                local(NOW));
    }

    @Test
    void changedOrIneligiblePointConfigurationFailsWithoutExternalWrites() {
        BizDataQualityRecalcJob job = runningRangeJob(
                BASE, BASE + MINUTE, BASE, "[\"P1\",\"P2\"]");
        when(jobRepository.findById("JOB-1")).thenReturn(Optional.of(job));
        when(pointConfigProvider.findAll()).thenReturn(List.of(point("P1")));

        service.processClaimedJob("JOB-1", NOW);

        verify(jobRepository).markFailed(
                "JOB-1",
                local(BASE),
                DataQualityRecalculationService.EXECUTION_FAILURE_SUMMARY);
        verify(manualAggregator, never()).aggregate(
                any(), any(Long.class), any(Long.class), any(Long.class));
        verify(minuteRepository, never())
                .saveAllWithQualityPriority(anyList(), isNull());
        verify(selectionService, never()).selectAndPersist(
                any(), any(), any(Long.class), any(Long.class),
                any(Long.class), any(Long.class), any(Long.class));
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        verify(jobRepository, never()).advanceChunk(
                any(), any(), any(), any(), any(Boolean.class), any());
    }

    private BizDataQualityRecalcJob runningRangeJob(
            long from,
            long to,
            long cursor,
            String pointIdsJson) {
        BizDataQualityRecalcJob job = new BizDataQualityRecalcJob();
        job.setJobId("JOB-1");
        job.setJobType(RecalculationJobType.RANGE_RECALCULATE);
        job.setBuildingId("BLD001");
        job.setPointIdsJson(pointIdsJson);
        job.setFromMinute(local(from));
        job.setToMinute(local(to));
        job.setCursorMinute(local(cursor));
        job.setStatus(RecalculationJobStatus.RUNNING);
        job.setPhase(RecalculationJobPhase.RECALCULATING);
        return job;
    }

    private BizDataQualityRecalcJob runningVoidJob() {
        BizDataQualityRecalcJob job = runningRangeJob(
                BASE, BASE + MINUTE, BASE, "[\"P1\"]");
        job.setJobType(RecalculationJobType.VOID_AND_RECALCULATE);
        job.setPhase(RecalculationJobPhase.VOIDING);
        job.setSupersedesTaskId("OLD-TASK");
        return job;
    }

    private PointRuntimeConfig point(String pointId) {
        return new PointRuntimeConfig(
                pointId,
                pointId,
                pointId,
                "BLD001",
                "GROUP001",
                "E1",
                "WCR1",
                "WCR",
                "MAIN",
                "TWin",
                "ANALOG",
                "℃",
                "ONLINE",
                1,
                BigDecimal.ZERO,
                new BigDecimal("100"));
    }

    private RawMinuteAggregate row(
            PointRuntimeConfig point,
            long minute,
            int quality,
            String taskId) {
        return new RawMinuteAggregate(
                point.pointId(),
                point.pointCode(),
                point.buildingId(),
                point.systemGroupId(),
                point.equipId(),
                point.equipCode(),
                point.familyCode(),
                point.componentCode(),
                point.suffixCode(),
                point.isForCalc(),
                minute,
                10.0,
                10.0,
                10.0,
                quality == 0 ? 1 : 0,
                quality,
                quality == 0 ? minute + 1_000L : null,
                quality == 0 ? minute + 2_000L : null,
                NOW,
                taskId);
    }

    private LocalDateTime local(long epochMillis) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMillis), PROJECT_ZONE);
    }
}
