package com.platform.energy.summary;

import com.platform.energy.summary.EnergySummaryModels.AssignmentEvidence;
import com.platform.energy.summary.EnergySummaryModels.BoundaryPolicyVersion;
import com.platform.energy.summary.EnergySummaryModels.QueryDimension;
import com.platform.energy.summary.EnergySummaryModels.SnapshotMeasure;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnergyBoundarySummaryCoreTest {
    private static final Instant START = Instant.parse("2026-07-31T16:00:00Z");
    private static final Instant END = Instant.parse("2026-08-31T16:00:00Z");
    private final EnergyBoundarySummaryCore core = new EnergyBoundarySummaryCore();

    @Test
    void keepsMainTotalAndReturnsSubmeterResidualAndUnallocatedBuckets() {
        List<SnapshotMeasure> measures = List.of(
                measure("MAIN", "1000", "0.1228"),
                measure("SUB1", "300", "0.03684"),
                measure("SUB2", "400", "0.04912"));
        List<AssignmentEvidence> assignments = List.of(
                assignment("MAIN", "MAIN", "SPACE", "BUILDING_SCOPE"),
                assignment("SUB1", "SUB", "SPACE", "SPACE_A"),
                assignment("SUB1", "SUB", "SYSTEM", "SYSTEM_A"),
                assignment("SUB2", "SUB", "SPACE", "SPACE_B"));

        var aggregate = core.summarize(measures, assignments,
                List.of(policy("MAIN_WITH_SUBMETER_BREAKDOWN"))).getFirst();

        assertThat(aggregate.authorityQuantity()).isEqualByComparingTo("1000");
        assertThat(aggregate.assignedQuantity()).isEqualByComparingTo("400");
        assertThat(aggregate.unallocatedQuantity()).isEqualByComparingTo("600");
        assertThat(aggregate.residualQuantity()).isEqualByComparingTo("300");
        assertThat(aggregate.authorityQuantity()).isNotEqualByComparingTo("1700");

        var groups = core.project(List.of(aggregate),
                List.of(QueryDimension.BUILDING, QueryDimension.SPACE));
        assertThat(groups).anySatisfy(group -> {
            assertThat(group.groupKey().get("SPACE")).isEqualTo("SPACE_B");
            assertThat(group.assignedQuantities().get("kWh")).isEqualByComparingTo("400");
        });
        assertThat(groups).anySatisfy(group -> {
            assertThat(group.groupKey().get("SPACE")).isEqualTo("UNALLOCATED");
            assertThat(group.unallocatedQuantities().get("kWh")).isEqualByComparingTo("600");
            assertThat(group.residualQuantities().get("kWh")).isEqualByComparingTo("300");
        });
    }

    @Test
    void keepsGrossDirectionsAndCalculatesNetWithoutOverwritingThem() {
        SnapshotMeasure inbound = measure("IN", "1000", "0.1228");
        SnapshotMeasure outbound = measure("OUT", "200", "0.02456");
        List<AssignmentEvidence> assignments = List.of(
                assignment("IN", "INDEPENDENT", "SPACE", "SPACE_A"),
                outbound("OUT"));
        var aggregates = core.summarize(List.of(inbound, outbound), assignments,
                List.of(policy("INDEPENDENT_METER_SUM")));

        var group = core.project(aggregates, List.of(QueryDimension.BUILDING)).getFirst();

        assertThat(group.grossInboundQuantities().get("kWh")).isEqualByComparingTo("1000");
        assertThat(group.grossOutboundQuantities().get("kWh")).isEqualByComparingTo("200");
        assertThat(group.netQuantities().get("kWh")).isEqualByComparingTo("800");
        assertThat(group.originalQuantities().get("kWh")).isEqualByComparingTo("1200");
        assertThat(group.tceByPerspective().get("CALORIFIC_EQUIVALENT"))
                .isEqualByComparingTo("0.09824");
    }

    @Test
    void rejectsUnknownMeterRoleInsteadOfGuessingAggregation() {
        AssignmentEvidence unknown = new AssignmentEvidence("REL_V1", 7, "MAIN", "ASSIGNED",
                "BOUNDARY_A", "BA", "模拟边界", "CONFIRMED", "ACTIVE", "UNKNOWN",
                "INBOUND", "PENDING_EXPERT", "SPACE", "SPACE_A", "SA", "空间A");

        assertThatThrownBy(() -> core.summarize(List.of(measure("MAIN", "1000", "0.1228")),
                List.of(unknown), List.of(policy("MAIN_METER_TOTAL"))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo("ENERGY_SUMMARY_RELATION_UNCONFIRMED"));
    }

    @Test
    void simulationPolicyCannotProduceFormalResultAndMissingPolicyIsRejected() {
        SnapshotMeasure formalInput = new SnapshotMeasure("SNAP_MAIN", "BLD001", "MAIN",
                "MONTH", START, END, "Asia/Shanghai", "LOCKED_COMPLETE", "FORMAL",
                "GRID_ELECTRICITY", new BigDecimal("1000"), "kWh", new BigDecimal("0.1228"),
                "tce", BigDecimal.ONE, List.of(), "hash-main", "REL_V1", 7,
                "PURCHASED_ELECTRICITY", "CALORIFIC_EQUIVALENT", "FORMULA_V1+PARAM_V1");

        assertThat(core.summarize(List.of(formalInput),
                List.of(assignment("MAIN", "MAIN", "SPACE", "SPACE_A")),
                List.of(policy("MAIN_METER_TOTAL"))).getFirst().resultNature())
                .isEqualTo("DEVELOPMENT_SIMULATION");
        assertThatThrownBy(() -> core.summarize(List.of(formalInput),
                List.of(assignment("MAIN", "MAIN", "SPACE", "SPACE_A")), List.of()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo("ENERGY_SUMMARY_POLICY_REQUIRED"));
    }

    private SnapshotMeasure measure(String pointId, String quantity, String tce) {
        return new SnapshotMeasure("SNAP_" + pointId, "BLD001", pointId, "MONTH",
                START, END, "Asia/Shanghai", "LOCKED_COMPLETE", "DEVELOPMENT_SIMULATION",
                "GRID_ELECTRICITY", new BigDecimal(quantity), "kWh", new BigDecimal(tce),
                "tce", BigDecimal.ONE, List.of(), "hash-" + pointId, "REL_V1", 7,
                "PURCHASED_ELECTRICITY", "CALORIFIC_EQUIVALENT", "FORMULA_V1+PARAM_V1");
    }

    private AssignmentEvidence assignment(
            String pointId, String role, String targetType, String targetId) {
        return new AssignmentEvidence("REL_V1", 7, pointId, "ASSIGNED", "BOUNDARY_A",
                "BA", "模拟边界", "CONFIRMED", "ACTIVE", role, "INBOUND", "CONFIRMED",
                targetType, targetId, targetId, targetId);
    }

    private AssignmentEvidence outbound(String pointId) {
        return new AssignmentEvidence("REL_V1", 7, pointId, "ASSIGNED", "BOUNDARY_A",
                "BA", "模拟边界", "CONFIRMED", "ACTIVE", "INDEPENDENT", "OUTBOUND",
                "CONFIRMED", "SPACE", "SPACE_A", "SA", "空间A");
    }

    private BoundaryPolicyVersion policy(String mode) {
        return new BoundaryPolicyVersion("POLICY_A", "POLICY_V1", 1, "BLD001",
                "BOUNDARY_A", "GRID_ELECTRICITY", mode, "APPROVED", "SIMULATION",
                "研发模拟总分表口径", LocalDateTime.of(2026, 1, 1, 0, 0), null,
                1, 101L, LocalDateTime.of(2026, 1, 1, 0, 0), 202L,
                LocalDateTime.of(2026, 1, 2, 0, 0), "研发模拟审核");
    }
}
