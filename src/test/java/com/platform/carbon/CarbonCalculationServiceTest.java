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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @BeforeEach
    void runPersistenceReadsInsideTheMockedShortTransaction() {
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(persistence).audit(any());
        when(persistence.read(any())).thenAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(0);
            return operation.get();
        });
    }

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

    @Test
    void sameIdempotencyKeyMarksExpiredCalculationTimedOutAndReturnsItsBatch() {
        String hash = requestHash(ResultNature.DEVELOPMENT_SIMULATION, null);
        LocalDateTime now = LocalDateTime.now();
        CalculationBatch expired = new CalculationBatch("EXPIRED", "BLD001", PeriodType.YEAR,
                START, END, "Asia/Shanghai", ResultNature.DEVELOPMENT_SIMULATION,
                "DIRECT", "CALCULATING", "IDEMPOTENCY-1", hash, "LOCK", "CRP_DECIMAL128_V1",
                null, now.minusMinutes(1), now.minusSeconds(1), null, null, 0, 0, false,
                null, null, 11L, now.minusMinutes(1));
        when(repository.findByIdempotency("BLD001", "IDEMPOTENCY-1")).thenReturn(expired);
        when(repository.detail("EXPIRED")).thenReturn(detail("EXPIRED", "FAILED_TIMEOUT"));
        when(persistence.timeout(eq("EXPIRED"), any(LocalDateTime.class))).thenReturn(true);

        CalculationDetail result = service.run(11L, Set.of("PLATFORM_ADMIN"), request(
                ResultNature.DEVELOPMENT_SIMULATION));

        assertThat(result.batch().status()).isEqualTo("FAILED_TIMEOUT");
        verify(persistence).timeout(eq("EXPIRED"), any(LocalDateTime.class));
        verify(persistence, never()).create(any());
        verify(input, never()).read(anyString(), any(), any(), any(), anyInt());
    }

    @Test
    void databaseQueryTimeoutBeforeRequestDeadlineReleasesTheActiveCalculationLock() {
        properties.setCalculationTimeout(java.time.Duration.ofSeconds(5));
        when(ruleRepository.activeRoundingPolicyId()).thenReturn("CRP_DECIMAL128_V1");
        when(input.read("BLD001", PeriodType.YEAR, START, END, 500))
                .thenReturn(List.of(activity(ResultNature.DEVELOPMENT_SIMULATION,
                        "LOCKED_COMPLETE", "COMPLETE")));
        when(ruleRepository.findBuildingRegion("BLD001"))
                .thenThrow(new QueryTimeoutException("simulated query timeout"));

        assertThatThrownBy(() -> service.run(11L, Set.of("PLATFORM_ADMIN"), request(
                ResultNature.DEVELOPMENT_SIMULATION)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CarbonErrors.CALCULATION_TIMEOUT);

        verify(persistence).timeoutAborted(anyString(), any(LocalDateTime.class));
        verify(persistence, never()).timeout(anyString(), any(LocalDateTime.class));
    }

    @Test
    void timedOutUncooperativeInputKeepsItsPermitUntilItActuallyExits() throws Exception {
        properties.setMaximumConcurrentCalculations(1);
        properties.setCalculationTimeout(java.time.Duration.ofMillis(250));
        properties.setCalculationRecoveryTimeout(java.time.Duration.ofMillis(10));
        var limited = new CarbonCalculationService(authorization, input, ruleRepository, repository,
                persistence, new CarbonCalculationCore(), properties, mock(AuditEvidenceWriter.class),
                mock(AuditGovernanceProperties.class));
        var release = new java.util.concurrent.CountDownLatch(1);
        var exited = new java.util.concurrent.CountDownLatch(1);
        when(ruleRepository.activeRoundingPolicyId()).thenReturn("CRP_DECIMAL128_V1");
        when(input.read(anyString(), any(), any(), any(), anyInt())).thenAnswer(invocation -> {
            try {
                while (release.getCount() > 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
                return List.of();
            } finally { exited.countDown(); }
        });
        try {
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> limited.run(11L, Set.of("PLATFORM_ADMIN"),
                        request(ResultNature.DEVELOPMENT_SIMULATION)))
                        .isInstanceOf(BusinessException.class)
                        .extracting("errorCode").isEqualTo(CarbonErrors.CALCULATION_TIMEOUT);
            }
            verify(input, org.mockito.Mockito.times(1)).read(anyString(), any(), any(), any(), anyInt());
            verify(persistence, never()).complete(anyString(), any(), anyInt(), anyBoolean(), anyLong(), any());
        } finally {
            release.countDown();
            assertThat(exited.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    private static RunCalculationRequest request(ResultNature nature) {
        return new RunCalculationRequest("BLD001", "YEAR", START, END,
                "Asia/Shanghai", nature.name(), "IDEMPOTENCY-1");
    }

    private static String requestHash(ResultNature nature, String supersedesBatchId) {
        return CarbonCalculationCore.sha256("BLD001|YEAR|" + START + '|' + END
                + "|Asia/Shanghai|" + nature + '|' + supersedesBatchId);
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
