package com.platform.carbon;

import com.platform.carbon.CarbonModels.RecalculationItem;
import com.platform.carbon.CarbonModels.ResultNature;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CarbonRecalculationPersistenceTest {
    private final CarbonRecalculationRepository repository = mock(CarbonRecalculationRepository.class);
    private final CarbonProperties properties = new CarbonProperties();
    private final CarbonRecalculationPersistence persistence = new CarbonRecalculationPersistence(
            repository, mock(CarbonRuleRepository.class), properties);

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

    private static RecalculationItem item() {
        return new RecalculationItem("ITEM", "BATCH", "BUILDING", 2025, "OLD", null,
                "PENDING", false, 0, null, null, null, "LOCK", LocalDateTime.now(), null);
    }
}
