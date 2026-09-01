package com.platform.energy.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.energy.catalog.EnergyCatalogLookup;
import com.platform.energy.period.EnergyPeriodAuthorization;
import com.platform.energy.period.EnergyPeriodModels.PeriodSnapshot;
import com.platform.energy.period.EnergyPeriodSnapshotReader;
import com.platform.energy.summary.EnergySummaryModels.BoundaryPolicyVersion;
import com.platform.energy.summary.api.EnergySummaryContracts.SummaryQueryRequest;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.api.RelationContracts.MeteringAssignmentView;
import com.platform.relation.api.RelationContracts.MeteringAssignmentsView;
import com.platform.relation.api.RelationContracts.QueryMetadata;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnergyBoundarySummaryServiceTest {
    private static final Instant START = Instant.parse("2026-07-31T16:00:00Z");
    private static final Instant END = Instant.parse("2026-08-31T16:00:00Z");

    @Test
    void queriesVisibleSnapshotWithItsHistoricalRelationVersion() {
        EnergyPeriodAuthorization authorization = mock(EnergyPeriodAuthorization.class);
        EnergyBoundarySummaryRepository repository = mock(EnergyBoundarySummaryRepository.class);
        EnergyPeriodSnapshotReader snapshotReader = mock(EnergyPeriodSnapshotReader.class);
        RelationGovernanceService relationService = mock(RelationGovernanceService.class);
        EnergyBoundarySummaryService service = new EnergyBoundarySummaryService(authorization,
                repository, snapshotReader, relationService, mock(EnergyCatalogLookup.class),
                new EnergyBoundarySummaryCore(), new ObjectMapper(),
                mock(AuditEvidenceWriter.class), mock(AuditGovernanceProperties.class));
        PeriodSnapshot snapshot = new PeriodSnapshot("SNAP001", "r".repeat(64), "PROJ001",
                "BLD001", "POINT001", "MONTH", START, END, "Asia/Shanghai", "PERIOD_V1", 1,
                "LOCKED_COMPLETE", "DEVELOPMENT_SIMULATION", "GRID_ELECTRICITY",
                new BigDecimal("1000"), "kWh", new BigDecimal("0.1228"), "tce",
                BigDecimal.ONE, "", "", "{\"relationVersionId\":\"REL_V1\","
                + "\"formulaVersionId\":\"FORMULA_V1\",\"parameterVersionId\":\"PARAM_V1\"}",
                "h".repeat(64), "{\"consumptionScope\":\"PURCHASED_ELECTRICITY\","
                + "\"perspective\":\"CALORIFIC_EQUIVALENT\"}", END, null, null,
                101L, LocalDateTime.of(2026, 9, 2, 9, 0));
        when(snapshotReader.listVisibleSnapshots("BLD001", "MONTH", START, END, 501))
                .thenReturn(List.of(snapshot));
        QueryMetadata metadata = new QueryMetadata("BLD001", "REL_V1", 1,
                LocalDateTime.of(2026, 1, 1, 0, 0), 7, 0, false, 0, 0, 0, 0);
        MeteringAssignmentView assignment = new MeteringAssignmentView("ASSIGN001", "ASSIGNED",
                null, null, "simulation", "BOUNDARY001", "B001", "模拟边界",
                "ELECTRICITY", "CONFIRMED", "ACTIVE", "NODE_POINT001", "POINT001",
                "P001", "模拟总表", "MAIN", "INBOUND", "CONFIRMED", "NODE_SPACE001",
                "SPACE", "SPACE001", "S001", "模拟空间");
        when(relationService.historicalMeteringAssignments(
                101L, Set.of("ENERGY_MANAGER"), "BLD001", "REL_V1", 1, 500))
                .thenReturn(new MeteringAssignmentsView(metadata, 1, 500, 1, List.of(assignment)));
        BoundaryPolicyVersion policy = new BoundaryPolicyVersion("POLICY001", "POLICY_V1", 1,
                "BLD001", "BOUNDARY001", "GRID_ELECTRICITY", "MAIN_METER_TOTAL",
                "APPROVED", "SIMULATION", "模拟口径", LocalDateTime.of(2026, 1, 1, 0, 0),
                null, 1, 101L, LocalDateTime.of(2026, 1, 1, 0, 0), 202L,
                LocalDateTime.of(2026, 1, 2, 0, 0), "模拟审核");
        when(repository.findApproved("BLD001", "BOUNDARY001", "GRID_ELECTRICITY",
                LocalDateTime.of(2026, 8, 1, 0, 0))).thenReturn(policy);

        var result = service.query(101L, Set.of("ENERGY_MANAGER"), new SummaryQueryRequest(
                "BLD001", "MONTH", START, END, List.of("BUILDING", "ENERGY_ITEM")));

        assertThat(result.sourceSnapshotCount()).isEqualTo(1);
        assertThat(result.groups()).singleElement().satisfies(group -> {
            assertThat(group.originalQuantities().get("kWh")).isEqualByComparingTo("1000");
            assertThat(group.relationVersionIds()).containsExactly("REL_V1");
            assertThat(group.summaryPolicyVersionIds()).containsExactly("POLICY_V1");
            assertThat(group.resultNature()).isEqualTo("DEVELOPMENT_SIMULATION");
        });
        verify(relationService).historicalMeteringAssignments(
                101L, Set.of("ENERGY_MANAGER"), "BLD001", "REL_V1", 1, 500);
    }
}
