package com.platform.energy.efficiency;

import com.platform.energy.efficiency.CoolingComputationCore.AlignmentMode;
import com.platform.energy.efficiency.CoolingComputationCore.Calculation;
import com.platform.energy.efficiency.CoolingComputationCore.InputRole;
import com.platform.energy.efficiency.CoolingComputationCore.Rules;
import com.platform.energy.efficiency.CoolingComputationCore.Sample;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class CoolingComputationCoreTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private final CoolingComputationCore core = new CoolingComputationCore();

    @Test
    void calculatesWaterCoolingWithExactSynchronousInputsAndMillisecondDuration() {
        Instant end = START.plusMillis(3_600_500);

        Calculation result = core.calculate(START, end,
                List.of(sample("flow-0", START, "100")),
                List.of(sample("supply-0", START, "7")),
                List.of(sample("return-0", START, "12")),
                rules(AlignmentMode.EXACT_SYNCHRONOUS, 0, 0, 3_600_500, false, false));

        assertThat(result.complete()).isTrue();
        assertThat(result.coverageRatio()).isEqualByComparingTo("1");
        assertThat(result.powerIntervals()).hasSize(1);
        assertThat(result.powerIntervals().getFirst().powerKw())
                .isEqualByComparingTo("580.5555555555555555555555555555556");
        assertThat(result.totalKwh()).isCloseTo(new BigDecimal("580.6361882716049382716049382716049"),
                offset(new BigDecimal("0.000000000000000000000000000001")));
        assertThat(result.powerIntervals().getFirst().inputs())
                .extracting(value -> value.role()).containsExactly(
                        InputRole.FLOW_M3_PER_H,
                        InputRole.SUPPLY_TEMPERATURE_DEG_C,
                        InputRole.RETURN_TEMPERATURE_DEG_C);
    }

    @Test
    void composesChangedFlowWithApprovedBoundedPreviousTemperatureHold() {
        Instant middle = START.plusSeconds(1_800);
        Instant end = START.plusSeconds(3_600);

        Calculation result = core.calculate(START, end,
                List.of(sample("flow-0", START, "100"), sample("flow-1", middle, "200")),
                List.of(sample("supply-0", START, "7")),
                List.of(sample("return-0", START, "12")),
                rules(AlignmentMode.BOUNDED_PREVIOUS_HOLD, 3_600_000, 1_800_000,
                        3_600_000, false, false));

        assertThat(result.complete()).isTrue();
        assertThat(result.powerIntervals()).hasSize(2);
        assertThat(result.totalKwh()).isCloseTo(new BigDecimal("870.833333333333333333333333333333"),
                offset(new BigDecimal("0.00000000000000000000000000001")));
        assertThat(result.powerIntervals().get(1).inputs())
                .filteredOn(value -> value.role() != InputRole.FLOW_M3_PER_H)
                .allSatisfy(value -> {
                    assertThat(value.previousValueHeld()).isTrue();
                    assertThat(value.heldMillis()).isEqualTo(1_800_000L);
                });
    }

    @Test
    void treatsRejectedQualitySampleAsABreakpointInsteadOfBridgingGoodSamples() {
        Instant rejectedAt = START.plusSeconds(3_600);
        Instant restoredAt = START.plusSeconds(7_200);
        Instant end = START.plusSeconds(10_800);

        Calculation result = core.calculate(START, end,
                List.of(sample("flow-0", START, "100"), rejected("flow-bad", rejectedAt, "100"),
                        sample("flow-1", restoredAt, "100")),
                List.of(sample("supply-0", START, "7"), sample("supply-1", restoredAt, "7")),
                List.of(sample("return-0", START, "12"), sample("return-1", restoredAt, "12")),
                rules(AlignmentMode.EXACT_SYNCHRONOUS, 0, 0, 7_200_000, false, false));

        assertThat(result.complete()).isFalse();
        assertThat(result.coverageRatio()).isEqualByComparingTo("0.6666666666666666666666666666666667");
        assertThat(result.issues()).anySatisfy(issue -> {
            assertThat(issue.startInclusive()).isEqualTo(rejectedAt);
            assertThat(issue.endExclusive()).isEqualTo(restoredAt);
            assertThat(issue.reasonCodes()).contains("FLOW_M3_PER_H_QUALITY_REJECTED");
            assertThat(issue.inputs()).extracting(value -> value.inputIndex()).contains(1);
        });
    }

    @Test
    void expiresPreviousValuesAtTheConfiguredBoundaryWithoutCrossingTheGap() {
        Instant expiry = START.plusMillis(1_000);
        Instant end = START.plusMillis(2_000);

        Calculation result = core.calculate(START, end,
                List.of(sample("flow-0", START, "100")),
                List.of(sample("supply-0", START, "7")),
                List.of(sample("return-0", START, "12")),
                rules(AlignmentMode.BOUNDED_PREVIOUS_HOLD, 1_000, 0, 5_000, false, false));

        assertThat(result.complete()).isFalse();
        assertThat(result.coverageRatio()).isEqualByComparingTo("0.5");
        assertThat(result.powerIntervals()).singleElement().satisfies(interval ->
                assertThat(interval.endExclusive()).isEqualTo(expiry));
        assertThat(result.issues()).anySatisfy(issue -> {
            assertThat(issue.startInclusive()).isEqualTo(expiry);
            assertThat(issue.endExclusive()).isEqualTo(end);
            assertThat(issue.reasonCodes()).contains("FLOW_M3_PER_H_HOLD_EXPIRED");
        });
    }

    @Test
    void appliesMaximumGapToEachInputInsteadOfExtendingStaleTemperaturesWithFreshFlow() {
        Instant gapExpiry = START.plusSeconds(300);
        Instant end = START.plusSeconds(420);

        Calculation result = core.calculate(START, end,
                List.of(sample("flow-0", START, "100"), sample("flow-1", START.plusSeconds(60), "100"),
                        sample("flow-2", START.plusSeconds(120), "100"),
                        sample("flow-3", START.plusSeconds(180), "100"),
                        sample("flow-4", START.plusSeconds(240), "100"),
                        sample("flow-5", gapExpiry, "100"),
                        sample("flow-6", START.plusSeconds(360), "100")),
                List.of(sample("supply-0", START, "7")),
                List.of(sample("return-0", START, "12")),
                rules(AlignmentMode.BOUNDED_PREVIOUS_HOLD, 3_600_000, 3_600_000,
                        300_000, false, false));

        assertThat(result.complete()).isFalse();
        assertThat(result.coverageRatio()).isEqualByComparingTo("0.7142857142857142857142857142857143");
        assertThat(result.issues()).anySatisfy(issue -> {
            assertThat(issue.startInclusive()).isEqualTo(gapExpiry);
            assertThat(issue.reasonCodes()).contains("SUPPLY_TEMPERATURE_DEG_C_INTEGRATION_GAP_EXCEEDED",
                    "RETURN_TEMPERATURE_DEG_C_INTEGRATION_GAP_EXCEEDED", "INTEGRATION_GAP_EXCEEDED");
            assertThat(issue.inputs()).extracting(value -> value.inputIndex()).contains(0);
        });
    }

    @Test
    void consumesLeftAnchorStateButKeepsARejectedLeftAnchorAsTheInitialBreakpoint() {
        Instant rejectedAt = START.minusMillis(500);
        Instant restoredAt = START.plusMillis(500);
        Instant end = START.plusMillis(1_000);

        Calculation result = core.calculate(START, end,
                List.of(sample("flow-old", START.minusMillis(750), "100"),
                        rejected("flow-rejected", rejectedAt, "100"), sample("flow-new", restoredAt, "100")),
                List.of(sample("supply-old", START.minusMillis(750), "7"),
                        sample("supply-new", restoredAt, "7")),
                List.of(sample("return-old", START.minusMillis(750), "12"),
                        sample("return-new", restoredAt, "12")),
                rules(AlignmentMode.BOUNDED_PREVIOUS_HOLD, 1_000, 0, 2_000, false, false));

        assertThat(result.complete()).isFalse();
        assertThat(result.coverageRatio()).isEqualByComparingTo("0.5");
        List<CoolingComputationCore.Issue> rejectedIntervals = result.issues().stream()
                .filter(issue -> issue.reasonCodes().contains("FLOW_M3_PER_H_QUALITY_REJECTED"))
                .toList();
        assertThat(rejectedIntervals).hasSize(2);
        assertThat(rejectedIntervals.getFirst().startInclusive()).isEqualTo(START);
        assertThat(rejectedIntervals.getFirst().endExclusive())
                .isEqualTo(rejectedIntervals.get(1).startInclusive());
        assertThat(rejectedIntervals.get(1).endExclusive()).isEqualTo(restoredAt);
        assertThat(rejectedIntervals).allSatisfy(issue ->
                assertThat(issue.inputs()).extracting(value -> value.inputIndex()).contains(1));
    }

    @Test
    void rejectsNegativeFlowReverseDeltaAndUnapprovedZeroValuesWithoutTakingAbsoluteValues() {
        Instant end = START.plusSeconds(1);
        Calculation negativeFlow = core.calculate(START, end,
                List.of(sample("flow-0", START, "-1")),
                List.of(sample("supply-0", START, "7")),
                List.of(sample("return-0", START, "12")),
                rules(AlignmentMode.EXACT_SYNCHRONOUS, 0, 0, 1_000, false, false));
        Calculation reverseDelta = core.calculate(START, end,
                List.of(sample("flow-0", START, "1")),
                List.of(sample("supply-0", START, "12")),
                List.of(sample("return-0", START, "7")),
                rules(AlignmentMode.EXACT_SYNCHRONOUS, 0, 0, 1_000, false, false));
        Calculation zeroFlow = core.calculate(START, end,
                List.of(sample("flow-0", START, "0")),
                List.of(sample("supply-0", START, "7")),
                List.of(sample("return-0", START, "12")),
                rules(AlignmentMode.EXACT_SYNCHRONOUS, 0, 0, 1_000, false, false));

        assertThat(negativeFlow.totalKwh()).isZero();
        assertThat(negativeFlow.issues().getFirst().reasonCodes()).contains("FLOW_M3_PER_H_NEGATIVE_FLOW");
        assertThat(reverseDelta.totalKwh()).isZero();
        assertThat(reverseDelta.issues().getFirst().reasonCodes()).contains("REVERSE_TEMPERATURE_DELTA");
        assertThat(zeroFlow.totalKwh()).isZero();
        assertThat(zeroFlow.issues().getFirst().reasonCodes()).contains("FLOW_M3_PER_H_ZERO_FLOW_NOT_ALLOWED");
    }

    @Test
    void allowsExplicitZeroFlowAndReportsMissingProfessionalParameters() {
        Instant end = START.plusSeconds(1);
        Calculation zeroFlow = core.calculate(START, end,
                List.of(sample("flow-0", START, "0")),
                List.of(sample("supply-0", START, "7")),
                List.of(sample("return-0", START, "12")),
                rules(AlignmentMode.EXACT_SYNCHRONOUS, 0, 0, 1_000, true, false));
        Rules missingRho = new Rules("RULE_V1", null, null, new BigDecimal("4.18"),
                new BigDecimal("0"), new BigDecimal("50"), 0, 0, 1_000,
                false, false, AlignmentMode.EXACT_SYNCHRONOUS);
        Calculation invalidRules = core.calculate(START, end, List.of(), List.of(), List.of(), missingRho);

        assertThat(zeroFlow.complete()).isTrue();
        assertThat(zeroFlow.totalKwh()).isZero();
        assertThat(invalidRules.complete()).isFalse();
        assertThat(invalidRules.issues().getFirst().reasonCodes()).contains("RHO_INVALID");
        assertThat(CoolingComputationCore.validateRules(missingRho)).contains("RHO_INVALID");
    }

    private static Rules rules(
            AlignmentMode mode, long maxHoldMillis, long maxSkewMillis, long maxGapMillis,
            boolean allowZeroFlow, boolean allowZeroDelta) {
        return new Rules("WATER_RULE_V1", mode == AlignmentMode.BOUNDED_PREVIOUS_HOLD ? "APPROVED_RULE" : null,
                new BigDecimal("1000"), new BigDecimal("4.18"), new BigDecimal("0"),
                new BigDecimal("50"), maxHoldMillis, maxSkewMillis, maxGapMillis,
                allowZeroFlow, allowZeroDelta, mode);
    }

    private static Sample sample(String id, Instant time, String value) {
        return new Sample(id, time, new BigDecimal(value), true, "QUALITY_V1");
    }

    private static Sample rejected(String id, Instant time, String value) {
        return new Sample(id, time, new BigDecimal(value), false, "QUALITY_REJECTED_V1");
    }
}
