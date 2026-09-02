package com.platform.energy.period;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.energy.period.EnergyPeriodModels.*;
import com.platform.energy.period.api.EnergyPeriodContracts.RefreshProjectionRequest;
import com.platform.energy.period.api.EnergyPeriodContracts.SubmitLockRequest;
import com.platform.energy.period.api.EnergyPeriodContracts.SubmitRecalculationRequest;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EnergyPeriodLifecycleServiceTest {
    private static final long USER = 101L;
    private static final Collection<String> ROLES = Set.of("ENERGY_MANAGER");
    private static final Instant START = Instant.parse("2026-07-31T16:00:00Z");
    private static final Instant END = Instant.parse("2026-08-31T16:00:00Z");

    @Mock private EnergyPeriodAuthorization authorization;
    @Mock private EnergyPeriodGovernanceService governance;
    @Mock private EnergyPeriodResultRepository repository;
    @Mock private EnergyPeriodCalculationService calculation;
    @Mock private EnergyPeriodValueStore valueStore;
    @Mock private EnergyPeriodSnapshotChangePublisher snapshotChangePublisher;
    @Mock private AuditEvidenceWriter auditWriter;

    private EnergyPeriodLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new EnergyPeriodLifecycleService(authorization, governance, repository,
                calculation, valueStore, snapshotChangePublisher, new ObjectMapper(), auditWriter,
                new AuditGovernanceProperties());
    }

    @Test
    void refreshesOneCurrentProjectionInsteadOfAppendingProvisionalVersions() {
        PeriodPolicyVersion policy = policy();
        when(governance.effectivePolicy("BLD001", LocalDate.of(2026, 8, 1).atStartOfDay()))
                .thenReturn(policy);
        when(calculation.calculate(eq(USER), eq(ROLES), eq("BLD001"), eq("POINT001"),
                any(), eq(policy), isNull(), any())).thenReturn(calculation(List.of()));

        var first = service.refresh(USER, ROLES, new RefreshProjectionRequest(
                "BLD001", "POINT001", "MONTH", LocalDate.of(2026, 8, 1), null,
                Instant.parse("2026-09-01T00:00:00Z")));

        ArgumentCaptor<CurrentProjection> inserted = ArgumentCaptor.forClass(CurrentProjection.class);
        verify(repository).insertCurrent(inserted.capture());
        verify(valueStore).write(any());
        assertThat(first.revision()).isEqualTo(1);
        assertThat(first.status()).isEqualTo("PROVISIONAL");

        reset(repository, valueStore);
        when(repository.findCurrent(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(inserted.getValue());
        when(repository.updateCurrent(any(), eq(1L))).thenReturn(1);
        var second = service.refresh(USER, ROLES, new RefreshProjectionRequest(
                "BLD001", "POINT001", "MONTH", LocalDate.of(2026, 8, 1), null,
                Instant.parse("2026-09-01T01:00:00Z")));

        ArgumentCaptor<CurrentProjection> updated = ArgumentCaptor.forClass(CurrentProjection.class);
        verify(repository).updateCurrent(updated.capture(), eq(1L));
        assertThat(second.projectionId()).isEqualTo(first.projectionId());
        assertThat(second.resultKey()).isEqualTo(first.resultKey());
        assertThat(second.revision()).isEqualTo(2);
    }

    @Test
    void permitsNativeOnlyLockOnlyThroughOneReviewedExceptionPolicy() {
        CurrentProjection current = current("TCE_RULE_UNAVAILABLE");
        when(repository.findCurrent("PROJECTION1")).thenReturn(current);
        when(governance.periodPolicyVersion("PPV1")).thenReturn(policy());
        when(governance.effectiveExceptionPolicies(eq("BLD001"), eq(List.of("TCE_RULE_UNAVAILABLE")),
                any())).thenReturn(List.of(exceptionPolicy()));

        var result = service.submitLock(USER, ROLES,
                new SubmitLockRequest("PROJECTION1", 1L, "研发月度封账", "模拟证据LOCK-1"));

        ArgumentCaptor<LockRequest> request = ArgumentCaptor.forClass(LockRequest.class);
        verify(repository).insertLockRequest(request.capture());
        assertThat(result.status()).isEqualTo("PENDING_REVIEW");
        assertThat(result.issuePolicyVersions()).containsExactly("EPV1");
        assertThat(request.getValue().evidenceReference()).isEqualTo("模拟证据LOCK-1");
    }

    @Test
    void blocksLockWhenIssueHasNoReviewedPolicy() {
        when(repository.findCurrent("PROJECTION1")).thenReturn(current("TCE_RULE_UNAVAILABLE"));
        when(governance.periodPolicyVersion("PPV1")).thenReturn(policy());
        when(governance.effectiveExceptionPolicies(anyString(), anyList(), any()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.submitLock(USER, ROLES,
                new SubmitLockRequest("PROJECTION1", 1L, "研发月度封账", "模拟证据")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(EnergyPeriodErrors.LOCK_BLOCKED);
    }

    @Test
    void createsOneBoundedBatchForMultipleSnapshotsAndReusesIdempotentResult() {
        PeriodSnapshot first = snapshot("S1", "P1");
        PeriodSnapshot second = snapshot("S2", "P2");
        when(repository.findSnapshot("S1")).thenReturn(first);
        when(repository.findSnapshot("S2")).thenReturn(second);
        when(repository.findVisibleSnapshot("P1")).thenReturn(first);
        when(repository.findVisibleSnapshot("P2")).thenReturn(second);
        when(repository.advanceBatch(anyString(), eq("CREATED"), eq("PENDING_REVIEW")))
                .thenReturn(1);
        when(repository.findBatch(anyString())).thenAnswer(invocation -> new RecalculationBatch(
                invocation.getArgument(0), "BLD001", "IDEMPOTENCY-1", "HASH",
                "SAME_RULES", "PENDING_REVIEW", "迟到修正", 2, 0, 0, 0, 0,
                USER, LocalDateTime.now(), null, null, null, null, null));

        var result = service.submitRecalculation(USER, ROLES, new SubmitRecalculationRequest(
                "BLD001", "IDEMPOTENCY-1", "SAME_RULES", "迟到修正", List.of("S2", "S1")));

        ArgumentCaptor<List<RecalculationItem>> items = ArgumentCaptor.forClass(List.class);
        verify(repository).insertBatchItems(items.capture());
        assertThat(result.totalItems()).isEqualTo(2);
        assertThat(items.getValue()).extracting(RecalculationItem::sourceSnapshotId)
                .containsExactly("S1", "S2");
        verify(repository, times(1)).insertBatch(any());
    }

    @Test
    void completesOneBatchWithoutAppendingSnapshotsWhenResultsAreUnchanged() {
        PeriodSnapshot source = snapshot("S1", "P1");
        PeriodPolicyVersion policy = policy();
        RecalculationItem item = new RecalculationItem(
                "ITEM1", "BATCH1", "S1", 0, "PENDING", null, null);
        RecalculationBatch validating = batch("VALIDATING", 0, 0);
        RecalculationBatch calculated = batch("CALCULATING", 0, 0);
        RecalculationBatch completed = batch("COMPLETED", 1, 0);
        when(repository.findBatch("BATCH1")).thenReturn(validating, calculated, completed);
        when(repository.listBatchItems("BATCH1")).thenReturn(List.of(item));
        when(repository.findSnapshot("S1")).thenReturn(source);
        when(repository.findVisibleSnapshot("P1")).thenReturn(source);
        when(governance.periodPolicyVersion("PPV1")).thenReturn(policy);
        when(calculation.calculate(eq(USER), eq(ROLES), eq("BLD001"), eq("POINTS1"),
                any(), eq(policy), isNull(), any())).thenReturn(completeCalculation("POINTS1"));
        when(repository.advanceBatch(anyString(), anyString(), anyString())).thenReturn(1);
        when(repository.completeBatch(eq("BATCH1"), any())).thenReturn(1);

        var result = service.executeRecalculation(USER, ROLES, "BATCH1");

        assertThat(result.status()).isEqualTo("COMPLETED");
        verify(repository).updateBatchItem("ITEM1", "UNCHANGED", null, null);
        verify(repository, never()).insertSnapshot(any());
        verify(valueStore, never()).write(any());
        verify(snapshotChangePublisher, never()).published(any(), any());
    }

    @Test
    void publishesOneCarbonDependencyChangeOnlyAfterChangedBatchCompletes() {
        PeriodSnapshot source = snapshot("S1", "P1");
        PeriodSnapshot replacement = snapshot("S2", "P1");
        RecalculationItem pending = new RecalculationItem(
                "ITEM1", "BATCH1", "S1", 0, "PENDING", null, null);
        RecalculationItem changed = new RecalculationItem(
                "ITEM1", "BATCH1", "S1", 0, "CHANGED", "S2", null);
        when(repository.findBatch("BATCH1")).thenReturn(
                batch("VALIDATING", 0, 0), batch("CALCULATING", 0, 0),
                batch("COMPLETED", 1, 0));
        when(repository.listBatchItems("BATCH1")).thenReturn(List.of(pending), List.of(changed));
        when(repository.findSnapshot("S1")).thenReturn(source);
        when(repository.findSnapshot("S2")).thenReturn(replacement);
        when(repository.findVisibleSnapshot("P1")).thenReturn(source);
        when(governance.periodPolicyVersion("PPV1")).thenReturn(policy());
        ProjectionCalculation changedCalculation = new ProjectionCalculation(
                "DEVELOPMENT_SIMULATION", "BLD001", "POINTS1",
                new PeriodWindow(PeriodType.MONTH, START, END, "Asia/Shanghai"), "PPV1",
                "GRID_ELECTRICITY", new BigDecimal("101"), "kWh", BigDecimal.ONE, "TCE",
                BigDecimal.ONE, List.of(), "{\"periodPolicyVersionId\":\"PPV1\"}",
                "CHANGED_HASH", null, END, LocalDateTime.now());
        when(calculation.calculate(eq(USER), eq(ROLES), eq("BLD001"), eq("POINTS1"),
                any(), any(), isNull(), any())).thenReturn(changedCalculation);
        when(repository.nextSnapshotVersion("P1")).thenReturn(2);
        when(repository.advanceBatch(anyString(), anyString(), anyString())).thenReturn(1);
        when(repository.completeBatch(eq("BATCH1"), any())).thenReturn(1);

        var result = service.executeRecalculation(USER, ROLES, "BATCH1");

        assertThat(result.status()).isEqualTo("COMPLETED");
        verify(snapshotChangePublisher).published(source, replacement);
    }

    private PeriodPolicyVersion policy() {
        return new PeriodPolicyVersion("PP1", "PPV1", 1, "BLD001", "Asia/Shanghai",
                72, "REVIEW_REQUIRED", "APPROVED", "SIMULATION", "研发周期口径",
                LocalDateTime.of(2020, 1, 1, 0, 0), null, 1, USER,
                LocalDateTime.now(), USER + 1, LocalDateTime.now());
    }

    private ProjectionCalculation calculation(List<String> issues) {
        return new ProjectionCalculation("DEVELOPMENT_SIMULATION", "BLD001", "POINT001",
                new PeriodWindow(PeriodType.MONTH, START, END, "Asia/Shanghai"), "PPV1",
                "GRID_ELECTRICITY", new BigDecimal("100"), "kWh", null, null,
                BigDecimal.ONE, issues, "{\"periodPolicyVersionId\":\"PPV1\"}", "HASH",
                null, END, LocalDateTime.now());
    }

    private ProjectionCalculation completeCalculation(String pointId) {
        return new ProjectionCalculation("DEVELOPMENT_SIMULATION", "BLD001", pointId,
                new PeriodWindow(PeriodType.MONTH, START, END, "Asia/Shanghai"), "PPV1",
                "GRID_ELECTRICITY", new BigDecimal("100"), "kWh", BigDecimal.ONE, "TCE",
                BigDecimal.ONE, List.of(), "{\"periodPolicyVersionId\":\"PPV1\"}", "HASH",
                null, END, LocalDateTime.now());
    }

    private RecalculationBatch batch(String status, int processed, int failed) {
        return new RecalculationBatch("BATCH1", "BLD001", "IDEMPOTENCY-1", "HASH",
                "SAME_RULES", status, "迟到修正", 1, processed,
                0, processed - failed, failed, USER, LocalDateTime.now(), USER + 1,
                LocalDateTime.now(), "研发审核", null,
                "COMPLETED".equals(status) ? LocalDateTime.now() : null);
    }

    private CurrentProjection current(String issues) {
        Instant lockStart = Instant.parse("2026-05-31T16:00:00Z");
        Instant lockEnd = Instant.parse("2026-06-30T16:00:00Z");
        return new CurrentProjection("PROJECTION1", "RESULT1", "BLD001", "POINT001", "MONTH",
                lockStart, lockEnd, "Asia/Shanghai", "PPV1", "PROVISIONAL", 1,
                "DEVELOPMENT_SIMULATION", "GRID_ELECTRICITY", new BigDecimal("100"),
                "kWh", null, null, BigDecimal.ONE, issues,
                "{\"periodPolicyVersionId\":\"PPV1\"}", "HASH", null, lockEnd,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private ExceptionPolicyVersion exceptionPolicy() {
        return new ExceptionPolicyVersion("EP1", "EPV1", 1, "BLD001",
                "TCE_RULE_UNAVAILABLE", "WARNING", "LOCK_NATIVE_QUANTITY_ONLY", 1,
                BigDecimal.ONE, new BigDecimal("0.95"), "ALL", true,
                "需要模拟证据", "APPROVED", "SIMULATION", "研发例外", LocalDateTime.of(2020,
                1, 1, 0, 0), null, 1, USER, LocalDateTime.now(), USER + 1,
                LocalDateTime.now());
    }

    private PeriodSnapshot snapshot(String snapshotId, String projectionId) {
        return new PeriodSnapshot(snapshotId, "RESULT" + snapshotId, projectionId, "BLD001",
                "POINT" + snapshotId, "MONTH", START, END, "Asia/Shanghai", "PPV1", 1,
                "LOCKED_COMPLETE", "DEVELOPMENT_SIMULATION", "GRID_ELECTRICITY",
                new BigDecimal("100"), "kWh", BigDecimal.ONE, "TCE", BigDecimal.ONE,
                "", "", "{\"calculationEvidence\":{\"periodPolicyVersionId\":\"PPV1\"},"
                + "\"calculationEvidenceHash\":\"HASH\"}", "HASH", null, END,
                null, null, USER, LocalDateTime.now());
    }
}
