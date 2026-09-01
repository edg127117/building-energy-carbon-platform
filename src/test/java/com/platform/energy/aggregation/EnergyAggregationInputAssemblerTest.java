package com.platform.energy.aggregation;

import com.platform.energy.activity.EnergyActivityDataContracts.AggregationActivitySnapshot;
import com.platform.energy.activity.EnergyActivityDataContracts.RawActivityDataView;
import com.platform.energy.activity.EnergyActivityDataService;
import com.platform.energy.aggregation.EnergyAggregationModels.AggregationQuery;
import com.platform.energy.catalog.EnergyCatalogLookup;
import com.platform.energy.catalog.EnergyCatalogLookup.ApprovedUnit;
import com.platform.energy.catalog.EnergyCatalogLookup.ApprovedEnergyItem;
import com.platform.energy.catalog.EnergyCatalogService;
import com.platform.energy.catalog.api.EnergyCatalogContracts.BindingVersionView;
import com.platform.framework.exception.BusinessException;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.api.RelationContracts.MeteringAssignmentView;
import com.platform.relation.api.RelationContracts.MeteringAssignmentsView;
import com.platform.relation.api.RelationContracts.QueryMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnergyAggregationInputAssemblerTest {
    private EnergyActivityDataService activity;
    private EnergyCatalogService catalog;
    private EnergyCatalogLookup lookup;
    private RelationGovernanceService relation;
    private EnergyAggregationGovernanceService governance;
    private EnergyAggregationInputAssembler assembler;

    @BeforeEach
    void setUp() {
        activity = mock(EnergyActivityDataService.class);
        catalog = mock(EnergyCatalogService.class);
        lookup = mock(EnergyCatalogLookup.class);
        relation = mock(RelationGovernanceService.class);
        governance = mock(EnergyAggregationGovernanceService.class);
        assembler = new EnergyAggregationInputAssembler(activity, catalog, lookup, relation, governance);
    }

    @Test
    void composesPublicUpstreamContractsIntoDeterministicCumulativeSimulation() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-01-01T01:00:00Z");
        AggregationQuery query = new AggregationQuery("BLD001", "POINT001", start, end, end);
        when(activity.aggregationSnapshot(eq(101L), eq(Set.of("ENERGY_MANAGER")),
                eq("BLD001"), eq("POINT001"), eq(start.toEpochMilli()), eq(end.toEpochMilli()),
                eq(end.toEpochMilli()))).thenReturn(snapshot("CUMULATIVE", start, end));
        when(catalog.effectiveBinding(anyLong(), anyCollection(), eq("BLD001"), eq("POINT001"),
                any(LocalDateTime.class))).thenReturn(binding());
        when(lookup.findApprovedItem(eq("ELECTRICITY"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(item()));
        when(lookup.findApprovedUnit(eq("KWH"), any(LocalDateTime.class)))
                .thenReturn(Optional.of(unit()));
        when(relation.effectiveMeteringAssignments(anyLong(), anyCollection(), eq("BLD001"),
                eq(1), eq(500))).thenReturn(assignments());
        when(governance.approvedCorrections(anyString(), anyString())).thenReturn(List.of());
        when(governance.approvedEvents(anyString(), anyString(), any(), any())).thenReturn(List.of());

        var result = new EnergyAggregationCore().aggregate(assembler.load(
                101L, Set.of("ENERGY_MANAGER"), query));

        assertThat(result.quantity()).isEqualByComparingTo("30");
        assertThat(result.resultNature()).isEqualTo("DEVELOPMENT_SIMULATION");
        assertThat(result.relationVersionId()).isEqualTo("REL_VERSION_1");
        assertThat(result.pointBindingVersionId()).isEqualTo("BINDING_VERSION_1");
        assertThat(result.qualityPolicyVersions()).containsExactly("SYSTEM_DEFAULT_Q0_ONLY:null:7");
    }

    @Test
    void rejectsPeriodTotalBecauseRawContractHasNoSourcePeriodEvidence() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-01-01T01:00:00Z");
        AggregationQuery query = new AggregationQuery("BLD001", "POINT001", start, end, end);
        when(activity.aggregationSnapshot(anyLong(), anyCollection(), anyString(), anyString(),
                anyLong(), anyLong(), anyLong())).thenReturn(snapshot("PERIOD_TOTAL", start, end));
        when(catalog.effectiveBinding(anyLong(), anyCollection(), anyString(), anyString(), any()))
                .thenReturn(binding());
        when(lookup.findApprovedItem(anyString(), any())).thenReturn(Optional.of(item()));
        when(lookup.findApprovedUnit(anyString(), any())).thenReturn(Optional.of(unit()));

        assertThatThrownBy(() -> assembler.load(101L, Set.of("ENERGY_MANAGER"), query))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo(EnergyAggregationErrors.PERIOD_COVERAGE_INVALID));
    }

    private AggregationActivitySnapshot snapshot(String semantics, Instant start, Instant end) {
        List<RawActivityDataView> facts = List.of(
                fact(100, start.toEpochMilli()), fact(130, end.toEpochMilli()));
        return new AggregationActivitySnapshot("BLD001", "POINT001", "KWH", semantics,
                "CONFIRMED", 3, start.toEpochMilli(), end.toEpochMilli(), end.toEpochMilli(),
                end.toEpochMilli(), "POINT_ID_EVENT_TIME", "EXTERNAL_APPEND_ONLY", facts);
    }

    private RawActivityDataView fact(double value, long eventTime) {
        return new RawActivityDataView("POINT001", "POINT001_CODE", "KWH", "ELECTRICITY",
                "GRID_PURCHASED", "CUMULATIVE", "CONFIRMED", 3, "SIMULATION_V1",
                "POINT001_SOURCE", "SIM_DEVICE", value, eventTime, eventTime,
                "Q0", false, "SYSTEM_DEFAULT_Q0_ONLY", null, 7);
    }

    private BindingVersionView binding() {
        return new BindingVersionView("BINDING_1", "BINDING_VERSION_1", 1, "BLD001",
                "POINT001", "POINT001_CODE", "KWH", "ELECTRICITY", "ITEM_VERSION_1",
                LocalDateTime.of(2025, 1, 1, 0, 0), null, "CONFIRMED",
                "研发模拟绑定", 1, 101L, LocalDateTime.of(2025, 1, 1, 0, 0),
                102L, LocalDateTime.of(2025, 1, 2, 0, 0));
    }

    private ApprovedUnit unit() {
        return new ApprovedUnit("UNIT_KWH", "KWH", "UNIT_VERSION_1", 1, "kWh",
                "ENERGY", "KWH", BigDecimal.ONE, "FIXED_SCALE", null,
                "STANDARD", "研发模拟单位", LocalDateTime.of(2025, 1, 1, 0, 0), null);
    }

    private ApprovedEnergyItem item() {
        return new ApprovedEnergyItem("ITEM_1", "ELECTRICITY", "ITEM_VERSION_1", 1,
                "ELECTRICITY", List.of("PURCHASED_ELECTRICITY"), "STANDARD",
                "研发模拟能源品种", LocalDateTime.of(2025, 1, 1, 0, 0), null);
    }

    private MeteringAssignmentsView assignments() {
        QueryMetadata metadata = new QueryMetadata("BLD001", "REL_VERSION_1", 1,
                LocalDateTime.of(2025, 1, 1, 0, 0), 8L, 0, false,
                0, 0, 0, 0);
        MeteringAssignmentView item = new MeteringAssignmentView("ASSIGNMENT_1", "ASSIGNED",
                null, null, "研发模拟计量分配", "BOUNDARY_1", "BOUNDARY_CODE",
                "建筑总表", "ELECTRICITY", "CONFIRMED", "ACTIVE", "POINT_NODE_1",
                "POINT001", "POINT001_CODE", "电表累计量", "MAIN", "INBOUND",
                "CONFIRMED", "SPACE_NODE_1", "SPACE", "BLD001", "BLD001", "建筑一");
        return new MeteringAssignmentsView(metadata, 1, 500, 1, List.of(item));
    }
}
