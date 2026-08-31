package com.platform.relation;

import com.platform.framework.exception.BusinessException;
import com.platform.relation.api.RelationContracts.MeterStructureRequest;
import com.platform.relation.api.RelationContracts.MeteringBoundaryRequest;
import com.platform.relation.api.RelationContracts.RevisionReasonRequest;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Collection;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class MeterStructureGovernanceIntegrationTest {
    private static final Set<String> ENERGY = Set.of("ENERGY_MANAGER");

    @Autowired private RelationGovernanceService service;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private BuildingScopeService buildingScopeService;

    @BeforeEach
    void setUp() {
        when(buildingScopeService.canAccess(anyLong(), any(Collection.class), any())).thenReturn(true);
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void createsMainSubAndIndependentStructuresWithDirectionsAndHierarchyQuery() {
        var draft = service.initialize(101L, ENERGY, "BLD001", "meter-init-1", "表计结构测试");
        var boundary = service.createBoundary(101L, ENERGY, draft.versionId(), "meter-boundary-1",
                new MeteringBoundaryRequest("POWER_IN", "建筑购电边界", "ELECTRICITY",
                        "CONFIRMED", "SYNTHETIC_LEDGER_REF", draft.revision()));
        String mainPoint = pointNode("BLD001", "POINT004");
        String subPoint = pointNode("BLD001", "POINT015");
        String independentPoint = pointNode("BLD001", "POINT016");

        var main = service.createMeterStructure(101L, ENERGY, draft.versionId(), "meter-main-1",
                request(boundary.boundaryId(), mainPoint, "MAIN", null, "INBOUND",
                        "CONFIRMED", null, 1L));
        var sub = service.createMeterStructure(101L, ENERGY, draft.versionId(), "meter-sub-1",
                request(boundary.boundaryId(), subPoint, "SUB", mainPoint, "OUTBOUND",
                        "CONFIRMED", null, 2L));
        service.createMeterStructure(101L, ENERGY, draft.versionId(), "meter-independent-1",
                request(boundary.boundaryId(), independentPoint, "INDEPENDENT", null,
                        "BIDIRECTIONAL", "CONFIRMED", null, 3L));

        var hierarchy = service.historicalMeterHierarchy(101L, ENERGY, "BLD001",
                draft.versionId(), mainPoint);
        assertThat(hierarchy.meter().meterRole()).isEqualTo("MAIN");
        assertThat(hierarchy.children()).extracting("structureItemId").containsExactly(sub.structureItemId());
        assertThat(service.versionDetail(101L, ENERGY, "BLD001", draft.versionId())
                .counts().meterStructures()).isEqualTo(3);
        assertThat(service.audits(101L, ENERGY, "BLD001"))
                .anyMatch(audit -> "CREATE_METER_STRUCTURE".equals(audit.actionType())
                        && main.structureItemId().equals(audit.objectId()));
    }

    @Test
    void rejectsInvalidParentsDuplicatesCrossBuildingAndDirectOrIndirectCycles() {
        var draft = service.initialize(101L, ENERGY, "BLD001", "meter-init-2", "层级拒绝测试");
        service.initialize(101L, ENERGY, "BLD002", "meter-init-2-b2", "跨建筑层级拒绝测试");
        String pointA = pointNode("BLD001", "POINT004");
        String pointB = pointNode("BLD001", "POINT015");
        String foreign = pointNode("BLD002", "POINT020");

        assertCode(() -> service.createMeterStructure(101L, ENERGY, draft.versionId(), "bad-sub",
                request(null, pointA, "SUB", null, "INBOUND", "CONFIRMED", null, 0L)),
                RelationErrors.VALIDATION_FAILED);
        assertCode(() -> service.createMeterStructure(101L, ENERGY, draft.versionId(), "bad-independent",
                request(null, pointA, "INDEPENDENT", pointB, "INBOUND", "CONFIRMED", null, 0L)),
                RelationErrors.VALIDATION_FAILED);
        assertCode(() -> service.createMeterStructure(101L, ENERGY, draft.versionId(), "bad-self",
                request(null, pointA, "SUB", pointA, "INBOUND", "CONFIRMED", null, 0L)),
                RelationErrors.CYCLE_DETECTED);
        assertCode(() -> service.createMeterStructure(101L, ENERGY, draft.versionId(), "bad-cross",
                request(null, pointA, "SUB", foreign, "INBOUND", "CONFIRMED", null, 0L)),
                RelationErrors.CROSS_BUILDING);

        var main = service.createMeterStructure(101L, ENERGY, draft.versionId(), "cycle-main",
                request(null, pointA, "MAIN", null, "INBOUND", "CONFIRMED", null, 0L));
        service.createMeterStructure(101L, ENERGY, draft.versionId(), "cycle-sub",
                request(null, pointB, "SUB", pointA, "OUTBOUND", "CONFIRMED", null, 1L));
        assertCode(() -> service.updateMeterStructure(101L, ENERGY, draft.versionId(),
                main.structureItemId(), request(null, pointA, "SUB", pointB, "INBOUND",
                        "CONFIRMED", null, 2L)), RelationErrors.CYCLE_DETECTED);
        assertThat(service.versionDetail(101L, ENERGY, "BLD001", draft.versionId())
                .version().revision()).isEqualTo(2L);
        assertCode(() -> service.createMeterStructure(101L, ENERGY, draft.versionId(), "duplicate",
                request(null, pointA, "MAIN", null, "INBOUND", "CONFIRMED", null, 2L)),
                RelationErrors.REFERENCE_CONFLICT);
    }

    @Test
    void unknownPendingIsDraftableButBlocksSubmissionAndAppearsInDiff() {
        var draft = service.initialize(101L, ENERGY, "BLD001", "meter-init-3", "待确认测试");
        String point = pointNode("BLD001", "POINT004");
        service.createMeterStructure(101L, ENERGY, draft.versionId(), "meter-unknown",
                request(null, point, "UNKNOWN", null, "UNKNOWN", "PENDING_EXPERT",
                        "EXPERT_REQUIRED", 0L));

        var validation = service.validate(101L, ENERGY, draft.versionId());
        assertThat(validation.pendingExpertCount()).isPositive();
        assertThat(service.versionDiff(101L, ENERGY, "BLD001",
                draft.versionId(), draft.versionId(), 20).meterStructureAddedCount()).isZero();
        assertCode(() -> service.submit(101L, ENERGY, draft.versionId(), "meter-submit-pending",
                new RevisionReasonRequest(1L, "尝试提交")), RelationErrors.PENDING_EXPERT);
    }

    private MeterStructureRequest request(
            String boundaryId, String point, String role, String parent, String direction,
            String confirmation, String reasonCode, long revision) {
        return new MeterStructureRequest(boundaryId, point, role, parent, direction, confirmation,
                reasonCode, reasonCode == null ? null : "等待能源专家确认",
                confirmation.equals("CONFIRMED") ? "SYNTHETIC_EVIDENCE" : null,
                "合成软件验证", revision);
    }

    private String pointNode(String buildingId, String pointId) {
        return jdbc.queryForObject("""
                SELECT node_id FROM biz_relation_node
                WHERE building_id=? AND node_type='POINT' AND business_object_id=?
                """, String.class, buildingId, pointId);
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String code) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private void clean() {
        jdbc.update("DELETE FROM biz_relation_validation_issue");
        jdbc.update("DELETE FROM biz_relation_review_request");
        jdbc.update("DELETE FROM biz_relation_audit_log");
        jdbc.update("DELETE FROM biz_meter_structure_version_item");
        jdbc.update("DELETE FROM biz_metering_assignment_version_item");
        jdbc.update("DELETE FROM biz_semantic_relation_version_item");
        jdbc.update("DELETE FROM biz_asset_assignment_version_item");
        jdbc.update("DELETE FROM biz_space_parent_version_item");
        jdbc.update("DELETE FROM biz_relation_node");
        jdbc.update("DELETE FROM biz_metering_boundary");
        jdbc.update("DELETE FROM biz_relation_version");
        jdbc.update("DELETE FROM biz_relation_model");
    }
}
