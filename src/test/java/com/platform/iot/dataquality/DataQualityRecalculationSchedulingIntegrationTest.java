package com.platform.iot.dataquality;

import com.platform.config.AsyncConfig;
import com.platform.config.DataQualityProperties;
import com.platform.iot.dataquality.model.RecalculationJobPhase;
import com.platform.iot.dataquality.model.RecalculationJobStatus;
import com.platform.iot.dataquality.model.RecalculationJobType;
import com.platform.iot.dataquality.model.entity.BizDataQualityRecalcJob;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataQualityRecalculationSchedulingIntegrationTest {

    @Test
    void scheduledScanActuallyClaimsAndExecutesOnIsolatedThreads() {
        RecalculationJobRepository jobRepository =
                mock(RecalculationJobRepository.class);
        DataQualityRecalculationService recalculationService =
                mock(DataQualityRecalculationService.class);
        CountDownLatch completed = new CountDownLatch(1);
        Queue<String> scanThreads = new ConcurrentLinkedQueue<>();
        Queue<String> workerThreads = new ConcurrentLinkedQueue<>();
        AtomicBoolean firstScan = new AtomicBoolean(true);
        BizDataQualityRecalcJob job = waitingJob();

        when(jobRepository.findClaimable(any(), anyInt()))
                .thenAnswer(invocation -> {
                    scanThreads.add(Thread.currentThread().getName());
                    return firstScan.getAndSet(false)
                            ? List.of(job)
                            : List.of();
                });
        when(jobRepository.claim(anyString(), any(), any()))
                .thenReturn(true);
        doAnswer(invocation -> {
            workerThreads.add(Thread.currentThread().getName());
            completed.countDown();
            return null;
        }).when(recalculationService)
                .processClaimedJob(anyString(), anyLong());

        new ApplicationContextRunner()
                .withPropertyValues(
                        "data-quality.enabled=true",
                        "data-quality.recalculation-enabled=true",
                        "data-quality.recalculation-scan-delay-ms=25",
                        "scheduling.business-pool-size=2")
                .withBean(DataQualityProperties.class,
                        DataQualityProperties::new)
                .withBean(RecalculationJobRepository.class,
                        () -> jobRepository)
                .withBean(FillTaskRepository.class,
                        () -> mock(FillTaskRepository.class))
                .withBean(RecalculationVoidService.class,
                        () -> mock(RecalculationVoidService.class))
                .withBean(DataQualityRecalculationService.class,
                        () -> recalculationService)
                .withUserConfiguration(
                        AsyncConfig.class,
                        DataQualityRecalculationScheduler.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
                    assertThat(scanThreads)
                            .allMatch(name -> name.startsWith(
                                    "iot-recalculation-scan-"));
                    assertThat(workerThreads)
                            .containsExactly("iot-recalculation-1");
                });
    }

    private BizDataQualityRecalcJob waitingJob() {
        BizDataQualityRecalcJob job = new BizDataQualityRecalcJob();
        job.setJobId("JOB-SCHEDULED");
        job.setJobType(RecalculationJobType.RANGE_RECALCULATE);
        job.setStatus(RecalculationJobStatus.WAITING);
        job.setPhase(RecalculationJobPhase.RECALCULATING);
        job.setCursorMinute(LocalDateTime.of(2026, 8, 12, 11, 0));
        return job;
    }
}
