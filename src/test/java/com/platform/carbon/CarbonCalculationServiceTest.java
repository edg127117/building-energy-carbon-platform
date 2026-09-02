package com.platform.carbon;

import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.carbon.CarbonModels.ActivitySegment;
import com.platform.carbon.CarbonModels.CalculationBatch;
import com.platform.carbon.CarbonModels.CalculationDetail;
import com.platform.carbon.CarbonModels.CalculationResult;
import com.platform.carbon.CarbonModels.PeriodType;
import com.platform.carbon.CarbonModels.ResultNature;
import com.platform.carbon.api.CarbonContracts.RunCalculationRequest;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CarbonCalculationServiceTest {
    private static final Instant START = Instant.parse("2025-12-31T16:00:00Z");
    private static final Instant END = Instant.parse("2026-12-31T16:00:00Z");

    private final CarbonAuthorization authorization = mock(CarbonAuthorization.class);
    private final CarbonActivityInputPort input = mock(CarbonActivityInputPort.class);
    private final CarbonRuleRepository ruleRepository = mock(CarbonRuleRepository.class);
    private final CarbonCalculationRepository repository = mock(CarbonCalculationRepository.class);
    private final CarbonCalculationPersistence persistence = mock(CarbonCalculationPersistence.class);
    private final CarbonProperties properties = new CarbonProperties();
    private final CarbonCalculationService service = new CarbonCalculationService(
            authorization, input, ruleRepository, repository, persistence,
            new CarbonCalculationCore(), properties, mock(AuditEvidenceWriter.class),
            mock(AuditGovernanceProperties.class));

    @Test
    void developmentCalculationKeepsTotalAndPersistsFailedSegmentWhenFactorIsMissing() {
        when(ruleRepository.activeRoundingPolicyId()).thenReturn("CRP_DECIMAL128_V1");
        when(input.read("BLD001", PeriodType.YEAR, START, END, 500))
                .thenReturn(List.of(activity(ResultNature.DEVELOPMENT_SIMULATION,
                        "LOCKED_COMPLETE", "COMPLETE")));
        when(ruleRepository.findBuildingRegion("BLD001")).thenReturn("310100");
        when(ruleRepository.findCandidateFactors(eq("ELECTRICITY"), any(), any()))
                .thenReturn(List.of());
        when(repository.detail(anyString())).thenAnswer(invocation -> detail(
                invocation.getArgument(0), "COMPLETED_INCOMPLETE"));

        CalculationDetail result = service.run(11L, Set.of("PLATFORM_ADMIN"), request(
                ResultNature.DEVELOPMENT_SIMULATION));

        assertThat(result.batch().status()).isEqualTo("COMPLETED_INCOMPLETE");
        ArgumentCaptor<CalculationResult> captured = ArgumentCaptor.forClass(CalculationResult.class);
        verify(persistence).complete(anyString(), captured.capture(), eq(1), eq(false),
                anyLong(), any(LocalDateTime.class));
        assertThat(captured.getValue().complete()).isFalse();
        assertThat(captured.getValue().failures()).singleElement()
                .satisfies(value -> assertThat(value.errorCode())
                        .isEqualTo(CarbonErrors.FACTOR_MISSING));
        assertThat(captured.getValue().summaries())
                .filteredOn(value -> "TOTAL_EMISSION".equals(value.metricCode()))
                .singleElement().satisfies(value -> assertThat(value.finalValue())
                        .isEqualByComparingTo("0.000000"));
    }

    @Test
    void formalCalculationFailsClosedBeforeMatchingWhenActivityIsPartial() {
        when(ruleRepository.activeRoundingPolicyId()).thenReturn("CRP_DECIMAL128_V1");
        when(input.read("BLD001", PeriodType.YEAR, START, END, 500))
                .thenReturn(List.of(activity(ResultNature.FORMAL,
                        "LOCKED_PARTIAL", "INCOMPLETE")));
        when(ruleRepository.findBuildingRegion("BLD001")).thenReturn("310100");

        assertThatThrownBy(() -> service.run(11L, Set.of("PLATFORM_ADMIN"),
                request(ResultNature.FORMAL)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CarbonErrors.ACTIVITY_INCOMPLETE);
        verify(persistence).fail(anyString(), eq(CarbonErrors.ACTIVITY_INCOMPLETE),
                anyString(), any(LocalDateTime.class), anyLong());
    }

    private static RunCalculationRequest request(ResultNature nature) {
        return new RunCalculationRequest("BLD001", "YEAR", START, END,
                "Asia/Shanghai", nature.name(), "IDEMPOTENCY-1");
    }

    private static ActivitySegment activity(ResultNature nature, String lock, String completeness) {
        return new ActivitySegment("SNAP001", "BLD001", PeriodType.YEAR, START, END,
                "Asia/Shanghai", "ELECTRICITY", new BigDecimal("1000"), "KWH",
                lock, completeness, nature, "e".repeat(64));
    }

    private static CalculationDetail detail(String id, String status) {
        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
        CalculationBatch batch = new CalculationBatch(id, "BLD001", PeriodType.YEAR,
                START, END, "Asia/Shanghai", ResultNature.DEVELOPMENT_SIMULATION,
                "DIRECT", status, "IDEMPOTENCY-1", "HASH", null,
                "CRP_DECIMAL128_V1", null, now, now.plusSeconds(20), now, 1L,
                1, 1, false, null, null, 11L, now);
        return new CalculationDetail(batch, List.of(), List.of(), List.of());
    }
}
