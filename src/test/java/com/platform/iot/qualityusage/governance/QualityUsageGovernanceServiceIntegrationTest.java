package com.platform.iot.qualityusage.governance;

import com.platform.audit.BackendDuty;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts.ChangeSetCreateRequest;
import com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts.ChangeSetUpdateRequest;
import com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts.ChangeSetView;
import com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts.PolicyDraftRequest;
import com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts.PolicyVersionView;
import com.platform.iot.qualityusage.governance.mapper.BizQualityUsagePolicyVersionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

/** H2 隔离验证治理状态机，不触达 MQTT、TDengine 或现场设备。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class QualityUsageGovernanceServiceIntegrationTest {
    private static final long ADMIN = 1L;
    private static final long ENERGY = 101L;
    private static final long OTHER_ENERGY = 102L;
    private static final Set<String> ADMIN_ROLE = Set.of("PLATFORM_ADMIN");
    private static final Set<String> ENERGY_ROLE = Set.of("ENERGY_MANAGER");

    @Autowired private QualityUsageGovernanceService service;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private BizQualityUsagePolicyVersionMapper versionMapper;

    @AfterEach
    void resetMapperSpy() {
        reset(versionMapper);
    }

    @BeforeEach
    void seedGovernanceDirectoryAndScopes() {
        jdbc.update("INSERT INTO sys_user_building(user_id,building_id) VALUES (?,?)", ENERGY, "BLD001");
        jdbc.update("INSERT INTO sys_user_building(user_id,building_id) VALUES (?,?)", OTHER_ENERGY, "BLD001");
        grantDuty(ENERGY, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        grantDuty(ADMIN, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        grantDuty(ADMIN, BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
        jdbc.update("""
                INSERT INTO biz_quality_usage_scenario
                  (scenario_id,scenario_code,scenario_name,adapter_type,status,introduced_version)
                VALUES ('QUS_RT','POINT_REALTIME_VIEW','实时展示','POINT_REALTIME_GATE','ENABLED','TEST_V1')
                """);
        jdbc.update("""
                INSERT INTO biz_quality_usage_scenario
                  (scenario_id,scenario_code,scenario_name,adapter_type,status,introduced_version)
                VALUES ('QUS_HIS','POINT_HISTORY_VIEW','历史展示','POINT_HISTORY_GATE','ENABLED','TEST_V1')
                """);
        jdbc.update("""
                INSERT INTO biz_quality_usage_scenario
                  (scenario_id,scenario_code,scenario_name,adapter_type,status,introduced_version)
                VALUES ('QUS_IND','INDICATOR_CALCULATION','指标计算','INDICATOR_INPUT_GATE','ENABLED','TEST_V1')
                """);
    }

    @Test
    void normalReviewPublishesWholeChangeSetOnceAndSupportsIdempotentSubmit() {
        ChangeSetView created = service.createChangeSet(ENERGY, ENERGY_ROLE,
                changeSet("首轮策略", policy("POINT001", "POINT_REALTIME_VIEW", List.of("Q0", "Q1")),
                        policy("POINT002", "INDICATOR_CALCULATION", List.of("Q0"))));
        ChangeSetView updated = service.updateChangeSet(ENERGY, ENERGY_ROLE, created.changeSetId(),
                new ChangeSetUpdateRequest(0, "首轮策略更新", "审核快照只含策略版本和允许等级",
                        List.of(policy("POINT001", "POINT_REALTIME_VIEW", List.of("Q0", "Q1")),
                                policy("POINT002", "INDICATOR_CALCULATION", List.of("Q0")))));
        assertThat(updated.revision()).isEqualTo(1);

        var submitted = service.submit(ENERGY, ENERGY_ROLE, created.changeSetId(), "submit-001", "请审核");
        var replay = service.submit(ENERGY, ENERGY_ROLE, created.changeSetId(), "submit-001", "请审核");
        assertThat(replay.requestId()).isEqualTo(submitted.requestId());
        assertThat(submitted.status()).isEqualTo("PENDING");
        assertThat(submitted.snapshotSha256()).hasSize(64);

        var approved = service.approve(ADMIN, ADMIN_ROLE, submitted.requestId(), "approve-001", "批准");
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject(
                "SELECT config_revision FROM biz_quality_usage_config_revision WHERE singleton_id=1", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_quality_usage_policy_version WHERE status='ACTIVE'", Integer.class))
                .isEqualTo(2);
        assertThat(service.listActivePolicies(ENERGY, ENERGY_ROLE, "BLD001", 1, 20).items())
                .extracting(item -> item.allowedQualities())
                .contains(List.of("Q0", "Q1"), List.of("Q0"));
    }

    @Test
    void staleBaseVersionRejectsTheEntireSecondChangeSetWithoutAnotherRevision() {
        ChangeSetView first = service.createChangeSet(ENERGY, ENERGY_ROLE,
                changeSet("首版", policy("POINT003", "POINT_REALTIME_VIEW", List.of("Q0"))));
        ChangeSetView stale = service.createChangeSet(ENERGY, ENERGY_ROLE,
                changeSet("并发草稿", policy("POINT003", "POINT_REALTIME_VIEW", List.of("Q0", "Q1"))));

        var firstReview = service.submit(ENERGY, ENERGY_ROLE, first.changeSetId(), "first-submit", "首版");
        service.approve(ADMIN, ADMIN_ROLE, firstReview.requestId(), "first-approve", "批准首版");
        var staleReview = service.submit(ENERGY, ENERGY_ROLE, stale.changeSetId(), "stale-submit", "旧基础版本");

        assertThatThrownBy(() -> service.approve(ADMIN, ADMIN_ROLE, staleReview.requestId(),
                "stale-approve", "不应部分发布"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo("QUALITY_POLICY_VERSION_CONFLICT"));
        assertThat(service.reviewDetail(ENERGY, ENERGY_ROLE, staleReview.requestId()).status()).isEqualTo("REJECTED");
        assertThat(service.changeSetDetail(ENERGY, ENERGY_ROLE, stale.changeSetId()).status()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject(
                "SELECT config_revision FROM biz_quality_usage_config_revision WHERE singleton_id=1", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_quality_usage_policy_version WHERE status='ACTIVE'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void lateSecondActivationFailureRollsBackEarlierPolicyBeforeRecordingRejection() {
        ChangeSetView changeSet = service.createChangeSet(ENERGY, ENERGY_ROLE,
                changeSet("原子发布", policy("POINT007", "POINT_REALTIME_VIEW", List.of("Q0")),
                        policy("POINT008", "POINT_HISTORY_VIEW", List.of("Q0", "Q1"))));
        var submitted = service.submit(ENERGY, ENERGY_ROLE, changeSet.changeSetId(),
                "atomic-submit", "两个项目必须全量发布");
        String secondVersionId = jdbc.queryForObject("""
                SELECT v.version_id
                FROM biz_quality_usage_policy_version v
                JOIN biz_quality_usage_policy p ON p.policy_id=v.policy_id
                WHERE v.change_set_id=? AND p.point_id='POINT008'
                """, String.class, changeSet.changeSetId());
        doReturn(0).when(versionMapper).activate(eq(secondVersionId), anyLong(), anyLong(), any(), any());

        assertThatThrownBy(() -> service.approve(ADMIN, ADMIN_ROLE, submitted.requestId(),
                "atomic-approve", "第二个条件更新失败"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo("QUALITY_POLICY_STATE_CONFLICT"));

        assertThat(service.reviewDetail(ENERGY, ENERGY_ROLE, submitted.requestId()).status()).isEqualTo("REJECTED");
        assertThat(service.changeSetDetail(ENERGY, ENERGY_ROLE, changeSet.changeSetId()).status()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM biz_quality_usage_policy_version v
                JOIN biz_quality_usage_policy p ON p.policy_id=v.policy_id
                WHERE p.point_id IN ('POINT007','POINT008') AND v.status='ACTIVE'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT config_revision FROM biz_quality_usage_config_revision WHERE singleton_id=1", Long.class))
                .isZero();
    }

    @Test
    void oldDirectPublicationIsRejectedAndDevelopmentSelfApprovalPreservesRollbackFlow() {
        ChangeSetView direct = service.createChangeSet(ADMIN, ADMIN_ROLE,
                changeSet("管理员完整两步发布", policy("POINT004", "POINT_HISTORY_VIEW", List.of())));
        assertThatThrownBy(() -> service.directPublish(ADMIN, ADMIN_ROLE, direct.changeSetId(),
                "direct-001", "绕过审核发布"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("BACKOFFICE_REVIEW_REQUIRED"));

        var submitted = service.submit(ADMIN, ADMIN_ROLE, direct.changeSetId(),
                "self-submit-001", "研发阶段完整提交");
        var approved = service.approve(ADMIN, ADMIN_ROLE, submitted.requestId(),
                "self-approve-001", "研发阶段自审批准");
        assertThat(approved.reviewMode()).isEqualTo("NORMAL");
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.reviewComment()).isEqualTo("研发阶段自审批准");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_quality_usage_audit_log
                WHERE action_type='SELF_APPROVAL_DEV_MODE' AND operator_id=?
                """, Integer.class, ADMIN)).isEqualTo(1);
        PolicyVersionView v1 = service.changeSetDetail(ADMIN, ADMIN_ROLE, direct.changeSetId())
                .policyVersions().getFirst();
        assertThat(v1.allowedQualities()).isEmpty();

        ChangeSetView rollback = service.createChangeSet(ENERGY, ENERGY_ROLE,
                new ChangeSetCreateRequest("BLD001", "复制回滚版本", null,
                        List.of(new PolicyDraftRequest("POINT004", "POINT_HISTORY_VIEW", List.of(),
                                v1.versionId(), "复制正式历史版本"))));
        var rollbackSubmitted = service.submit(ENERGY, ENERGY_ROLE, rollback.changeSetId(),
                "rollback-submit", "提交复制");
        service.approve(ADMIN, ADMIN_ROLE, rollbackSubmitted.requestId(), "rollback-approve", "批准复制");
        PolicyVersionView copied = service.changeSetDetail(ENERGY, ENERGY_ROLE, rollback.changeSetId())
                .policyVersions().getFirst();
        assertThat(copied.versionNo()).isEqualTo(2);
        assertThat(copied.copiedFromVersionId()).isEqualTo(v1.versionId());
        assertThat(copied.status()).isEqualTo("ACTIVE");
        assertThat(copied.allowedQualities()).isEmpty();
        PolicyVersionView retired = service.listVersions(ADMIN, ADMIN_ROLE, v1.policyId()).stream()
                .filter(version -> version.versionId().equals(v1.versionId()))
                .findFirst()
                .orElseThrow();
        // 同一完整分钟连续发布保留 [t,t) 审计版本；它不命中 minuteStart，但不能改写生效规则。
        assertThat(retired.status()).isEqualTo("RETIRED");
        assertThat(retired.effectiveToMs()).isEqualTo(retired.effectiveFromMs());
    }

    @Test
    void stateMachineAndRoleVisibilityPreservePrivateDraftAndAuditBoundaries() {
        ChangeSetView created = service.createChangeSet(ENERGY, ENERGY_ROLE,
                changeSet("状态机", policy("POINT009", "POINT_REALTIME_VIEW", List.of("Q0"))));
        var firstSubmit = service.submit(ENERGY, ENERGY_ROLE, created.changeSetId(),
                "state-submit-1", "先提交再撤回");
        assertThat(service.withdraw(ENERGY, ENERGY_ROLE, created.changeSetId(),
                "state-withdraw", "补充说明").status()).isEqualTo("DRAFT");
        assertThat(service.reviewDetail(ENERGY, ENERGY_ROLE, firstSubmit.requestId()).status()).isEqualTo("WITHDRAWN");

        var resubmitted = service.submit(ENERGY, ENERGY_ROLE, created.changeSetId(),
                "state-submit-2", "重新提交");
        assertThat(service.reject(ADMIN, ADMIN_ROLE, resubmitted.requestId(),
                "state-reject", "审核拒绝").status()).isEqualTo("REJECTED");
        assertThat(service.cancel(ENERGY, ENERGY_ROLE, created.changeSetId(),
                "state-cancel", "放弃本次变更").status()).isEqualTo("CANCELLED");

        ChangeSetView disposable = service.createChangeSet(ENERGY, ENERGY_ROLE,
                changeSet("未提交草稿", policy("POINT010", "POINT_HISTORY_VIEW", List.of("Q0"))));
        service.deleteChangeSet(ENERGY, ENERGY_ROLE, disposable.changeSetId());
        assertThatThrownBy(() -> service.changeSetDetail(ENERGY, ENERGY_ROLE, disposable.changeSetId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("QUALITY_POLICY_NOT_FOUND"));

        assertThatThrownBy(() -> service.changeSetDetail(OTHER_ENERGY, ENERGY_ROLE, created.changeSetId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("QUALITY_POLICY_NOT_FOUND"));
        assertThatThrownBy(() -> service.reviewDetail(OTHER_ENERGY, ENERGY_ROLE, resubmitted.requestId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("QUALITY_POLICY_NOT_FOUND"));
        assertThat(service.listAudits(OTHER_ENERGY, ENERGY_ROLE, "BLD001", 1, 50).items()).isEmpty();
    }

    @Test
    void scopeOwnershipExpectedRevisionAndQualityValidationAreEnforced() {
        ChangeSetView created = service.createChangeSet(ENERGY, ENERGY_ROLE,
                changeSet("私有草稿", policy("POINT005", "POINT_REALTIME_VIEW", List.of("Q0"))));
        assertThatThrownBy(() -> service.changeSetDetail(OTHER_ENERGY, ENERGY_ROLE, created.changeSetId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("QUALITY_POLICY_NOT_FOUND"));
        service.updateChangeSet(ENERGY, ENERGY_ROLE, created.changeSetId(),
                new ChangeSetUpdateRequest(0, "第一次更新", null,
                        List.of(policy("POINT005", "POINT_REALTIME_VIEW", List.of("Q0")))));
        assertThatThrownBy(() -> service.updateChangeSet(ENERGY, ENERGY_ROLE, created.changeSetId(),
                new ChangeSetUpdateRequest(0, "过期更新", null,
                        List.of(policy("POINT005", "POINT_REALTIME_VIEW", List.of("Q0"))))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("QUALITY_POLICY_VERSION_CONFLICT"));
        assertThatThrownBy(() -> service.createChangeSet(ENERGY, ENERGY_ROLE,
                changeSet("非法集合", policy("POINT006", "POINT_REALTIME_VIEW", List.of("Q1")))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("QUALITY_POLICY_VALIDATION_FAILED"));
    }

    @Test
    void roleAloneCannotSubmitOrReviewAndApprovalCommentIsRequired() {
        ChangeSetView created = service.createChangeSet(ENERGY, ENERGY_ROLE,
                changeSet("职责校验", policy("POINT013", "POINT_REALTIME_VIEW", List.of("Q0"))));
        jdbc.update("DELETE FROM sys_user_backend_duty WHERE user_id=? AND duty_key=?",
                ENERGY, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER.name());
        assertThatThrownBy(() -> service.submit(ENERGY, ENERGY_ROLE, created.changeSetId(),
                "missing-submitter-duty", "仅有业务角色"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("BACKOFFICE_DUTY_REQUIRED"));

        grantDuty(ENERGY, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        var submitted = service.submit(ENERGY, ENERGY_ROLE, created.changeSetId(),
                "duty-submit", "具备提交职责");
        jdbc.update("DELETE FROM sys_user_backend_duty WHERE user_id=? AND duty_key=?",
                ADMIN, BackendDuty.BACKOFFICE_CHANGE_REVIEWER.name());
        assertThatThrownBy(() -> service.approve(ADMIN, ADMIN_ROLE, submitted.requestId(),
                "missing-reviewer-duty", "仅有平台管理员角色"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("BACKOFFICE_DUTY_REQUIRED"));

        grantDuty(ADMIN, BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
        assertThatThrownBy(() -> service.approve(ADMIN, ADMIN_ROLE, submitted.requestId(),
                "blank-review-comment", " "))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("QUALITY_POLICY_VALIDATION_FAILED"));
    }

    private ChangeSetCreateRequest changeSet(String title, PolicyDraftRequest... policies) {
        return new ChangeSetCreateRequest("BLD001", title, null, List.of(policies));
    }

    private PolicyDraftRequest policy(String pointId, String scenarioCode, List<String> levels) {
        return new PolicyDraftRequest(pointId, scenarioCode, levels, null, "测试策略变更");
    }

    private void grantDuty(long userId, BackendDuty duty) {
        LocalDateTime now = LocalDateTime.now().minusMinutes(1);
        jdbc.update("""
                INSERT INTO sys_user_backend_duty
                (assignment_id,user_id,duty_key,status,effective_at,created_by,created_at)
                VALUES (?,?,?,'ACTIVE',?,?,?)
                """, UUID.randomUUID().toString().replace("-", ""), userId, duty.name(),
                Timestamp.valueOf(now), ADMIN, Timestamp.valueOf(now));
    }
}
