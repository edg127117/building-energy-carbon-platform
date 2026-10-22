package com.platform.carbon;

import com.platform.carbon.CarbonModels.CalculationBatch;
import com.platform.carbon.CarbonModels.DependencyChange;
import com.platform.carbon.CarbonModels.PeriodType;
import com.platform.carbon.CarbonModels.RecalculationItem;
import com.platform.carbon.CarbonModels.ResultNature;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CarbonRecalculationPersistenceTest {
    private final CarbonRecalculationRepository repository = mock(CarbonRecalculationRepository.class);
    private final CarbonProperties properties = new CarbonProperties();
    private final CarbonRecalculationPersistence persistence = new CarbonRecalculationPersistence(
            repository, mock(CarbonRuleRepository.class), properties);

    @Test
    void queuedElectricityUpgradeStopsFormalItemButAllowsSimulation() {
        when(repository.lockLease(eq("BATCH"), eq("OWNER"), any())).thenReturn(true);
        when(repository.electricityFactorChangeBatch("BATCH")).thenReturn(true);
        var batch = mock(CarbonModels.RecalculationBatch.class);
        when(repository.findBatch("BATCH")).thenReturn(batch);
        when(batch.resultNature()).thenReturn(ResultNature.FORMAL);
        assertThat(persistence.startItem("ITEM", "BATCH", "OWNER")).isFalse();
        verify(repository).stopLockedReportItem(eq("ITEM"), any());
        verify(repository, never()).startItem(anyString(), anyString());

        when(batch.resultNature()).thenReturn(ResultNature.DEVELOPMENT_SIMULATION);
        when(repository.startItem("SIMULATION_ITEM", "BATCH")).thenReturn(1);
        assertThat(persistence.startItem("SIMULATION_ITEM", "BATCH", "OWNER")).isTrue();
        verify(repository, never()).stopLockedReportItem(eq("SIMULATION_ITEM"), any());
    }

    @Test
    void staleLeaseCannotStartSucceedFailFinishOrRenew() {
        var item = item();
        assertThat(persistence.startItem(item.itemId(), item.batchId(), "STALE")).isFalse();
        persistence.succeedItem(item, "CANDIDATE", "STALE");
        persistence.failItem(item, "ERROR", "failure", "STALE");
        persistence.finishCalculationPhase(item.batchId(), ResultNature.FORMAL, "STALE");
        assertThat(persistence.renewLease(item.batchId(), "STALE")).isFalse();
        verify(repository, never()).startItem(anyString(), anyString());
        verify(repository, never()).registerCandidate(anyString(), anyString(), anyString(), anyString());
        verify(repository, never()).succeedItem(anyString(), anyString(), any());
        verify(repository, never()).failItem(anyString(), anyBoolean(), anyInt(), any(), anyString(), anyString(), any());
        verify(repository, never()).finishCalculationPhase(anyString(), any(), any());
        verify(repository, never()).renewLease(anyString(), anyString(), any());
    }

    @Test
    void failedItemUsesBoundedRetryAndConfiguredBackoff() {
        when(repository.lockLease(eq("BATCH"), eq("OWNER"), any())).thenReturn(true);
        properties.setRetryBackoff(java.time.Duration.ofSeconds(15));
        LocalDateTime before = LocalDateTime.now();
        persistence.failItem(item(), "ERROR", "failure", "OWNER");
        verify(repository).failItem(eq("ITEM"), eq(false), eq(1),
                argThat(next -> !next.isBefore(before.plusSeconds(15))), eq("ERROR"), eq("failure"), any());
    }

    @Test
    void sameYearMonthAndQuarterReportsMaterializeIntoSeparateBatchesWithBoundedLocks() {
        DependencyChange change = change("CHANGE-1");
        CalculationBatch january = report("JAN", PeriodType.MONTH,
                "2025-12-31T16:00:00Z", "2026-01-31T16:00:00Z");
        CalculationBatch february = report("FEB", PeriodType.MONTH,
                "2026-01-31T16:00:00Z", "2026-02-28T16:00:00Z");
        CalculationBatch firstQuarter = report("Q1", PeriodType.QUARTER,
                "2025-12-31T16:00:00Z", "2026-03-31T16:00:00Z");
        when(repository.lockChange(eq(change.changeId()), eq("LEASE"), any())).thenReturn(true);
        when(repository.findImpactedResults(eq(change), anyInt()))
                .thenReturn(List.of(january, february, firstQuarter));
        when(repository.findActiveBatchForItem(any(CalculationBatch.class), anyInt())).thenReturn(null);
        when(repository.lockCurrentResult(anyString())).thenReturn(true);
        when(repository.findMergeableBatch(anyString(), anyInt())).thenReturn(null);
        when(repository.insertItem(anyString(), any(CalculationBatch.class), anyInt())).thenReturn(1);

        List<String> batchIds = persistence.materialize(
                new CarbonRecalculationPersistence.ChangeLease(change, "LEASE"));

        assertThat(batchIds).hasSize(3).doesNotHaveDuplicates();
        var mergeKeys = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repository, times(3)).findMergeableBatch(mergeKeys.capture(), anyInt());
        assertThat(mergeKeys.getAllValues()).doesNotHaveDuplicates();
        assertThat(CarbonRecalculationRepository.activeLockKey(january, 2026))
                .isNotEqualTo(CarbonRecalculationRepository.activeLockKey(february, 2026))
                .isNotEqualTo(CarbonRecalculationRepository.activeLockKey(firstQuarter, 2026))
                .hasSize(71);
        assertThat(CarbonRecalculationRepository.activeLockKey(report("YEAR", PeriodType.YEAR,
                "2025-12-31T16:00:00Z", "2026-12-31T16:00:00Z"), 2026))
                .isEqualTo("BLD001|2026|FORMAL");
    }

    @Test
    void samePeriodActiveBatchCreatedDuringCurrentResultLockMergesInsteadOfDuplicatingItem() {
        DependencyChange change = change("CHANGE-2");
        CalculationBatch january = report("JAN", PeriodType.MONTH,
                "2025-12-31T16:00:00Z", "2026-01-31T16:00:00Z");
        when(repository.lockChange(eq(change.changeId()), eq("LEASE"), any())).thenReturn(true);
        when(repository.findImpactedResults(eq(change), anyInt())).thenReturn(List.of(january));
        when(repository.findActiveBatchForItem(any(CalculationBatch.class), anyInt()))
                .thenReturn(null, null, "ACTIVE");
        when(repository.lockCurrentResult("JAN")).thenReturn(true);
        when(repository.canMerge(eq("ACTIVE"), anyString())).thenReturn(true);

        List<String> batchIds = persistence.materialize(
                new CarbonRecalculationPersistence.ChangeLease(change, "LEASE"));

        assertThat(batchIds).containsExactly("ACTIVE");
        verify(repository).lockCurrentResult("JAN");
        verify(repository).attachTrigger(eq("ACTIVE"), anyString());
        verify(repository, never()).insertBatch(any());
        verify(repository, never()).insertItem(anyString(), any(CalculationBatch.class), anyInt());
    }

    private static RecalculationItem item() {
        return new RecalculationItem("ITEM", "BATCH", "BUILDING", 2025, "OLD", null,
                "PENDING", false, 0, null, null, null, "LOCK", LocalDateTime.now(), null);
    }

    private static DependencyChange change(String id) {
        LocalDateTime now = LocalDateTime.of(2026, 2, 1, 12, 0);
        return new DependencyChange(id, "ACTIVITY_SNAPSHOT", "ENERGY_PERIOD", "SOURCE",
                "late correction", null, "VERSION", "fingerprint-" + id, "BLD001", "ORG-A",
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 4, 1, 0, 0),
                41L, "ANALYZING", now, null);
    }

    private static CalculationBatch report(String id, PeriodType periodType,
                                           String start, String end) {
        LocalDateTime now = LocalDateTime.of(2026, 2, 1, 12, 0);
        return new CalculationBatch(id, "BLD001", periodType, Instant.parse(start), Instant.parse(end),
                "Asia/Shanghai", ResultNature.FORMAL, "PUBLISHED", "COMPLETED_COMPLETE",
                "source-" + id, "HASH", null, "CRP_DECIMAL128_V1", null,
                now, now.plusSeconds(30), now, 1L, 1, 1, false, null, null, 41L, now);
    }
}
