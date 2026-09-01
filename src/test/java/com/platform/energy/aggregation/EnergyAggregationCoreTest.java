package com.platform.energy.aggregation;

import com.platform.energy.aggregation.EnergyAggregationModels.*;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnergyAggregationCoreTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-01-01T02:00:00Z");
    private final EnergyAggregationCore core = new EnergyAggregationCore();

    @Test
    void executorLoadsTheFrozenSnapshotOnlyThroughTheInputPort() {
        AggregationInput snapshot = input(ValueSemantics.CUMULATIVE, "KWH", List.of(
                fact("start", "100", START), fact("end", "125", END)),
                List.of(), List.of(), null);
        EnergyAggregationInputPort port = mock(EnergyAggregationInputPort.class);
        when(port.load(snapshot.query())).thenReturn(snapshot);

        AggregationResult result = new EnergyAggregationExecutor(core)
                .execute(snapshot.query(), port);

        assertThat(result.quantity()).isEqualByComparingTo("25");
        verify(port).load(snapshot.query());
    }

    @Test
    void aggregatesCumulativeReadingsFromPinnedBoundaryAnchors() {
        AggregationResult result = core.aggregate(input(ValueSemantics.CUMULATIVE, "KWH", List.of(
                fact("before", "90", START.minusSeconds(60)),
                fact("start", "100", START),
                fact("middle", "130", START.plusSeconds(3600)),
                fact("end", "160", END)), List.of(), List.of(), null));

        assertThat(result.quantity()).isEqualByComparingTo("60");
        assertThat(result.resultUnitCode()).isEqualTo("KWH");
        assertThat(result.pointBindingVersionId()).isEqualTo("BINDING_V1");
        assertThat(result.relationVersionId()).isEqualTo("RELATION_V1");
        assertThat(result.qualityPolicyVersions()).containsExactly("QUALITY_V1");
    }

    @Test
    void handlesResetRolloverAndReplacementAsDifferentApprovedEvents() {
        assertThat(core.aggregate(cumulativeDrop("900", "10",
                event(MeterEventType.RESET, "RESET_V1", "950", "0", null))).quantity())
                .isEqualByComparingTo("60");
        assertThat(core.aggregate(cumulativeDrop("9990", "15",
                event(MeterEventType.ROLLOVER, "ROLLOVER_V1", null, null, "10000"))).quantity())
                .isEqualByComparingTo("25");
        MeterEventEvidence replacement = new MeterEventEvidence(
                "REPLACE", "REPLACE_V1", "BLD001", "POINT001",
                MeterEventType.REPLACEMENT, START.plusSeconds(3600),
                EvidenceStatus.APPROVED, new BigDecimal("950"), new BigDecimal("5"), null,
                "OLD_METER", "NEW_METER", "RELATION_OLD", "RELATION_NEW",
                "模拟换表证据", 101, 202L, true);
        assertThat(core.aggregate(cumulativeDrop("900", "25", replacement)).quantity())
                .isEqualByComparingTo("70");
    }

    @Test
    void neverTurnsUnknownNegativeDeltaIntoPositiveConsumption() {
        AggregationInput input = cumulativeDrop("100", "90", null);

        assertCode(() -> core.aggregate(input),
                EnergyAggregationErrors.NEGATIVE_DELTA_UNCLASSIFIED);
    }

    @Test
    void rejectsRolloverWithoutModulusAndConflictingEvents() {
        assertCode(() -> core.aggregate(cumulativeDrop("100", "10",
                        event(MeterEventType.ROLLOVER, "ROLLOVER_V1", null, null, null))),
                EnergyAggregationErrors.INPUT_INCOMPLETE);
        AggregationInput original = cumulativeDrop("100", "10",
                event(MeterEventType.RESET, "RESET_V1", "110", "0", null));
        AggregationInput conflict = new AggregationInput(original.query(), original.measurement(),
                original.assignment(), original.activityWatermark(), original.facts(),
                List.of(original.meterEvents().getFirst(),
                        event(MeterEventType.ROLLOVER, "ROLLOVER_V1", null, null, "1000")),
                original.corrections(), original.integrationPolicy());
        assertCode(() -> core.aggregate(conflict),
                EnergyAggregationErrors.EVENT_EVIDENCE_CONFLICT);
    }

    @Test
    void preservesOriginalFactAndUsesOnlyOneApprovedQualityGatedCorrection() {
        List<ActivityFact> facts = List.of(fact("start", "100", START), fact("bad", "90", END));
        CorrectionEvidence correction = new CorrectionEvidence(
                "CORRECTION", "CORRECTION_V1", "bad", new BigDecimal("90"),
                new BigDecimal("140"), "模拟错误修正", EvidenceStatus.APPROVED,
                "模拟审核证据", 101, 202L, true);
        AggregationResult result = core.aggregate(input(ValueSemantics.CUMULATIVE, "KWH", facts,
                List.of(), List.of(correction), null));

        assertThat(result.quantity()).isEqualByComparingTo("40");
        assertThat(result.correctionVersions()).containsExactly("CORRECTION_V1");
        assertThat(facts.getLast().rawValue()).isEqualByComparingTo("90");

        CorrectionEvidence duplicate = new CorrectionEvidence(
                "CORRECTION_2", "CORRECTION_V2", "bad", new BigDecimal("90"),
                new BigDecimal("150"), "冲突修正", EvidenceStatus.APPROVED,
                "模拟审核证据2", 103, 204L, true);
        assertCode(() -> core.aggregate(input(ValueSemantics.CUMULATIVE, "KWH", facts,
                        List.of(), List.of(correction, duplicate), null)),
                EnergyAggregationErrors.CORRECTION_CONFLICT);
    }

    @Test
    void sumsOnlyContiguousExplicitSourcePeriods() {
        ActivityFact first = periodFact("p1", "10", START, START.plusSeconds(3600));
        ActivityFact second = periodFact("p2", "20", START.plusSeconds(3600), END);
        AggregationResult result = core.aggregate(input(ValueSemantics.PERIOD_TOTAL, "KWH",
                List.of(first, second), List.of(), List.of(), null));

        assertThat(result.quantity()).isEqualByComparingTo("30");

        ActivityFact overlap = periodFact("p2", "20", START.plusSeconds(1800), END);
        assertCode(() -> core.aggregate(input(ValueSemantics.PERIOD_TOTAL, "KWH",
                        List.of(first, overlap), List.of(), List.of(), null)),
                EnergyAggregationErrors.PERIOD_COVERAGE_INVALID);
        assertCode(() -> core.aggregate(input(ValueSemantics.PERIOD_TOTAL, "KWH",
                        List.of(fact("missing", "30", START)), List.of(), List.of(), null)),
                EnergyAggregationErrors.PERIOD_COVERAGE_INVALID);
    }

    @Test
    void integratesInstantaneousPowerWithVersionedStepAndTrapezoidalPolicies() {
        List<ActivityFact> facts = List.of(
                fact("start", "10", START),
                fact("middle", "20", START.plusSeconds(3600)),
                fact("end", "30", END));

        AggregationResult step = core.aggregate(input(ValueSemantics.INSTANTANEOUS, "KW", facts,
                List.of(), List.of(), policy(IntegrationMethod.STEP_PREVIOUS, 3600, "1")));
        AggregationResult trapezoid = core.aggregate(input(ValueSemantics.INSTANTANEOUS, "KW", facts,
                List.of(), List.of(), policy(IntegrationMethod.TRAPEZOIDAL, 3600, "1")));

        assertThat(step.quantity()).isEqualByComparingTo("30");
        assertThat(trapezoid.quantity()).isEqualByComparingTo("40");
        assertThat(step.resultUnitCode()).isEqualTo("KWH");
        assertThat(step.integrationPolicyVersionId()).isEqualTo("INTEGRATION_V1");
    }

    @Test
    void rejectsOversizedInstantaneousGapsAndMissingBoundaryReadings() {
        List<ActivityFact> facts = List.of(
                fact("start", "10", START),
                fact("middle", "20", START.plusSeconds(3600)),
                fact("end", "30", END));
        assertCode(() -> core.aggregate(input(ValueSemantics.INSTANTANEOUS, "KW", facts,
                        List.of(), List.of(), policy(IntegrationMethod.STEP_PREVIOUS, 1800, "0.75"))),
                EnergyAggregationErrors.COVERAGE_INSUFFICIENT);
        assertCode(() -> core.aggregate(input(ValueSemantics.INSTANTANEOUS, "KW",
                        facts.subList(1, 3), List.of(), List.of(),
                        policy(IntegrationMethod.STEP_PREVIOUS, 3600, "0.5"))),
                EnergyAggregationErrors.ANCHOR_MISSING);
    }

    private AggregationInput cumulativeDrop(
            String previous, String current, MeterEventEvidence event) {
        return input(ValueSemantics.CUMULATIVE, "KWH", List.of(
                        fact("start", previous, START), fact("end", current, END)),
                event == null ? List.of() : List.of(event), List.of(), null);
    }

    private AggregationInput input(
            ValueSemantics semantics, String unit, List<ActivityFact> facts,
            List<MeterEventEvidence> events, List<CorrectionEvidence> corrections,
            IntegrationPolicy policy) {
        AggregationQuery query = new AggregationQuery("BLD001", "POINT001", START, END,
                END.plusSeconds(300));
        MeasurementContext context = new MeasurementContext(
                "ELECTRICITY", "BINDING_V1", unit, "UNIT_V1", semantics,
                null, DataNature.SIMULATED, "CONFIRMED", START.minusSeconds(3600),
                null, "模拟测点绑定");
        MeteringAssignmentEvidence assignment = new MeteringAssignmentEvidence(
                "RELATION_V1", 7, "ASSIGNMENT_V1", "BOUNDARY_V1", "SPACE_V1",
                "ASSIGNED", "CONFIRMED");
        return new AggregationInput(query, context, assignment, END.plusSeconds(60), facts, events,
                corrections, policy);
    }

    private ActivityFact fact(String id, String value, Instant time) {
        return new ActivityFact(id, new BigDecimal(value), time, time.plusSeconds(1),
                "Q0", "QUALITY_V1", false, null, null, null, null);
    }

    private ActivityFact periodFact(String id, String value, Instant from, Instant to) {
        return new ActivityFact(id, new BigDecimal(value), to.minusSeconds(1), to,
                "Q0", "QUALITY_V1", false, from, to, "UTC", "PERIOD_V1");
    }

    private MeterEventEvidence event(
            MeterEventType type, String version, String before, String after, String modulus) {
        return new MeterEventEvidence(type.name(), version, "BLD001", "POINT001", type,
                START.plusSeconds(3600),
                EvidenceStatus.APPROVED, decimal(before), decimal(after), decimal(modulus),
                null, null, null, null, "模拟事件证据", 101, 202L, true);
    }

    private IntegrationPolicy policy(IntegrationMethod method, long gap, String coverage) {
        return new IntegrationPolicy("INTEGRATION_V1", method, gap, new BigDecimal(coverage),
                BoundaryHandling.REQUIRE_BOUNDARY_READINGS, EvidenceStatus.APPROVED);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(code);
    }
}
