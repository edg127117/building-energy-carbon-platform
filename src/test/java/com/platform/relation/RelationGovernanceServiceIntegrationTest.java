package com.platform.relation;

import com.platform.framework.exception.BusinessException;
import com.platform.relation.api.RelationContracts.ActivationRequest;
import com.platform.relation.api.RelationContracts.CreateVersionRequest;
import com.platform.relation.api.RelationContracts.MeteringAssignmentRequest;
import com.platform.relation.api.RelationContracts.MeteringBoundaryRequest;
import com.platform.relation.api.RelationContracts.ReviewDecisionRequest;
import com.platform.relation.api.RelationContracts.RevisionReasonRequest;
import com.platform.relation.api.RelationContracts.SemanticRelationRequest;
import com.platform.relation.api.RelationContracts.SpaceParentRequest;
import com.platform.relation.api.RelationContracts.VersionView;
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
class RelationGovernanceServiceIntegrationTest {
    private static final Set<String> ENERGY = Set.of("ENERGY_MANAGER");
    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");
    private static final Set<String> ENERGY_ADMIN = Set.of("ENERGY_MANAGER", "PLATFORM_ADMIN");

    @Autowired private RelationGovernanceService service;
    @Autowired private RelationGovernanceProperties properties;
    @Autowired private RelationGovernanceGuard guard;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private BuildingScopeService buildingScopeService;

    private boolean originalSelfApproval;

    @BeforeEach
    void setUp() {
        originalSelfApproval = properties.isSelfApprovalEnabled();
        when(buildingScopeService.canAccess(anyLong(), any(Collection.class), any())).thenReturn(true);
        cleanRelationData();
    }

    @AfterEach
    void restoreProperties() {
        properties.setSelfApprovalEnabled(originalSelfApproval);
        cleanRelationData();
    }

    @Test
    void initializesSubmitsApprovesActivatesAndCreatesRollbackDraft() {
        VersionView draft = service.initialize(101L, ENERGY, "BLD001", "init-b1", "接管明确旧外键");
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(service.validate(101L, ENERGY, draft.versionId()).errorCount()).isZero();

        var review = service.submit(101L, ENERGY, draft.versionId(), "submit-b1",
                new RevisionReasonRequest(draft.revision(), "提交首版"));
        assertThat(review.status()).isEqualTo("PENDING");
        assertThat(review.snapshotSha256()).hasSize(64);

        var approved = service.approve(202L, ADMIN, review.requestId(), "approve-b1",
                new ReviewDecisionRequest("软件结构校验通过"));
        assertThat(approved.status()).isEqualTo("APPROVED");

        long modelRevision = service.model(202L, ADMIN, "BLD001").modelRevision();
        VersionView effective = service.activate(202L, ADMIN, draft.versionId(), "activate-b1",
                new ActivationRequest(modelRevision, "立即生效"));
        assertThat(effective.status()).isEqualTo("EFFECTIVE");
        assertThat(service.model(202L, ADMIN, "BLD001").governanceMode()).isEqualTo("GOVERNED");
        assertThat(service.effectiveSpaceTree(101L, ENERGY, "BLD001").roots()).isNotEmpty();
        assertThat(service.model(404L, Set.of("BUILDING_OWNER"), "BLD001").draftVersionId()).isNull();

        long activatedRevision = service.model(202L, ADMIN, "BLD001").modelRevision();
        VersionView rollbackDraft = service.createVersion(101L, ENERGY, "BLD001", "rollback-b1",
                new CreateVersionRequest("回滚验证", effective.versionId(), activatedRevision));
        assertThat(rollbackDraft.status()).isEqualTo("DRAFT");
        assertThat(rollbackDraft.copiedFromVersionId()).isEqualTo(effective.versionId());
        assertThat(rollbackDraft.versionNo()).isGreaterThan(effective.versionNo());
    }

    @Test
    void pendingExpertBlocksSubmissionAndCrossBuildingEdgeIsRejected() {
        VersionView b1 = service.initialize(101L, ENERGY, "BLD001", "init-pending-b1", "初始化B1");
        service.initialize(101L, ENERGY, "BLD002", "init-pending-b2", "初始化B2");
        String b1Source = nodeId("BLD001", "EQUIPMENT");
        String b1Target = nodeId("BLD001", "SYSTEM");
        String b2Target = nodeId("BLD002", "SYSTEM");

        assertThatThrownBy(() -> service.addSemanticRelation(101L, ENERGY, b1.versionId(), "cross-edge",
                new SemanticRelationRequest("SERVES", b1Source, b2Target, "MANUAL", "PENDING_EXPERT",
                        null, "跨建筑试探", b1.revision())))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(RelationErrors.CROSS_BUILDING));

        service.addSemanticRelation(101L, ENERGY, b1.versionId(), "pending-edge",
                new SemanticRelationRequest("SERVES", b1Source, b1Target, "MANUAL", "PENDING_EXPERT",
                        null, "等待专业确认", b1.revision()));
        assertThatThrownBy(() -> service.submit(101L, ENERGY, b1.versionId(), "submit-pending",
                new RevisionReasonRequest(1L, "尝试提交")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(RelationErrors.PENDING_EXPERT));
    }

    @Test
    void editsAndRetiresDraftItemsAndExposesHistoricalEvidence() {
        VersionView draft = service.initialize(101L, ENERGY, "BLD001", "init-edit", "初始化编辑测试");
        String source = nodeId("BLD001", "EQUIPMENT");
        String target = nodeId("BLD001", "SYSTEM");
        var relation = service.addSemanticRelation(101L, ENERGY, draft.versionId(), "edit-edge",
                new SemanticRelationRequest("SERVES", source, target, "MANUAL", "PENDING_EXPERT",
                        null, "待确认", draft.revision()));
        service.updateSemanticRelation(101L, ENERGY, draft.versionId(), relation.relationItemId(),
                new SemanticRelationRequest("CONNECTED_TO", source, target, "MANUAL", "PENDING_EXPERT",
                        null, "修订待确认", 1L));
        service.deleteSemanticRelation(101L, ENERGY, draft.versionId(), relation.relationItemId(), 2L);

        var boundary = service.createBoundary(101L, ENERGY, draft.versionId(), "edit-boundary",
                new MeteringBoundaryRequest("TEST_BOUNDARY", "测试边界", null,
                        "PENDING_EXPERT", null, 3L));
        service.updateBoundary(101L, ENERGY, draft.versionId(), boundary.boundaryId(),
                new MeteringBoundaryRequest("TEST_BOUNDARY", "测试边界修订", null,
                        "PENDING_EXPERT", null, 4L));
        var pendingAssignment = new MeteringAssignmentRequest(boundary.boundaryId(), null, null,
                "PENDING_EXPERT", "EXPERT_REQUIRED", "等待确认", null, 5L);
        String assignmentId = service.createMeteringAssignment(101L, ENERGY, draft.versionId(),
                "edit-metering", pendingAssignment);
        assertThat(service.createMeteringAssignment(101L, ENERGY, draft.versionId(),
                "edit-metering", pendingAssignment)).isEqualTo(assignmentId);
        service.updateMeteringAssignment(101L, ENERGY, draft.versionId(), assignmentId,
                new MeteringAssignmentRequest(null, null, null, "UNASSIGNED",
                        "NO_MAPPING", "尚无现场映射", null, 6L));
        service.deleteMeteringAssignment(101L, ENERGY, draft.versionId(), assignmentId, 7L);
        service.retireBoundary(101L, ENERGY, draft.versionId(), boundary.boundaryId(), 8L);

        var detail = service.versionDetail(101L, ENERGY, "BLD001", draft.versionId());
        assertThat(detail.version().revision()).isEqualTo(9L);
        assertThat(detail.counts().semanticRelations()).isZero();
        assertThat(detail.counts().meteringAssignments()).isZero();
        var diff = service.versionDiff(101L, ENERGY, "BLD001",
                draft.versionId(), draft.versionId(), 20);
        assertThat(diff.addedCount()).isZero();
        assertThat(diff.removedCount()).isZero();
        assertThat(service.historicalNodeContext(101L, ENERGY, "BLD001", draft.versionId(),
                "EQUIPMENT", "EQUIP_WCR_B1", 1, 1, 20).metadata().versionId())
                .isEqualTo(draft.versionId());
    }

    @Test
    void developmentSelfApprovalIsExplicitAndAudited() {
        properties.setSelfApprovalEnabled(true);
        VersionView draft = service.initialize(303L, ENERGY_ADMIN, "BLD001", "init-self", "研发初始化");
        var review = service.submit(303L, ENERGY_ADMIN, draft.versionId(), "submit-self",
                new RevisionReasonRequest(draft.revision(), "研发提交"));

        var approved = service.approve(303L, ENERGY_ADMIN, review.requestId(), "approve-self",
                new ReviewDecisionRequest("研发阶段单人闭环"));

        assertThat(approved.selfApprovalDevMode()).isTrue();
        assertThat(service.audits(303L, ENERGY_ADMIN, "BLD001"))
                .anyMatch(audit -> "SELF_APPROVAL_DEV_MODE".equals(audit.actionType()));
    }

    @Test
    void productionRuleRejectsSubmitterSelfApprovalByDefault() {
        properties.setSelfApprovalEnabled(false);
        VersionView draft = service.initialize(303L, ENERGY_ADMIN, "BLD001", "init-no-self", "初始化");
        var review = service.submit(303L, ENERGY_ADMIN, draft.versionId(), "submit-no-self",
                new RevisionReasonRequest(draft.revision(), "提交"));

        assertThatThrownBy(() -> service.approve(303L, ENERGY_ADMIN, review.requestId(), "approve-no-self",
                new ReviewDecisionRequest("不应通过")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(RelationErrors.FORBIDDEN));
    }

    @Test
    void idempotencyReplayRequiresSameUserAndRequest() {
        VersionView first = service.initialize(101L, ENERGY, "BLD001", "init-replay", "幂等初始化");
        VersionView replay = service.initialize(101L, ENERGY, "BLD001", "init-replay", "幂等初始化");
        assertThat(replay.versionId()).isEqualTo(first.versionId());

        assertThatThrownBy(() -> service.initialize(
                102L, ENERGY, "BLD001", "init-replay", "幂等初始化"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(RelationErrors.IDEMPOTENCY_REUSED));
    }

    @Test
    void governedProjectionCannotBeChangedOrReferencedAssetDeletedThroughLegacyGuard() {
        VersionView draft = service.initialize(101L, ENERGY, "BLD001", "init-guard", "初始化");
        var review = service.submit(101L, ENERGY, draft.versionId(), "submit-guard",
                new RevisionReasonRequest(draft.revision(), "提交"));
        service.approve(202L, ADMIN, review.requestId(), "approve-guard",
                new ReviewDecisionRequest("批准"));
        service.activate(202L, ADMIN, draft.versionId(), "activate-guard",
                new ActivationRequest(service.model(202L, ADMIN, "BLD001").modelRevision(), "生效"));

        assertThatThrownBy(() -> guard.rejectChangedProjection("BLD001", "SPACE001", "SPACE002"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(RelationErrors.GOVERNANCE_REQUIRED));
        assertThatThrownBy(() -> guard.requireDeletable("EQUIPMENT", "EQUIP_WCR_B1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(RelationErrors.REFERENCE_CONFLICT));
    }

    @Test
    void deepSpaceCycleIsRejectedWithoutAdvancingDraftRevision() {
        jdbc.update("""
                INSERT INTO biz_space
                (space_id,building_id,parent_space_id,space_name,space_code,space_type,floor_level,del_flag)
                VALUES ('SPACE_CHILD_TEST','BLD001','SPACE001','子空间','CHILD_TEST','ROOM',1,0)
                """);
        VersionView draft = service.initialize(101L, ENERGY, "BLD001", "init-cycle", "初始化循环测试");

        assertThatThrownBy(() -> service.updateSpaceParent(101L, ENERGY, draft.versionId(), "SPACE001",
                new SpaceParentRequest("SPACE_CHILD_TEST", draft.revision(), 0)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(RelationErrors.CYCLE_DETECTED));
        assertThat(service.versions(101L, ENERGY, "BLD001").getFirst().revision()).isEqualTo(draft.revision());
    }

    private String nodeId(String buildingId, String nodeType) {
        return jdbc.queryForObject("""
                SELECT node_id FROM biz_relation_node
                WHERE building_id=? AND node_type=? ORDER BY business_object_id LIMIT 1
                """, String.class, buildingId, nodeType);
    }

    private void cleanRelationData() {
        jdbc.update("DELETE FROM biz_relation_validation_issue");
        jdbc.update("DELETE FROM biz_relation_review_request");
        jdbc.update("DELETE FROM biz_relation_audit_log");
        jdbc.update("DELETE FROM biz_metering_assignment_version_item");
        jdbc.update("DELETE FROM biz_semantic_relation_version_item");
        jdbc.update("DELETE FROM biz_asset_assignment_version_item");
        jdbc.update("DELETE FROM biz_space_parent_version_item");
        jdbc.update("DELETE FROM biz_relation_node");
        jdbc.update("DELETE FROM biz_metering_boundary");
        jdbc.update("DELETE FROM biz_relation_version");
        jdbc.update("DELETE FROM biz_relation_model");
        jdbc.update("DELETE FROM biz_space WHERE space_id='SPACE_CHILD_TEST'");
    }
}
