package com.platform.iot.dataquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.RecalculationJobPhase;
import com.platform.iot.dataquality.model.RecalculationJobStatus;
import com.platform.iot.dataquality.model.RecalculationJobType;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import com.platform.iot.dataquality.model.entity.BizDataQualityRecalcJob;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecalculationVoidServiceTest {

    private static final long MINUTE = 1_800_000L;
    private static final long NOW = 2_400_000L;

    @Mock private HvacMinuteRepository minuteRepository;
    @Mock private FillTaskRepository fillTaskRepository;
    @Mock private RecalculationJobRepository jobRepository;

    private RecalculationVoidService service;

    @BeforeEach
    void setUp() {
        service = new RecalculationVoidService(
                minuteRepository,
                fillTaskRepository,
                jobRepository,
                new ObjectMapper());
    }

    @Test
    void freezesOnlyOldOwnedTargetsBeforeDeleteAndClosesExactCounts() {
        BizDataQualityRecalcJob job = job(null);
        BizDataQualityFillTask oldTask = oldTask(4, 1);
        RawMinuteAggregate oldOwned =
                minute(MINUTE, 2, "OLD_TASK");
        RawMinuteAggregate real =
                minute(MINUTE + 60_000L, 0, null);
        RawMinuteAggregate anotherTask =
                minute(MINUTE + 120_000L, 1, "OTHER_TASK");
        when(minuteRepository.findRange(
                Set.of("POINT001"), MINUTE, MINUTE + 240_000L))
                .thenReturn(
                        List.of(oldOwned, real, anotherTask),
                        List.of(real, anotherTask));
        when(minuteRepository.deleteIfOwnedByTask(
                "POINT001", MINUTE, "OLD_TASK")).thenReturn(true);

        RecalculationVoidService.VoidResult result =
                service.voidOldTask(job, oldTask, NOW);

        assertThat(result).isEqualTo(
                new RecalculationVoidService.VoidResult(1, 2));
        InOrder order = inOrder(jobRepository, minuteRepository, fillTaskRepository);
        order.verify(jobRepository).freezeVoidTargets("JOB001", "[" + MINUTE + "]");
        order.verify(minuteRepository).deleteIfOwnedByTask(
                "POINT001", MINUTE, "OLD_TASK");
        order.verify(fillTaskRepository).markVoidedExact(
                "OLD_TASK", 99L, "错误配置", local(NOW),
                4, 1, 2, 1);
        order.verify(jobRepository).completeVoid("JOB001", 1, 2);
    }

    @Test
    void deleteRaceIsReclassifiedAsReplacementWithoutDeletingNewOwner() {
        BizDataQualityRecalcJob job = job(null);
        BizDataQualityFillTask oldTask = oldTask(1, 0);
        RawMinuteAggregate oldOwned = minute(MINUTE, 2, "OLD_TASK");
        RawMinuteAggregate replacement = minute(MINUTE, 1, "NEW_TASK");
        when(minuteRepository.findRange(
                Set.of("POINT001"), MINUTE, MINUTE + 60_000L))
                .thenReturn(List.of(oldOwned), List.of(replacement));
        when(minuteRepository.deleteIfOwnedByTask(
                "POINT001", MINUTE, "OLD_TASK")).thenReturn(false);

        RecalculationVoidService.VoidResult result =
                service.voidOldTask(job, oldTask, NOW);

        assertThat(result).isEqualTo(
                new RecalculationVoidService.VoidResult(0, 1));
        verify(fillTaskRepository).markVoidedExact(
                "OLD_TASK", 99L, "错误配置", local(NOW),
                1, 0, 1, 0);
    }

    @Test
    void restartUsesFrozenTargetsAndCountsAnAbsentTargetAsAlreadyVoided() {
        BizDataQualityRecalcJob job = job("[" + MINUTE + "]");
        BizDataQualityFillTask oldTask = oldTask(1, 0);
        when(minuteRepository.deleteIfOwnedByTask(
                "POINT001", MINUTE, "OLD_TASK")).thenReturn(false);
        when(minuteRepository.findRange(
                Set.of("POINT001"), MINUTE, MINUTE + 60_000L))
                .thenReturn(List.of());

        RecalculationVoidService.VoidResult result =
                service.voidOldTask(job, oldTask, NOW);

        assertThat(result).isEqualTo(
                new RecalculationVoidService.VoidResult(1, 0));
        verify(jobRepository, never()).freezeVoidTargets(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(minuteRepository).deleteIfOwnedByTask(
                "POINT001", MINUTE, "OLD_TASK");
    }

    @Test
    void restartRetriesATargetThatIsStillOwnedByTheOldTask() {
        BizDataQualityRecalcJob job = job("[" + MINUTE + "]");
        BizDataQualityFillTask oldTask = oldTask(1, 0);
        when(minuteRepository.deleteIfOwnedByTask(
                "POINT001", MINUTE, "OLD_TASK")).thenReturn(true);
        when(minuteRepository.findRange(
                Set.of("POINT001"), MINUTE, MINUTE + 60_000L))
                .thenReturn(List.of());

        service.voidOldTask(job, oldTask, NOW);

        verify(minuteRepository).deleteIfOwnedByTask(
                "POINT001", MINUTE, "OLD_TASK");
        verify(fillTaskRepository).markVoidedExact(
                "OLD_TASK", 99L, "错误配置", local(NOW),
                1, 0, 0, 1);
    }

    @Test
    void sparseTypicalTaskDoesNotCountUnrelatedHourRowsAsReplacements() {
        BizDataQualityRecalcJob job = job(null);
        BizDataQualityFillTask oldTask = oldTask(1, 0);
        oldTask.setEndMinute(local(MINUTE + 60 * 60_000L));
        RawMinuteAggregate oldOwned = minute(MINUTE, 2, "OLD_TASK");
        List<RawMinuteAggregate> unrelated = java.util.stream.LongStream
                .range(1, 60)
                .mapToObj(offset -> minute(
                        MINUTE + offset * 60_000L, 0, null))
                .toList();
        List<RawMinuteAggregate> before = new java.util.ArrayList<>();
        before.add(oldOwned);
        before.addAll(unrelated);
        when(minuteRepository.findRange(
                Set.of("POINT001"), MINUTE, MINUTE + 60 * 60_000L))
                .thenReturn(before, unrelated);
        when(minuteRepository.deleteIfOwnedByTask(
                "POINT001", MINUTE, "OLD_TASK")).thenReturn(true);

        RecalculationVoidService.VoidResult result =
                service.voidOldTask(job, oldTask, NOW);

        assertThat(result).isEqualTo(
                new RecalculationVoidService.VoidResult(1, 0));
        verify(fillTaskRepository).markVoidedExact(
                "OLD_TASK", 99L, "错误配置", local(NOW),
                1, 0, 0, 1);
    }

    @Test
    void hotTypicalTaskUsesFrozenOwnedMinutesWhenHourlyCountIsStillZero() {
        BizDataQualityRecalcJob job = job(null);
        BizDataQualityFillTask oldTask = oldTask(0, 0);
        oldTask.setEndMinute(local(MINUTE + 60 * 60_000L));
        RawMinuteAggregate first = minute(MINUTE, 2, "OLD_TASK");
        RawMinuteAggregate second =
                minute(MINUTE + 120_000L, 2, "OLD_TASK");
        when(minuteRepository.findRange(
                Set.of("POINT001"), MINUTE, MINUTE + 60 * 60_000L))
                .thenReturn(List.of(first, second), List.of());
        when(minuteRepository.deleteIfOwnedByTask(
                "POINT001", MINUTE, "OLD_TASK")).thenReturn(true);
        when(minuteRepository.deleteIfOwnedByTask(
                "POINT001", MINUTE + 120_000L, "OLD_TASK")).thenReturn(true);

        RecalculationVoidService.VoidResult result =
                service.voidOldTask(job, oldTask, NOW);

        assertThat(result).isEqualTo(
                new RecalculationVoidService.VoidResult(2, 0));
        verify(fillTaskRepository).markVoidedExact(
                "OLD_TASK", 99L, "错误配置", local(NOW),
                2, 0, 0, 2);
    }

    @Test
    void tdengineFailureIsLoggedServerSideButExposesOnlyFixedJobError() {
        BizDataQualityRecalcJob job = job("[" + MINUTE + "]");
        BizDataQualityFillTask oldTask = oldTask(1, 0);
        when(minuteRepository.deleteIfOwnedByTask(
                "POINT001", MINUTE, "OLD_TASK"))
                .thenThrow(new RuntimeException(
                        "JDBC jdbc:TAOS://internal-host SELECT *"));

        assertThatThrownBy(() -> service.voidOldTask(job, oldTask, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TDengine作废操作失败")
                .hasMessageNotContaining("JDBC")
                .hasMessageNotContaining("internal-host")
                .hasMessageNotContaining("SELECT");
        verify(fillTaskRepository, never()).markVoidedExact(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
        verify(jobRepository, never()).completeVoid(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    private BizDataQualityRecalcJob job(String frozenTargets) {
        BizDataQualityRecalcJob job = new BizDataQualityRecalcJob();
        job.setJobId("JOB001");
        job.setJobType(RecalculationJobType.VOID_AND_RECALCULATE);
        job.setBuildingId("BLD001");
        job.setSupersedesTaskId("OLD_TASK");
        job.setReason("错误配置");
        job.setOperatorId(99L);
        job.setStatus(RecalculationJobStatus.RUNNING);
        job.setPhase(RecalculationJobPhase.VOIDING);
        job.setFromMinute(local(MINUTE));
        job.setToMinute(local(MINUTE + 60_000L));
        job.setCursorMinute(local(MINUTE));
        job.setVoidTargetMinutesJson(frozenTargets);
        return job;
    }

    private BizDataQualityFillTask oldTask(int minuteCount, int failedCount) {
        BizDataQualityFillTask task = new BizDataQualityFillTask();
        task.setTaskId("OLD_TASK");
        task.setBuildingId("BLD001");
        task.setPointId("POINT001");
        task.setStartMinute(local(MINUTE));
        task.setEndMinute(local(MINUTE + minuteCount * 60_000L));
        task.setMinuteCount(minuteCount);
        task.setFailedCount(failedCount);
        task.setReplacedCount(0);
        task.setVoidedCount(0);
        task.setApplyStatus(
                failedCount > 0 ? FillApplyStatus.FAILED : FillApplyStatus.APPLIED);
        return task;
    }

    private RawMinuteAggregate minute(
            long minuteStart, int quality, String taskId) {
        return new RawMinuteAggregate(
                "POINT001", "POINT001", "BLD001", null, null, null,
                "WCR", "MAIN", "TWin", 1, minuteStart,
                10D, 10D, 10D, 1, quality,
                null, null, minuteStart + 60_000L, taskId);
    }

    private LocalDateTime local(long epochMillis) {
        return LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMillis),
                java.time.ZoneId.of("Asia/Shanghai"));
    }
}
