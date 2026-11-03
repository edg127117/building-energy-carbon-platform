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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnergyBoundarySummaryCoreTest {
    private static final Instant START = Instant.parse("2026-07-31T16:00:00Z");
    private static final Instant END = Instant.parse("2026-08-31T16:00:00Z");
    private final EnergyBoundarySummaryCore core = new EnergyBoundarySummaryCore();

    @Test
    void deduplicatesNestedSubmetersBeforeBoundaryBreakdownAndStillValidatesEverySource() {
        var measures=List.of(measure("MAIN","1000","1"),measure("FLOOR","1000","1"),measure("ROOM_A","600","0.6"),measure("ROOM_B","400","0.4"));
        var assignments=List.of(assignment("MAIN","MAIN","SPACE","BUILDING"),assignment("FLOOR","SUB","SPACE","FLOOR"),
                assignment("ROOM_A","SUB","SPACE","ROOM_A"),assignment("ROOM_B","SUB","SPACE","ROOM_B"));
        var tree=Map.of("REL_V1",Map.of("FLOOR",Set.of("MAIN"),"ROOM_A",Set.of("MAIN","FLOOR"),"ROOM_B",Set.of("MAIN","FLOOR")));
        // Without hierarchy this valid tree was rejected as 2000 units of SUB against a 1000-unit MAIN.
        assertThatThrownBy(()->core.summarize(measures,assignments,List.of(policy("MAIN_WITH_SUBMETER_BREAKDOWN")),Map.of())).hasMessageContaining("分表合计");
        var result=core.summarize(measures,assignments,List.of(policy("MAIN_WITH_SUBMETER_BREAKDOWN")),tree).getFirst();
        assertThat(result.authorityQuantity()).isEqualByComparingTo("1000");
        assertThat(result.assignedQuantity()).isEqualByComparingTo("1000");
        assertThat(result.residualQuantity()).isZero();assertThat(result.authorityTce()).isEqualByComparingTo("1");
        assertThat(result.targetContributions()).singleElement().satisfies(c->assertThat(c.targetObjectId()).isEqualTo("FLOOR"));
        assertThatThrownBy(()->core.summarize(List.of(measures.get(0),measures.get(1),measures.get(2),measures.get(2)),assignments,
                List.of(policy("MAIN_WITH_SUBMETER_BREAKDOWN")),tree)).hasMessageContaining("重复测点");
    }

    @Test
    void completeNativeQuantityRequiresExplicitFormalPolicyToRemainFormal() {
        var nativeInput=new SnapshotMeasure("SNAP_MAIN","BLD001","MAIN","MONTH",START,END,"Asia/Shanghai",
                "LOCKED_NATIVE_COMPLETE","FORMAL","GRID_ELECTRICITY",new BigDecimal("1000"),"kWh",null,null,
                BigDecimal.ONE,List.of("TCE_RULE_UNAVAILABLE"),"native-hash","REL_V1",7,"UNSPECIFIED",null,null);
        for(String source:List.of("FORMAL","SIMULATION","UNKNOWN")) {
            var result=core.summarize(List.of(nativeInput),List.of(assignment("MAIN","MAIN","SPACE","A")),List.of(policy("MAIN_METER_TOTAL",source)),Map.of()).getFirst();
            assertThat(result.resultNature()).isEqualTo("FORMAL".equals(source)?"FORMAL":"DEVELOPMENT_SIMULATION");
            assertThat(result.resultCompleteness()).isEqualTo("COMPLETE_NATIVE_ONLY");
            assertThat(result.authorityTce()).isNull();
        }
    }

    @Test
    void nativeBoundaryDoesNotSplitOrDoubleCountWhenSomeMetersHaveNoTce() {
        var nativeMain=new SnapshotMeasure("SNAP_MAIN","BLD001","MAIN","MONTH",START,END,"Asia/Shanghai",
                "LOCKED_NATIVE_COMPLETE","FORMAL","GRID_ELECTRICITY",new BigDecimal("1000"),"kWh",null,null,
                BigDecimal.ONE,List.of("TCE_RULE_UNAVAILABLE"),"native-hash","REL_V1",7,"PURCHASED_ELECTRICITY",null,null);
        var aggregate=core.summarize(List.of(nativeMain,measure("SUB1","300","0.03684")),
                List.of(assignment("MAIN","MAIN","SPACE","A"),assignment("SUB1","SUB","SPACE","B")),
                List.of(policy("MAIN_WITH_SUBMETER_BREAKDOWN")),Map.of());
        assertThat(aggregate).hasSize(1);
        assertThat(aggregate.getFirst().authorityQuantity()).isEqualByComparingTo("1000");
        assertThat(aggregate.getFirst().residualQuantity()).isEqualByComparingTo("700");
        assertThat(aggregate.getFirst().authorityTce()).isNull();
        assertThat(aggregate.getFirst().resultCompleteness()).isEqualTo("COMPLETE_NATIVE_ONLY");
        assertThat(core.project(aggregate,List.of(QueryDimension.BUILDING)).getFirst().tceByPerspective()).isEmpty();
    }

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
                List.of(policy("MAIN_WITH_SUBMETER_BREAKDOWN")),Map.of()).getFirst();

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
                List.of(policy("INDEPENDENT_METER_SUM")),Map.of());

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
                List.of(unknown), List.of(policy("MAIN_METER_TOTAL")),Map.of()))
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
                List.of(policy("MAIN_METER_TOTAL")),Map.of()).getFirst().resultNature())
                .isEqualTo("DEVELOPMENT_SIMULATION");
        assertThatThrownBy(() -> core.summarize(List.of(formalInput),
                List.of(assignment("MAIN", "MAIN", "SPACE", "SPACE_A")), List.of(),Map.of()))
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
        return policy(mode,"SIMULATION");
    }
    private BoundaryPolicyVersion policy(String mode,String source) {
        return new BoundaryPolicyVersion("POLICY_A", "POLICY_V1", 1, "BLD001",
                "BOUNDARY_A", "GRID_ELECTRICITY", mode, "APPROVED", source,
                "研发模拟总分表口径", LocalDateTime.of(2026, 1, 1, 0, 0), null,
                1, 101L, LocalDateTime.of(2026, 1, 1, 0, 0), 202L,
                LocalDateTime.of(2026, 1, 2, 0, 0), "研发模拟审核");
    }
}
