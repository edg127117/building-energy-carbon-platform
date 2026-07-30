package com.platform.iot.dataquality;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.platform.iot.dataquality.mapper.BizDataQualityRecalcJobMapper;
import com.platform.iot.dataquality.model.RecalculationChunkStats;
import com.platform.iot.dataquality.model.RecalculationJobPhase;
import com.platform.iot.dataquality.model.RecalculationJobStatus;
import com.platform.iot.dataquality.model.RecalculationJobType;
import com.platform.iot.dataquality.model.entity.BizDataQualityRecalcJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 人工重算任务 MySQL 仓储的状态机和幂等语义测试。
 */
@ExtendWith(MockitoExtension.class)
class MySqlRecalculationJobRepositoryTest {

    private static final LocalDateTime FROM =
            LocalDateTime.of(2026, 7, 29, 10, 0);
    private static final LocalDateTime TO =
            LocalDateTime.of(2026, 7, 29, 12, 0);

    @Mock
    private BizDataQualityRecalcJobMapper mapper;

    private MySqlRecalculationJobRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MySqlRecalculationJobRepository(mapper);
    }

    @Test
    void insertReusesExistingJobBeforeWriting() {
        BizDataQualityRecalcJob existing = candidate();
        existing.setJobId("JOB-EXISTING");
        when(mapper.selectByIdempotencyKey("RANGE_RECALC:KEY"))
                .thenReturn(existing);

        assertThat(repository.insert(candidate())).isSameAs(existing);

        verify(mapper, never()).insert(any());
    }

    @Test
    void duplicateInsertUsesCurrentReadToReuseConcurrentJob() {
        BizDataQualityRecalcJob existing = candidate();
        existing.setJobId("JOB-CONCURRENT");
        when(mapper.selectByIdempotencyKey("RANGE_RECALC:KEY"))
                .thenReturn(null);
        when(mapper.insert(any(BizDataQualityRecalcJob.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(mapper.selectByIdempotencyKeyForUpdate("RANGE_RECALC:KEY"))
                .thenReturn(existing);

        assertThat(repository.insert(candidate())).isSameAs(existing);

        verify(mapper).selectByIdempotencyKeyForUpdate("RANGE_RECALC:KEY");
    }

    @Test
    void explicitCurrentReadUsesForUpdateMapperEntry() {
        BizDataQualityRecalcJob existing = candidate();
        when(mapper.selectByIdempotencyKeyForUpdate("RANGE_RECALC:KEY"))
                .thenReturn(existing);

        assertThat(repository.findByIdempotencyKeyForUpdate(
                "RANGE_RECALC:KEY")).containsSame(existing);

        verify(mapper).selectByIdempotencyKeyForUpdate("RANGE_RECALC:KEY");
    }

    @Test
    void claimAndAdvanceDelegateToConditionalAtomicUpdates() {
        LocalDateTime staleBefore = FROM.minusMinutes(2);
        LocalDateTime now = FROM.plusMinutes(1);
        LocalDateTime nextCursor = FROM.plusHours(1);
        when(mapper.claimAtomic("JOB1", staleBefore, now)).thenReturn(1);
        when(mapper.advanceChunkAtomic(
                "JOB1", FROM, nextCursor,
                2, 3, 4, 1, false, now)).thenReturn(1);

        assertThat(repository.claim("JOB1", staleBefore, now)).isTrue();
        repository.advanceChunk(
                "JOB1",
                FROM,
                nextCursor,
                new RecalculationChunkStats(2, 3, 4, 1),
                false,
                now);

        verify(mapper).claimAtomic(
                eq("JOB1"), eq(staleBefore), eq(now));
        verify(mapper).advanceChunkAtomic(
                eq("JOB1"), eq(FROM), eq(nextCursor),
                eq(2), eq(3), eq(4), eq(1), eq(false), eq(now));
    }

    @Test
    void finalAdvanceUsesTheSameAtomicBoundary() {
        LocalDateTime finishedAt = TO.plusSeconds(5);
        when(mapper.advanceChunkAtomic(
                "JOB1", FROM, TO,
                1, 0, 0, 0, true, finishedAt)).thenReturn(1);

        repository.advanceChunk(
                "JOB1",
                FROM,
                TO,
                new RecalculationChunkStats(1, 0, 0, 0),
                true,
                finishedAt);

        verify(mapper).advanceChunkAtomic(
                "JOB1", FROM, TO,
                1, 0, 0, 0, true, finishedAt);
    }

    @Test
    void changedCursorCannotBeSilentlyAdvanced() {
        when(mapper.advanceChunkAtomic(
                anyString(), any(), any(),
                anyInt(), anyInt(), anyInt(), anyInt(),
                eq(false), any())).thenReturn(0);

        assertThatThrownBy(() -> repository.advanceChunk(
                "JOB1",
                FROM,
                FROM.plusHours(1),
                new RecalculationChunkStats(1, 1, 1, 1),
                false,
                FROM.plusMinutes(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("游标");
    }

    @Test
    void onlyFailedJobCanBeResumed() {
        when(mapper.resumeFailedAtomic("JOB1")).thenReturn(0);

        assertThatThrownBy(() -> repository.resumeFailed("JOB1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void stateTransitionsRejectUnexpectedDatabaseState() {
        when(mapper.freezeVoidTargetsAtomic("JOB1", "[60000]")).thenReturn(0);
        when(mapper.completeVoidAtomic("JOB2", 1, 2)).thenReturn(0);
        when(mapper.markFailedAtomic(eq("JOB3"), eq(FROM), anyString()))
                .thenReturn(0);

        assertThatThrownBy(() ->
                repository.freezeVoidTargets("JOB1", "[60000]"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                repository.completeVoid("JOB2", 1, 2))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                repository.markFailed("JOB3", FROM, "failure"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validatesClaimLimitAndPageRange() {
        assertThatThrownBy(() -> repository.findClaimable(FROM, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findClaimable(FROM, 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findPage(
                0, 20, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findPage(
                1, 101, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(mapper, never()).selectClaimable(any(), anyInt());
        verify(mapper, never()).selectPageFiltered(
                any(IPage.class), any(), any(), any(), any(), any());
    }

    @Test
    void failureTextIsTrimmedAndCappedAtOneThousandCharacters() {
        when(mapper.markFailedAtomic(eq("JOB1"), eq(FROM), anyString()))
                .thenReturn(1);
        String oversized = "  " + "x".repeat(1_100) + "  ";

        repository.markFailed("JOB1", FROM, oversized);

        ArgumentCaptor<String> errorCaptor =
                ArgumentCaptor.forClass(String.class);
        verify(mapper).markFailedAtomic(
                eq("JOB1"), eq(FROM), errorCaptor.capture());
        assertThat(errorCaptor.getValue()).hasSize(1_000);
        assertThat(errorCaptor.getValue()).doesNotStartWith(" ");
    }

    private BizDataQualityRecalcJob candidate() {
        BizDataQualityRecalcJob job = new BizDataQualityRecalcJob();
        job.setIdempotencyKey("RANGE_RECALC:KEY");
        job.setJobType(RecalculationJobType.RANGE_RECALCULATE);
        job.setBuildingId("BLD001");
        job.setPointIdsJson("[\"P1\"]");
        job.setFromMinute(FROM);
        job.setToMinute(TO);
        job.setReason("历史测点配置修正");
        job.setOperatorId(1L);
        job.setStatus(RecalculationJobStatus.WAITING);
        job.setPhase(RecalculationJobPhase.RECALCULATING);
        job.setCursorMinute(FROM);
        return job;
    }
}
