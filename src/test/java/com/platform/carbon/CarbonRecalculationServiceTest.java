package com.platform.carbon;

import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.carbon.CarbonModels.RecalculationBatch;
import com.platform.carbon.CarbonModels.RecalculationItem;
import com.platform.carbon.CarbonModels.ResultNature;
import com.platform.carbon.api.CarbonContracts.ApproveRecalculationRequest;
import com.platform.carbon.api.CarbonContracts.RecoverDeadItemRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class CarbonRecalculationServiceTest {
    private final CarbonAuthorization authorization = mock(CarbonAuthorization.class);
    private final CarbonRuleRepository ruleRepository = mock(CarbonRuleRepository.class);
    private final CarbonRecalculationRepository repository = mock(CarbonRecalculationRepository.class);
    private final CarbonRecalculationPersistence persistence = mock(CarbonRecalculationPersistence.class);
    private final CarbonCalculationService calculationService = mock(CarbonCalculationService.class);
    private final CarbonProperties properties = new CarbonProperties();
    private final AuditEvidenceWriter auditWriter = mock(AuditEvidenceWriter.class);
    private final AuditGovernanceProperties auditProperties = mock(AuditGovernanceProperties.class);
    private final CarbonRecalculationService service = new CarbonRecalculationService(
            authorization, ruleRepository, repository, persistence, calculationService,
            properties, auditWriter, auditProperties);

    @Test
    void approvesOneFormalBatchOnlyAfterAllBuildingAndSeparationChecks() {
        RecalculationBatch pending = batch("PENDING_APPROVAL", ResultNature.FORMAL, 41L, null);
        RecalculationBatch completed = batch("COMPLETED", ResultNature.FORMAL, 41L, 99L);
        when(repository.findBatch("RB001")).thenReturn(pending, completed);
        when(repository.listBuildingIds("RB001")).thenReturn(List.of("BLD001", "BLD002"));
        when(repository.listResponsibleUsers("RB001")).thenReturn(List.of(42L));

        var result = service.approve(99L, Set.of("PLATFORM_ADMIN"), "RB001",
                new ApproveRecalculationRequest("审核通过"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        verify(authorization).requireRecalculationApprover(
                99L, Set.of("PLATFORM_ADMIN"), List.of("BLD001", "BLD002"));
        verify(authorization).requireSeparation(41L, 99L);
        verify(authorization).requireSeparation(42L, 99L);
        verify(persistence).publish("RB001", 99L, "审核通过");
    }

    @Test
    void recoversDeadItemByCreatingNewManualChangeWithoutMutatingFrozenBatch() {
        RecalculationItem item = new RecalculationItem("RI001", "RB001", "BLD001", 2026,
                "OLD001", null, "DEAD", false, 4, null,
                "FAILED", "失败", null, LocalDateTime.now(), LocalDateTime.now());
        when(repository.findItem("RI001")).thenReturn(item);
        when(repository.findBatch("RB001")).thenReturn(
                batch("DEAD", ResultNature.FORMAL, 41L, null));

        var result = service.recoverDead(99L, Set.of("PLATFORM_ADMIN"), "RI001",
                new RecoverDeadItemRequest("已补齐缺失因子"));

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.buildingId()).isEqualTo("BLD001");
        verify(ruleRepository).insertDependencyChange(any(), eq("MANUAL"),
                eq("CARBON_RESULT_YEAR"), eq("BLD001-2026"),
                org.mockito.ArgumentMatchers.contains("sourceItem=RI001"), eq(null), any(), any(),
                eq("BLD001"), eq("ORG-A"), any(), any(), eq(99L), any());
    }

    @Test
    void isolatesFailedCalculationItemAndFinishesBatchPhaseForRetryScheduling() {
        RecalculationBatch calculating = batch("CALCULATING", ResultNature.FORMAL, null, null);
        RecalculationItem item = new RecalculationItem("RI001", "RB001", "BLD001", 2026,
                "OLD001", null, "PENDING", false, 0, null,
                null, null, "LOCK", LocalDateTime.now(), null);
        when(persistence.claimBatch()).thenReturn(calculating);
        when(repository.listItems("RB001")).thenReturn(List.of(item));
        when(persistence.startItem("RI001", "RB001")).thenReturn(true);
        doThrow(CarbonErrors.error(409, CarbonErrors.FACTOR_MISSING, "缺少因子"))
                .when(calculationService).runCandidate(
                        "BLD001", 2026, ResultNature.FORMAL, "OLD001", "recalc:RI001:0");
        when(repository.findBatch("RB001")).thenReturn(
                batch("FAILED_RETRYABLE", ResultNature.FORMAL, null, null));

        service.executeOne();

        verify(persistence).failItem(item, CarbonErrors.FACTOR_MISSING, "缺少因子");
        verify(persistence).finishCalculationPhase("RB001", ResultNature.FORMAL);
    }

    private static RecalculationBatch batch(String status, ResultNature nature,
                                             Long initiatedBy, Long approvedBy) {
        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
        return new RecalculationBatch("RB001", "KEY001", "MANUAL", "ORG-A", nature,
                status, true, 2, 2, null, null, now, now, initiatedBy,
                approvedBy, approvedBy == null ? null : now, "", null, now);
    }
}
