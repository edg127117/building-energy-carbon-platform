package com.platform.energy.aggregation;

import com.platform.energy.aggregation.EnergyAggregationModels.ActivityFact;
import com.platform.energy.aggregation.EnergyAggregationModels.CorrectionEvidence;
import com.platform.energy.aggregation.EnergyAggregationModels.EvidenceStatus;
import com.platform.energy.aggregation.EnergyAggregationModels.MeterEventEvidence;
import com.platform.energy.aggregation.EnergyAggregationModels.MeterEventType;
import com.platform.energy.aggregation.NativeQuantityAggregationService.GovernedEvidence;
import com.platform.energy.aggregation.NativeQuantityAggregationService.NativeCumulativeRequest;
import com.platform.energy.aggregation.NativeQuantityAggregationService.NativeIntegrationRequest;
import com.platform.energy.aggregation.NativeQuantityAggregationService.NativePowerInterval;
import com.platform.energy.aggregation.NativeQuantityAggregationService.NativeQuantityResult;
import com.platform.energy.aggregation.NativeQuantityAggregationService.NativeScope;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NativeQuantityAggregationServiceTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = START.plusSeconds(7_200);
    private final EnergyAggregationAuthorization authorization = mock(EnergyAggregationAuthorization.class);
    private final EnergyAggregationGovernanceService governance = mock(EnergyAggregationGovernanceService.class);
    private final NativeQuantityAggregationService service = new NativeQuantityAggregationService(
            new EnergyAggregationCore(), authorization, governance);

    @Test
    void aggregatesOnlyTheExactNativeCumulativeBoundaries() {
        NativeQuantityResult result = service.aggregateCumulative(new NativeCumulativeRequest(scope(), "KWH",
                List.of(fact("before", "90", START.minusSeconds(60)), fact("start", "100", START),
                        fact("middle", "130", START.plusSeconds(3_600)), fact("end", "160", END)),
                List.of(), List.of()));

        assertThat(result.quantity()).isEqualByComparingTo("60");
        assertThat(result.coverageRatio()).isEqualByComparingTo("1");
        assertThat(result.maximumObservedGapMillis()).isEqualTo(3_600_000L);
        assertThat(result.inputFactIds()).containsExactly("start", "middle", "end");
        assertThat(result.complete()).isTrue();
    }

    @Test
    void classifiesAnApprovedPositiveResetAtTheEndBoundaryWithoutRequiringSimulatedEnergyIdentity() {
        MeterEventEvidence reset = event(MeterEventType.RESET, END, "110", "900", null);

        NativeQuantityResult result = service.aggregateCumulative(new NativeCumulativeRequest(scope(), "KWH",
                List.of(fact("start", "100", START), fact("end", "930", END)),
                List.of(reset), List.of()));

        assertThat(result.quantity()).isEqualByComparingTo("40");
        assertThat(result.meterEventVersionIds()).containsExactly("EVENT_V1");
    }

    @Test
    void appliesOnlyOneApprovedQualityGatedCorrectionToNativeFacts() {
        CorrectionEvidence correction = new CorrectionEvidence("CORRECTION", "CORRECTION_V1", "end",
                new BigDecimal("90"), new BigDecimal("140"), "校正抄表错误", EvidenceStatus.APPROVED,
                "审核校正依据", 101, 202L, true);

        NativeQuantityResult result = service.aggregateCumulative(new NativeCumulativeRequest(scope(), "KWH",
                List.of(fact("start", "100", START), fact("end", "90", END)),
                List.of(), List.of(correction)));

        assertThat(result.quantity()).isEqualByComparingTo("40");
        assertThat(result.correctionVersionIds()).containsExactly("CORRECTION_V1");
    }

    @Test
    void rejectsMissingAnchorsUnclassifiedNegativeValuesAndMismatchedEventScope() {
        assertCode(() -> service.aggregateCumulative(new NativeCumulativeRequest(scope(), "KWH",
                        List.of(fact("before", "90", START.minusSeconds(60)), fact("end", "120", END)),
                        List.of(), List.of())), EnergyAggregationErrors.ANCHOR_MISSING);
        assertCode(() -> service.aggregateCumulative(new NativeCumulativeRequest(scope(), "KWH",
                        List.of(fact("start", "100", START), fact("end", "90", END)),
                        List.of(), List.of())), EnergyAggregationErrors.NEGATIVE_DELTA_UNCLASSIFIED);
        MeterEventEvidence rolloverOverflow = event(MeterEventType.ROLLOVER, END, null, null, "1000");
        assertCode(() -> service.aggregateCumulative(new NativeCumulativeRequest(scope(), "KWH",
                        List.of(fact("start", "1100", START), fact("end", "200", END)),
                        List.of(rolloverOverflow), List.of())), EnergyAggregationErrors.EVENT_EVIDENCE_CONFLICT);
        MeterEventEvidence wrongScope = new MeterEventEvidence("EVENT", "EVENT_V1", "OTHER", "POINT001",
                MeterEventType.RESET, START.plusSeconds(1_800), EvidenceStatus.APPROVED,
                new BigDecimal("110"), BigDecimal.ZERO, null, null, null, null, null,
                "审核事件依据", 101, 202L, false);
        assertCode(() -> service.aggregateCumulative(new NativeCumulativeRequest(scope(), "KWH",
                        List.of(fact("start", "100", START), fact("end", "10", END)),
                        List.of(wrongScope), List.of())), EnergyAggregationErrors.EVENT_EVIDENCE_CONFLICT);
    }

    @Test
    void returnsPartialNativeIntegralAndTheExplicitGapInsteadOfInterpolatingIt() {
        NativeQuantityResult result = service.integratePower(new NativeIntegrationRequest(scope(), "KWH", List.of(
                new NativePowerInterval("segment-1", START, START.plusSeconds(3_600), new BigDecimal("10"),
                        List.of("flow-0", "supply-0", "return-0"), "QUALITY_V1"),
                new NativePowerInterval("segment-2", START.plusSeconds(5_400), END, new BigDecimal("20"),
                        List.of("flow-1", "supply-1", "return-1"), "QUALITY_V1"))));

        assertThat(result.quantity()).isEqualByComparingTo("20");
        assertThat(result.coverageRatio()).isEqualByComparingTo("0.75");
        assertThat(result.maximumObservedGapMillis()).isEqualTo(1_800_000L);
        assertThat(result.issues()).containsExactly("POWER_INTERVAL_GAP");
        assertThat(result.complete()).isFalse();
    }

    @Test
    void loadsOnlyApprovedGovernedEvidenceAfterRunnerAndBuildingChecks() {
        MeterEventEvidence event = event(MeterEventType.RESET, START.plusSeconds(1_800), "110", "0", null);
        CorrectionEvidence correction = new CorrectionEvidence("CORRECTION", "CORRECTION_V1", "end",
                new BigDecimal("90"), new BigDecimal("140"), "校正", EvidenceStatus.APPROVED,
                "审核校正依据", 101, 202L, true);
        when(governance.approvedEvents("BLD001", "POINT001", START, END, 1_001))
                .thenReturn(List.of(event));
        when(governance.approvedCorrections("BLD001", "POINT001", 1_001)).thenReturn(List.of(correction));

        GovernedEvidence evidence = service.governedEvidence(7L, List.of("ENERGY_MANAGER"), "BLD001",
                "POINT001", START, END);

        verify(authorization).requireRunner(7L, List.of("ENERGY_MANAGER"));
        verify(authorization).checkBuilding(7L, List.of("ENERGY_MANAGER"), "BLD001");
        verify(governance).approvedEvents("BLD001", "POINT001", START, END, 1_001);
        verify(governance).approvedCorrections("BLD001", "POINT001", 1_001);
        assertThat(evidence.meterEvents()).containsExactly(event);
        assertThat(evidence.corrections()).containsExactly(correction);
    }

    @Test
    void rejectsGovernedEvidenceThatExceedsTheSingleReadLimit() {
        when(governance.approvedEvents("BLD001", "POINT001", START, END, 1_001))
                .thenReturn(Collections.nCopies(1_001,
                        event(MeterEventType.RESET, START.plusSeconds(1_800), "110", "0", null)));
        when(governance.approvedCorrections("BLD001", "POINT001", 1_001)).thenReturn(List.of());

        assertCode(() -> service.governedEvidence(7L, List.of("ENERGY_MANAGER"), "BLD001",
                "POINT001", START, END), EnergyAggregationErrors.INPUT_INCOMPLETE);
    }

    private static NativeScope scope() {
        return new NativeScope("BLD001", "POINT001", START, END, END.plusSeconds(60), END.plusSeconds(30));
    }

    private static ActivityFact fact(String id, String value, Instant eventTime) {
        return new ActivityFact(id, new BigDecimal(value), eventTime, eventTime.plusMillis(1), "Q0",
                "QUALITY_V1", false, null, null, null, null);
    }

    private static MeterEventEvidence event(
            MeterEventType type, Instant occurredAt, String before, String after, String modulus) {
        return new MeterEventEvidence("EVENT", "EVENT_V1", "BLD001", "POINT001", type, occurredAt,
                EvidenceStatus.APPROVED, decimal(before), decimal(after), decimal(modulus), null, null,
                null, null, "审核事件依据", 101, 202L, false);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static void assertCode(Runnable action, String expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(expected);
    }
}
