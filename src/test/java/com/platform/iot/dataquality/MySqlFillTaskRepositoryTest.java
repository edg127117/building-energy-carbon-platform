package com.platform.iot.dataquality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.dataquality.mapper.BizDataQualityFillTaskMapper;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.FillSourceType;
import com.platform.iot.dataquality.model.FillTaskEvidence;
import com.platform.iot.dataquality.model.TaskReconciliation;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MySqlFillTaskRepositoryTest {

    @Mock private BizDataQualityFillTaskMapper mapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MySqlFillTaskRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MySqlFillTaskRepository(
                mapper, new FillTaskEvidenceCodec(objectMapper), objectMapper);
    }

    @Test
    void getOrCreateReturnsTheSameTaskForOneIdempotencyKey() {
        AtomicReference<BizDataQualityFillTask> stored = new AtomicReference<>();
        when(mapper.selectByIdempotencyKey("Q2:POINT001:CONFIG001:1:3600000"))
                .thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(mapper).insert(any(BizDataQualityFillTask.class));
        BizDataQualityFillTask firstCandidate = typicalCandidate();
        BizDataQualityFillTask secondCandidate = typicalCandidate();

        BizDataQualityFillTask first = repository.getOrCreate(firstCandidate);
        BizDataQualityFillTask second = repository.getOrCreate(secondCandidate);

        assertThat(first.getTaskId()).isNotBlank().hasSize(32);
        assertThat(second.getTaskId()).isEqualTo(first.getTaskId());
        verify(mapper).insert(firstCandidate);
        verify(mapper, never()).insert(secondCandidate);
    }

    @Test
    void duplicateInsertUsesLockingReadToSeeConcurrentTask() {
        BizDataQualityFillTask candidate = typicalCandidate();
        BizDataQualityFillTask concurrent = typicalCandidate();
        concurrent.setTaskId("CONCURRENT");
        when(mapper.selectByIdempotencyKey(candidate.getIdempotencyKey()))
                .thenReturn(null);
        when(mapper.insert(candidate)).thenThrow(new DuplicateKeyException("duplicate"));
        when(mapper.selectByIdempotencyKeyForUpdate(candidate.getIdempotencyKey()))
                .thenReturn(concurrent);

        BizDataQualityFillTask actual = repository.getOrCreate(candidate);

        assertThat(actual).isSameAs(concurrent);
        verify(mapper).selectByIdempotencyKeyForUpdate(
                candidate.getIdempotencyKey());
    }

    @Test
    void firstSuccessUsesOneAtomicWaitingToAppliedUpdate() {
        when(mapper.markFirstApplied("TASK001")).thenReturn(1);

        repository.markFirstApplied("TASK001");
        repository.markFirstApplied("TASK001");

        verify(mapper, org.mockito.Mockito.times(2)).markFirstApplied("TASK001");
    }

    @Test
    void lateRealReplacementUsesAtomicIncrement() {
        when(mapper.incrementReplacedCountAtomic("TASK001", 1))
                .thenReturn(1);

        repository.incrementReplacedCount("TASK001", 1);

        verify(mapper).incrementReplacedCountAtomic("TASK001", 1);
    }

    @Test
    void failureIsRecordedImmediatelyAndKeepsAtMostSixtyMinutes() throws Exception {
        BizDataQualityFillTask task = typicalCandidate();
        task.setTaskId("TASK001");
        // 防御性验证 60 项上限时，构造一个多一分钟的历史异常任务范围。
        task.setEndMinute(LocalDateTime.of(1970, 1, 1, 10, 1));
        when(mapper.selectByTaskIdForUpdate("TASK001")).thenReturn(task);
        doAnswer(invocation -> {
            task.setFailedMinutesJson(invocation.getArgument(1));
            task.setFailedCount(invocation.getArgument(2));
            task.setLastError(invocation.getArgument(3));
            task.setApplyStatus(FillApplyStatus.FAILED);
            return 1;
        }).when(mapper).recordFailureAtomic(
                anyString(), anyString(), anyInt(), anyString());

        for (int index = 0; index < 61; index++) {
            repository.recordFailure(
                    "TASK001", 3_600_000L + index * 60_000L, "write-" + index);
        }

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(mapper, org.mockito.Mockito.times(61)).recordFailureAtomic(
                org.mockito.ArgumentMatchers.eq("TASK001"),
                jsonCaptor.capture(),
                anyInt(),
                anyString());
        JsonNode failures = objectMapper.readTree(
                jsonCaptor.getAllValues().getLast());
        assertThat(failures.size()).isEqualTo(60);
        assertThat(failures.get(0).get("minuteStart").asLong())
                .isEqualTo(3_660_000L);
        assertThat(failures.get(59).get("error").asText())
                .isEqualTo("write-60");
        assertThat(task.getRetryCount()).isZero();
    }

    @Test
    void reconciliationAndVoidDelegateToAtomicMapperUpdates() {
        BizDataQualityFillTask task = typicalCandidate();
        task.setTaskId("TASK001");
        when(mapper.selectByTaskIdForUpdate("TASK001")).thenReturn(task);
        when(mapper.reconcileAtomic(
                anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(),
                any(FillApplyStatus.class), anyString(), any(LocalDateTime.class)))
                .thenReturn(1);
        LocalDateTime closedAt = LocalDateTime.of(2026, 7, 29, 12, 5);
        TaskReconciliation reconciliation = new TaskReconciliation(
                "TASK001", 2, 2, 0, 0, 0, FillApplyStatus.APPLIED,
                List.of(new FillTaskEvidence.MinuteSegment(
                        3_600_000L, 3_720_000L)),
                closedAt);

        repository.reconcile(reconciliation);
        repository.markVoided(
                "TASK001", 99L, "典型值配置错误",
                LocalDateTime.of(2026, 7, 29, 12, 10));

        verify(mapper).reconcileAtomic(
                org.mockito.ArgumentMatchers.eq("TASK001"),
                org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(FillApplyStatus.APPLIED),
                org.mockito.ArgumentMatchers.contains("appliedSegments"),
                org.mockito.ArgumentMatchers.eq(closedAt));
        verify(mapper).markVoidedAtomic(
                org.mockito.ArgumentMatchers.eq("TASK001"),
                org.mockito.ArgumentMatchers.eq(99L),
                org.mockito.ArgumentMatchers.eq("典型值配置错误"),
                any(LocalDateTime.class));
    }

    @Test
    void lateFailureAndReconciliationCannotReviveTerminalTask() {
        BizDataQualityFillTask voided = typicalCandidate();
        voided.setTaskId("TASK001");
        voided.setApplyStatus(FillApplyStatus.VOIDED);
        when(mapper.selectByTaskIdForUpdate("TASK001")).thenReturn(voided);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        repository.recordFailure("TASK001", 1_800_000L, "late"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("终态");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        repository.reconcile(new TaskReconciliation(
                                "TASK001", 1, 1, 0, 0, 0,
                                FillApplyStatus.APPLIED, List.of(),
                                LocalDateTime.of(2026, 7, 29, 12, 5))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("终态");

        verify(mapper, never()).recordFailureAtomic(
                anyString(), anyString(), anyInt(), anyString());
        verify(mapper, never()).reconcileAtomic(
                anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(),
                any(FillApplyStatus.class), anyString(), any(LocalDateTime.class));
    }

    @Test
    void reconciliationRejectsWaitingAndVoidedTargets() {
        BizDataQualityFillTask waiting = typicalCandidate();
        waiting.setTaskId("TASK001");
        for (FillApplyStatus target : List.of(
                FillApplyStatus.WAITING, FillApplyStatus.VOIDED)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            repository.reconcile(new TaskReconciliation(
                                    "TASK001", 1, 1, 0, 0, 0, target,
                                    List.of(), LocalDateTime.of(
                                    2026, 7, 29, 12, 5))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("收口状态");
        }
    }

    @Test
    void repositoryDependsOnTheMysqlMapperInsteadOfTdengineJdbcTemplate() {
        assertThat(Arrays.stream(
                        MySqlFillTaskRepository.class.getDeclaredFields())
                .anyMatch(field ->
                        field.getType() == BizDataQualityFillTaskMapper.class))
                .isTrue();
        assertThat(Arrays.stream(
                        MySqlFillTaskRepository.class.getDeclaredFields())
                .noneMatch(field ->
                        JdbcTemplate.class.isAssignableFrom(field.getType())))
                .isTrue();
    }

    private BizDataQualityFillTask typicalCandidate() {
        BizDataQualityFillTask task = new BizDataQualityFillTask();
        task.setIdempotencyKey("Q2:POINT001:CONFIG001:1:3600000");
        task.setBuildingId("BUILDING001");
        task.setPointId("POINT001");
        task.setStartMinute(LocalDateTime.of(1970, 1, 1, 9, 0));
        task.setEndMinute(LocalDateTime.of(1970, 1, 1, 10, 0));
        task.setMinuteCount(60);
        task.setDataQuality(2);
        task.setSourceType(FillSourceType.TYPICAL_VALUE);
        task.setAlgorithmVersion("typical-v1");
        task.setEvidenceJson(new FillTaskEvidenceCodec(objectMapper).encode(
                FillSourceType.TYPICAL_VALUE,
                new FillTaskEvidence.Typical(
                        "CONFIG001", 1, java.math.BigDecimal.valueOf(18D), "℃",
                        0L, null, 3_600_000L, "typical-v1", List.of())));
        task.setTypicalConfigId("CONFIG001");
        task.setTypicalConfigVersion(1);
        task.setApplyStatus(FillApplyStatus.WAITING);
        task.setAppliedCount(0);
        task.setFailedCount(0);
        task.setReplacedCount(0);
        task.setVoidedCount(0);
        task.setRetryCount(0);
        task.setGeneratedAt(LocalDateTime.now());
        return task;
    }
}
