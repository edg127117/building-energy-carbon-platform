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
import com.platform.relation.RelationGovernanceProperties;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnergyBoundarySummaryServiceTest {
    private static final Instant START = Instant.parse("2026-07-31T16:00:00Z");
    private static final Instant END = Instant.parse("2026-08-31T16:00:00Z");

    @Test
    void usesTheSameHistoricalHierarchyToAvoidCountingFloorAndRoomSubmetersTwice() {
        var authorization=mock(EnergyPeriodAuthorization.class);
        var repository=mock(EnergyBoundarySummaryRepository.class);
        var reader=mock(EnergyPeriodSnapshotReader.class);
        var relations=mock(RelationGovernanceService.class);
        var roles=Set.of("BUILDING_OWNER");
        var metadata=new QueryMetadata("BLD001","REL_V1",1,LocalDateTime.of(2026,1,1,0,0),7,0,false,0,0,0,0);
        var points=List.of("MAIN","FLOOR","ROOM_A","ROOM_B");
        var quantities=List.of("1000","1000","600","400");
        var snapshots=new java.util.ArrayList<PeriodSnapshot>();
        var assignments=new java.util.ArrayList<MeteringAssignmentView>();
        var structures=new java.util.ArrayList<com.platform.relation.api.RelationContracts.MeterStructureView>();
        for(int i=0;i<points.size();i++) {
            String p=points.get(i),role=i==0?"MAIN":"SUB";
            var quantity=new BigDecimal(quantities.get(i));
            snapshots.add(new PeriodSnapshot("SNAP_"+p,"key_"+p,"PROJ_"+p,"BLD001",p,"MONTH",START,END,"Asia/Shanghai","PERIOD",1,
                    "LOCKED_COMPLETE","FORMAL","GRID_ELECTRICITY",quantity,"kWh",quantity.movePointLeft(3),"TCE",BigDecimal.ONE,"","",
                    "{\"relationVersionId\":\"REL_V1\"}","HASH_"+p,"{\"perspective\":\"CALORIFIC_EQUIVALENT\"}",END,null,null,1,LocalDateTime.of(2026,9,2,0,0)));
            assignments.add(new MeteringAssignmentView("A_"+p,"ASSIGNED",null,null,"isolated", "BOUNDARY","B","测试边界","ELECTRICITY",
                    "CONFIRMED","ACTIVE","N_"+p,p,p,p,role,"INBOUND","CONFIRMED","SPACE_NODE_"+p,"SPACE","SPACE_"+p,p,p));
            String parent=i==0?null:i==1?"N_MAIN":"N_FLOOR";
            structures.add(new com.platform.relation.api.RelationContracts.MeterStructureView("S_"+p,"BOUNDARY","N_"+p,p,role,parent,parent,
                    "INBOUND","CONFIRMED",null,null,"isolated",null,"FORMAL"));
        }
        when(reader.listVisibleSnapshots("BLD001","MONTH",START,END,501)).thenReturn(snapshots);
        when(relations.historicalMeteringAssignments(1L,roles,"BLD001","REL_V1",1,100))
                .thenReturn(new MeteringAssignmentsView(metadata,1,100,4,assignments));
        when(relations.historicalMeterStructures(1L,roles,"BLD001","REL_V1",1,100))
                .thenReturn(new com.platform.relation.api.RelationContracts.MeterStructuresView(metadata,1,100,4,structures));
        when(repository.findApproved(eq("BLD001"),eq("BOUNDARY"),eq("GRID_ELECTRICITY"),any())).thenReturn(
                new BoundaryPolicyVersion("POLICY","POLICY_V1",1,"BLD001","BOUNDARY","GRID_ELECTRICITY","MAIN_WITH_SUBMETER_BREAKDOWN",
                        "APPROVED","FORMAL","isolated",LocalDateTime.of(2026,1,1,0,0),null,0,1,LocalDateTime.of(2026,1,1,0,0),2L,LocalDateTime.of(2026,1,2,0,0),"review"));
        var service=new EnergyBoundarySummaryService(authorization,repository,reader,relations,new RelationGovernanceProperties(),mock(EnergyCatalogLookup.class),
                new EnergyBoundarySummaryCore(),new ObjectMapper(),mock(AuditEvidenceWriter.class),mock(AuditGovernanceProperties.class));
        var result=service.query(1,roles,new SummaryQueryRequest("BLD001","MONTH",START,END,List.of("ENERGY_ITEM")));
        assertThat(result.sourceSnapshotCount()).isEqualTo(4);
        assertThat(result.groups()).singleElement().satisfies(g->{
            assertThat(g.grossInboundQuantities().get("kWh")).isEqualByComparingTo("1000");
            assertThat(g.assignedQuantities().get("kWh")).isEqualByComparingTo("1000");
            assertThat(g.residualQuantities().get("kWh")).isZero();
            assertThat(g.tceByPerspective().get("CALORIFIC_EQUIVALENT")).isEqualByComparingTo("1");
        });
    }

    @Test
    void listsPolicyVersionsOnlyAfterBuildingCheckAndRejectsSilentTruncation() {
        var authorization = mock(EnergyPeriodAuthorization.class);
        var repository = mock(EnergyBoundarySummaryRepository.class);
        var service = new EnergyBoundarySummaryService(authorization, repository,
                mock(EnergyPeriodSnapshotReader.class), mock(RelationGovernanceService.class),
                new RelationGovernanceProperties(), mock(EnergyCatalogLookup.class),
                new EnergyBoundarySummaryCore(), new ObjectMapper(),
                mock(AuditEvidenceWriter.class), mock(AuditGovernanceProperties.class));
        var roles = Set.of("BUILDING_OWNER");
        when(repository.listPolicies("BLD001", 501)).thenReturn(List.of());
        assertThat(service.listPolicies(101L, roles, "BLD001")).isEmpty();
        verify(authorization).requireReader(roles);
        verify(authorization).checkBuilding(101L, roles, "BLD001");
        doThrow(new com.platform.framework.exception.BusinessException(403, "建筑无权限"))
                .when(authorization).checkBuilding(101L, roles, "BLD002");
        assertThatThrownBy(() -> service.listPolicies(101L, roles, "BLD002"))
                .isInstanceOf(com.platform.framework.exception.BusinessException.class);
        verify(repository, never()).listPolicies(eq("BLD002"), anyInt());
        when(repository.listPolicies("BLD001", 501)).thenReturn(
                java.util.Collections.nCopies(501, null));
        assertThatThrownBy(() -> service.listPolicies(101L, roles, "BLD001"))
                .isInstanceOf(com.platform.framework.exception.BusinessException.class)
                .hasMessageContaining("查询上限");
    }

    @Test
    void formalPolicyKeepsExplicitSourcePendingIndependentReviewAndPreservesDutyGate() {
        var authorization=mock(EnergyPeriodAuthorization.class);
        var repository=mock(EnergyBoundarySummaryRepository.class);
        var relations=mock(RelationGovernanceService.class);
        var catalog=mock(EnergyCatalogLookup.class);
        var service=new EnergyBoundarySummaryService(authorization,repository,mock(EnergyPeriodSnapshotReader.class),relations,new RelationGovernanceProperties(),catalog,
                new EnergyBoundarySummaryCore(),new ObjectMapper(),mock(AuditEvidenceWriter.class),mock(AuditGovernanceProperties.class));
        LocalDateTime from=LocalDateTime.of(2026,1,1,0,0);
        when(catalog.findApprovedItem("GRID_ELECTRICITY",from)).thenReturn(java.util.Optional.of(
                new EnergyCatalogLookup.ApprovedEnergyItem("I","GRID_ELECTRICITY","IV",1,"ELECTRICITY",List.of("PURCHASED_ELECTRICITY"),"MANUAL","合成确认规则",from,null)));
        var metadata=new QueryMetadata("BLD001","REL",1,from,1,0,false,0,0,0,0);
        when(relations.effectiveBoundaries(101L,Set.of("ENERGY_MANAGER"),"BLD001",1,100)).thenReturn(
                new com.platform.relation.api.RelationContracts.MeteringBoundariesView(metadata,1,100,1,List.of(
                        new com.platform.relation.api.RelationContracts.MeteringBoundaryView("BOUNDARY","B","边界","ELECTRICITY","CONFIRMED","ACTIVE"))));
        when(repository.nextVersion(anyString())).thenReturn(1);
        var request=new com.platform.energy.summary.api.EnergySummaryContracts.CreateBoundaryPolicyRequest("BLD001","BOUNDARY","GRID_ELECTRICITY","MAIN_METER_TOTAL","FORMAL","合成确认依据",from,null);
        var result=service.createPolicy(101L,Set.of("ENERGY_MANAGER"),request);
        assertThat(result.sourceType()).isEqualTo("FORMAL");
        assertThat(result.status()).isEqualTo("PENDING_EXPERT");
        verify(repository,never()).approve(anyString(),anyInt(),anyLong(),any(),anyString());
        doThrow(new com.platform.framework.exception.BusinessException(403,"无维护职责")).when(authorization).requirePolicyMaintainer(102L,Set.of("ENERGY_MANAGER"));
        assertThatThrownBy(()->service.createPolicy(102L,Set.of("ENERGY_MANAGER"),request)).isInstanceOf(com.platform.framework.exception.BusinessException.class);
    }

    private RelationGovernanceProperties pageLimit(int limit) {
        var properties = new RelationGovernanceProperties();
        properties.setMaxPageSize(limit);
        return properties;
    }

    @Test
    void queriesVisibleSnapshotWithItsHistoricalRelationVersion() {
        EnergyPeriodAuthorization authorization = mock(EnergyPeriodAuthorization.class);
        EnergyBoundarySummaryRepository repository = mock(EnergyBoundarySummaryRepository.class);
        EnergyPeriodSnapshotReader snapshotReader = mock(EnergyPeriodSnapshotReader.class);
        RelationGovernanceService relationService = mock(RelationGovernanceService.class);
        EnergyBoundarySummaryService service = new EnergyBoundarySummaryService(authorization,
                repository, snapshotReader, relationService, pageLimit(17), mock(EnergyCatalogLookup.class),
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
                101L, Set.of("ENERGY_MANAGER"), "BLD001", "REL_V1", 1, 17))
                .thenReturn(new MeteringAssignmentsView(metadata, 1, 17, 1, List.of(assignment)));
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
                101L, Set.of("ENERGY_MANAGER"), "BLD001", "REL_V1", 1, 17);
    }
}
