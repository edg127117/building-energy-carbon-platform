package com.platform.iot.dataquality;

import com.platform.config.DataQualityProperties;
import com.platform.iot.dataquality.model.RecalculationJobPhase;
import com.platform.iot.dataquality.model.RecalculationJobStatus;
import com.platform.iot.dataquality.model.RecalculationJobType;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import com.platform.iot.dataquality.model.entity.BizDataQualityRecalcJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataQualityRecalculationSchedulerTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    @Mock
    private RecalculationJobRepository jobRepository;
    @Mock
    private FillTaskRepository fillTaskRepository;
    @Mock
    private RecalculationVoidService voidService;
    @Mock
    private DataQualityRecalculationService recalculationService;

    private DataQualityProperties properties;
    private DataQualityRecalculationScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new DataQualityProperties();
        properties.setRecalculationStaleMs(120_000L);
        scheduler = new DataQualityRecalculationScheduler(
                jobRepository,
                fillTaskRepository,
                voidService,
                recalculationService,
                properties);
    }

    @Test
    void scanUsesOneStaleWatermarkAndHandlesAtMostTenCandidates() {
        List<BizDataQualityRecalcJob> candidates = IntStream.range(0, 11)
                .mapToObj(index -> job("JOB-" + index,
                        RecalculationJobPhase.RECALCULATING))
                .toList();
        when(jobRepository.findClaimable(any(), eq(10)))
                .thenReturn(candidates);
        when(jobRepository.claim(eq("JOB-2"), any(), any()))
                .thenReturn(true);

        scheduler.runAt(NOW);

        LocalDateTime expectedNow = local(NOW);
        LocalDateTime expectedStale = expectedNow.minusSeconds(120);
        verify(jobRepository).findClaimable(expectedStale, 10);
        for (BizDataQualityRecalcJob candidate : candidates.subList(0, 10)) {
            verify(jobRepository).claim(
                    candidate.getJobId(), expectedStale, expectedNow);
        }
        verify(jobRepository, never()).claim(
                eq("JOB-10"), any(), any());
        verify(recalculationService).processClaimedJob("JOB-2", NOW);
        verify(recalculationService, times(1))
                .processClaimedJob(anyString(), eq(NOW));
    }

    @Test
    void onlyRepositoryReturnedStaleRunningJobCanBeClaimed() {
        BizDataQualityRecalcJob stale = job(
                "JOB-STALE", RecalculationJobPhase.RECALCULATING);
        stale.setStatus(RecalculationJobStatus.RUNNING);
        BizDataQualityRecalcJob fresh = job(
                "JOB-FRESH", RecalculationJobPhase.RECALCULATING);
        fresh.setStatus(RecalculationJobStatus.RUNNING);
        when(jobRepository.findClaimable(any(), eq(10)))
                .thenReturn(List.of(stale));
        when(jobRepository.claim(eq("JOB-STALE"), any(), any()))
                .thenReturn(true);

        scheduler.runAt(NOW);

        verify(jobRepository).claim(eq("JOB-STALE"), any(), any());
        verify(jobRepository, never()).claim(
                eq(fresh.getJobId()), any(), any());
        verify(recalculationService)
                .processClaimedJob("JOB-STALE", NOW);
    }

    @Test
    void voidingCompletesBeforeRecalculationStarts() {
        BizDataQualityRecalcJob job =
                job("JOB-VOID", RecalculationJobPhase.VOIDING);
        job.setJobType(RecalculationJobType.VOID_AND_RECALCULATE);
        job.setSupersedesTaskId("TASK-OLD");
        BizDataQualityFillTask oldTask = new BizDataQualityFillTask();
        oldTask.setTaskId("TASK-OLD");
        when(jobRepository.findClaimable(any(), eq(10)))
                .thenReturn(List.of(job));
        when(jobRepository.claim(eq("JOB-VOID"), any(), any()))
                .thenReturn(true);
        when(fillTaskRepository.findById("TASK-OLD"))
                .thenReturn(Optional.of(oldTask));

        scheduler.runAt(NOW);

        InOrder order = inOrder(voidService, recalculationService);
        order.verify(voidService).voidOldTask(job, oldTask, NOW);
        order.verify(recalculationService)
                .processClaimedJob("JOB-VOID", NOW);
    }

    @Test
    void oneJobFailureAndRepeatedMarkFailureDoNotStopLaterJobs() {
        BizDataQualityRecalcJob first =
                job("JOB-1", RecalculationJobPhase.RECALCULATING);
        BizDataQualityRecalcJob second =
                job("JOB-2", RecalculationJobPhase.RECALCULATING);
        when(jobRepository.findClaimable(any(), eq(10)))
                .thenReturn(List.of(first, second));
        when(jobRepository.claim(anyString(), any(), any()))
                .thenReturn(true);
        doThrow(new IllegalStateException("internal evidence"))
                .when(recalculationService)
                .processClaimedJob("JOB-1", NOW);
        doThrow(new IllegalStateException("already failed"))
                .when(jobRepository)
                .markFailed(
                        "JOB-1",
                        first.getCursorMinute(),
                        "人工重算批次执行失败");

        scheduler.runAt(NOW);

        verify(jobRepository).markFailed(
                "JOB-1",
                first.getCursorMinute(),
                "人工重算批次执行失败");
        verify(recalculationService)
                .processClaimedJob("JOB-2", NOW);
    }

    @Test
    void nonFinalJobCanReturnToWaitingAndRunInALaterScan() {
        BizDataQualityRecalcJob job =
                job("JOB-CHUNK", RecalculationJobPhase.RECALCULATING);
        when(jobRepository.findClaimable(any(), eq(10)))
                .thenReturn(List.of(job), List.of(job));
        when(jobRepository.claim(eq("JOB-CHUNK"), any(), any()))
                .thenReturn(true, true);

        scheduler.runAt(NOW);
        scheduler.runAt(NOW + 10_000L);

        verify(recalculationService, times(2))
                .processClaimedJob(eq("JOB-CHUNK"), anyLong());
    }

    @Test
    void bothFeatureFlagsAreRequiredForConditionalRegistration() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(RecalculationJobRepository.class,
                        () -> mock(RecalculationJobRepository.class))
                .withBean(FillTaskRepository.class,
                        () -> mock(FillTaskRepository.class))
                .withBean(RecalculationVoidService.class,
                        () -> mock(RecalculationVoidService.class))
                .withBean(DataQualityRecalculationService.class,
                        () -> mock(DataQualityRecalculationService.class))
                .withBean(DataQualityProperties.class,
                        DataQualityProperties::new)
                .withUserConfiguration(
                        DataQualityRecalculationScheduler.class);

        runner.withPropertyValues(
                        "data-quality.enabled=true",
                        "data-quality.recalculation-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(
                        DataQualityRecalculationScheduler.class));
        runner.withPropertyValues(
                        "data-quality.enabled=false",
                        "data-quality.recalculation-enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(
                        DataQualityRecalculationScheduler.class));
        runner.withPropertyValues(
                        "data-quality.enabled=true",
                        "data-quality.recalculation-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(
                        DataQualityRecalculationScheduler.class));
    }

    private BizDataQualityRecalcJob job(
            String jobId,
            RecalculationJobPhase phase) {
        BizDataQualityRecalcJob job = new BizDataQualityRecalcJob();
        job.setJobId(jobId);
        job.setJobType(RecalculationJobType.RANGE_RECALCULATE);
        job.setStatus(RecalculationJobStatus.WAITING);
        job.setPhase(phase);
        job.setCursorMinute(local(NOW).minusMinutes(1));
        return job;
    }

    private LocalDateTime local(long epochMillis) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMillis), PROJECT_ZONE);
    }
}
