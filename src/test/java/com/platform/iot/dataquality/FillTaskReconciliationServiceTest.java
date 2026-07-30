package com.platform.iot.dataquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.FillSourceType;
import com.platform.iot.dataquality.model.FillTaskEvidence;
import com.platform.iot.dataquality.model.TaskReconciliation;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FillTaskReconciliationServiceTest {

    private static final long HOUR = 1_800_000_000_000L;

    @Mock private FillTaskRepository fillTaskRepository;
    @Mock private HvacMinuteRepository minuteRepository;

    private FillTaskEvidenceCodec evidenceCodec;
    private FillTaskReconciliationService service;

    @BeforeEach
    void setUp() {
        evidenceCodec = new FillTaskEvidenceCodec(new ObjectMapper());
        service = new FillTaskReconciliationService(
                fillTaskRepository, evidenceCodec, minuteRepository);
    }

    @Test
    void rebuildsActualSegmentsAndCountsWithoutCountingWholeHour() {
        BizDataQualityFillTask task = typicalTask("Q2");
        task.setFailedCount(1);
        task.setReplacedCount(2);
        when(fillTaskRepository.findTypicalTasksToClose(
                any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(task));
        when(fillTaskRepository.findInterpolationTasksToClose(
                any(LocalDateTime.class), eq(100))).thenReturn(List.of());
        when(minuteRepository.findByQualityTaskId(
                eq("Q2"), eq("P1"), anyLong(), anyLong(), eq(61)))
                .thenReturn(List.of(
                        row(HOUR, "Q2"),
                        row(HOUR + 60_000L, "Q2"),
                        row(HOUR + 180_000L, "Q2")));

        service.reconcile(HOUR + 3_600_000L);

        ArgumentCaptor<TaskReconciliation> result =
                ArgumentCaptor.forClass(TaskReconciliation.class);
        verify(fillTaskRepository).reconcile(result.capture());
        assertThat(result.getValue().minuteCount()).isEqualTo(6);
        assertThat(result.getValue().appliedCount()).isEqualTo(3);
        assertThat(result.getValue().failedCount()).isEqualTo(1);
        assertThat(result.getValue().replacedCount()).isEqualTo(2);
        assertThat(result.getValue().applyStatus())
                .isEqualTo(FillApplyStatus.FAILED);
        assertThat(result.getValue().appliedSegments()).containsExactly(
                new FillTaskEvidence.MinuteSegment(
                        HOUR, HOUR + 120_000L),
                new FillTaskEvidence.MinuteSegment(
                        HOUR + 180_000L, HOUR + 240_000L));
    }

    @Test
    void marksTaskReplacedWhenNoOwnedMinutesRemain() {
        BizDataQualityFillTask task = typicalTask("Q2");
        task.setReplacedCount(2);
        when(fillTaskRepository.findTypicalTasksToClose(
                any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(task));
        when(fillTaskRepository.findInterpolationTasksToClose(
                any(LocalDateTime.class), eq(100))).thenReturn(List.of());
        when(minuteRepository.findByQualityTaskId(
                eq("Q2"), eq("P1"), anyLong(), anyLong(), eq(61)))
                .thenReturn(List.of());

        service.reconcile(HOUR + 3_600_000L);

        ArgumentCaptor<TaskReconciliation> result =
                ArgumentCaptor.forClass(TaskReconciliation.class);
        verify(fillTaskRepository).reconcile(result.capture());
        assertThat(result.getValue().applyStatus())
                .isEqualTo(FillApplyStatus.REPLACED);
        assertThat(result.getValue().minuteCount()).isEqualTo(2);
    }

    @Test
    void reconcilesAppliedQ1WhoseClosedAtWasNotWritten() {
        BizDataQualityFillTask task = interpolationTask("Q1");
        when(fillTaskRepository.findTypicalTasksToClose(
                any(LocalDateTime.class), eq(100))).thenReturn(List.of());
        when(fillTaskRepository.findInterpolationTasksToClose(
                any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(task));
        when(minuteRepository.findByQualityTaskId(
                eq("Q1"), eq("P1"), anyLong(), anyLong(), eq(61)))
                .thenReturn(List.of(row(HOUR + 60_000L, "Q1", 1)));

        service.reconcile(HOUR + 3_600_000L);

        ArgumentCaptor<TaskReconciliation> result =
                ArgumentCaptor.forClass(TaskReconciliation.class);
        verify(fillTaskRepository).reconcile(result.capture());
        assertThat(result.getValue().applyStatus())
                .isEqualTo(FillApplyStatus.APPLIED);
        assertThat(result.getValue().minuteCount()).isEqualTo(1);
        assertThat(result.getValue().appliedSegments()).isEmpty();
    }

    @Test
    void tdengineFailureLeavesTaskForNextRoundAndContinues() {
        BizDataQualityFillTask first = typicalTask("BAD");
        BizDataQualityFillTask second = typicalTask("GOOD");
        when(fillTaskRepository.findTypicalTasksToClose(
                any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(first, second));
        when(fillTaskRepository.findInterpolationTasksToClose(
                any(LocalDateTime.class), eq(100))).thenReturn(List.of());
        when(minuteRepository.findByQualityTaskId(
                eq("BAD"), eq("P1"), anyLong(), anyLong(), eq(61)))
                .thenThrow(new IllegalStateException("td down"));
        when(minuteRepository.findByQualityTaskId(
                eq("GOOD"), eq("P1"), anyLong(), anyLong(), eq(61)))
                .thenReturn(List.of(row(HOUR, "GOOD")));

        service.reconcile(HOUR + 3_600_000L);

        verify(fillTaskRepository, never()).reconcile(argThat(
                result -> result.taskId().equals("BAD")));
        verify(fillTaskRepository).reconcile(argThat(
                result -> result.taskId().equals("GOOD")));
    }

    private BizDataQualityFillTask typicalTask(String taskId) {
        BizDataQualityFillTask task = base(taskId);
        task.setSourceType(FillSourceType.TYPICAL_VALUE);
        task.setDataQuality(2);
        task.setEvidenceJson(evidenceCodec.encode(
                FillSourceType.TYPICAL_VALUE,
                new FillTaskEvidence.Typical(
                        "CFG", 1, BigDecimal.TEN, "℃",
                        HOUR, null, HOUR, TypicalValueFillService.ALGORITHM_VERSION,
                        List.of())));
        return task;
    }

    private BizDataQualityFillTask interpolationTask(String taskId) {
        BizDataQualityFillTask task = base(taskId);
        task.setSourceType(FillSourceType.INTERPOLATION);
        task.setDataQuality(1);
        task.setEvidenceJson(evidenceCodec.encode(
                FillSourceType.INTERPOLATION,
                new FillTaskEvidence.Interpolation(
                        HOUR, 10.0, HOUR + 120_000L, 20.0,
                        InterpolationFillService.ALGORITHM_VERSION)));
        return task;
    }

    private BizDataQualityFillTask base(String taskId) {
        BizDataQualityFillTask task = new BizDataQualityFillTask();
        task.setTaskId(taskId);
        task.setPointId("P1");
        task.setStartMinute(local(HOUR));
        task.setEndMinute(local(HOUR + 3_600_000L));
        task.setApplyStatus(FillApplyStatus.APPLIED);
        task.setFailedCount(0);
        task.setReplacedCount(0);
        task.setVoidedCount(0);
        return task;
    }

    private RawMinuteAggregate row(long minute, String taskId) {
        return row(minute, taskId, 2);
    }

    private RawMinuteAggregate row(long minute, String taskId, int quality) {
        return new RawMinuteAggregate(
                "P1", "P1", "B1", "G1", "E1", "E1",
                "WCR", "MAIN", "TWin", 1,
                minute, 10.0, 10.0, 10.0, 0, quality,
                null, null, minute + 90_000L, taskId);
    }

    private LocalDateTime local(long epochMillis) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMillis), ZoneId.of("Asia/Shanghai"));
    }
}
