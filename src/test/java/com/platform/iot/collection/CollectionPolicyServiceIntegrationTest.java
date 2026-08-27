package com.platform.iot.collection;

import com.platform.audit.BackendDuty;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.collection.api.CollectionPolicyContracts.AliasCreateRequest;
import com.platform.iot.collection.api.CollectionPolicyContracts.AliasView;
import com.platform.iot.collection.api.CollectionPolicyContracts.CopyVersionRequest;
import com.platform.iot.collection.api.CollectionPolicyContracts.DataSourceView;
import com.platform.iot.collection.api.CollectionPolicyContracts.InitialPolicyRequest;
import com.platform.iot.collection.api.CollectionPolicyContracts.PolicyVersionCreateRequest;
import com.platform.iot.collection.api.CollectionPolicyContracts.PolicyVersionView;
import com.platform.iot.collection.api.CollectionPolicyContracts.SourceCreateRequest;
import com.platform.iot.collection.api.CollectionPolicyContracts.SourceUpdateRequest;
import com.platform.iot.collection.runtime.CollectionRuntimeStateService;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointAliasKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CollectionPolicyServiceIntegrationTest {
    private static final long ENERGY_USER = 101L;
    private static final long ADMIN_USER = 1L;
    private static final Set<String> ENERGY = Set.of("ENERGY_MANAGER");
    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");

    @Autowired private CollectionPolicyService service;
    @Autowired private CollectionRuntimeStateService runtimeStateService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataPointConfigProvider pointConfigProvider;

    @BeforeEach
    void grantBuildingOne() {
        jdbcTemplate.update("INSERT INTO sys_user_building(user_id,building_id) VALUES (?,?)",
                ENERGY_USER, "BLD001");
        grantDuty(ENERGY_USER, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        grantDuty(ADMIN_USER, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        grantDuty(ADMIN_USER, BackendDuty.BACKOFFICE_CHANGE_REVIEWER);
    }

    @Test
    void initialMigrationExposesNineteenActivePoliciesWithoutHardCodingRuntimeValues() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_point_alias WHERE source_id='SOURCE_MQTT_FREEZE_V1' AND status=1",
                Integer.class)).isEqualTo(19);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_collection_policy_version "
                        + "WHERE status='ACTIVE' AND expected_interval_seconds=60 "
                        + "AND allowed_delay_seconds=30 AND raw_retention_days=90 "
                        + "AND minute_retention_mode='LONG_TERM'", Integer.class)).isEqualTo(19);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_collection_config_audit_log "
                        + "WHERE actor_type='SYSTEM_MIGRATION' AND operator_id IS NULL", Integer.class))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void draftIsIsolatedAndApprovedSourcePublishesAtomicallyWithConfigurablePolicy() {
        DataSourceView source = service.createSource(ENERGY_USER, ENERGY,
                sourceRequest("MQTT_BLD001_TEST_A"));
        AliasView alias = service.createAlias(ENERGY_USER, ENERGY, source.sourceId(),
                aliasRequest(15, 300));

        runtimeStateService.refreshAll();
        assertThat(runtimeStateService.snapshot().aliases()).doesNotContainKey(alias.aliasId());

        var submitted = service.submitSource(ENERGY_USER, ENERGY, source.sourceId(), "提交完整配置包");
        var approved = service.approveReview(ADMIN_USER, ADMIN, submitted.requestId(), "批准首启");
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(service.sourceDetail(ADMIN_USER, ADMIN, source.sourceId()).status()).isEqualTo("ENABLED");

        List<PolicyVersionView> versions = service.listVersions(
                ADMIN_USER, ADMIN, alias.policyId());
        assertThat(versions).singleElement().satisfies(version -> {
            assertThat(version.status()).isEqualTo("ACTIVE");
            assertThat(version.expectedIntervalSeconds()).isEqualTo(15);
            assertThat(version.allowedDelaySeconds()).isEqualTo(300);
        });
        runtimeStateService.refreshAll();
        assertThat(runtimeStateService.snapshot().aliases()).containsKey(alias.aliasId());
    }

    @Test
    void revisionConflictAndCrossBuildingAccessAreRejectedWithStableCodes() {
        DataSourceView source = service.createSource(ENERGY_USER, ENERGY,
                sourceRequest("MQTT_BLD001_TEST_B"));
        service.updateSource(ENERGY_USER, ENERGY, source.sourceId(),
                new SourceUpdateRequest("新名称", null, 0, "修订展示名称"));

        assertThatThrownBy(() -> service.updateSource(ENERGY_USER, ENERGY, source.sourceId(),
                new SourceUpdateRequest("过期更新", null, 0, "并发覆盖")))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode()).isEqualTo(CollectionErrors.VERSION_CONFLICT);
                });
        assertThatThrownBy(() -> service.createSource(ENERGY_USER, ENERGY,
                new SourceCreateRequest("MQTT_BLD002_FORBIDDEN", "二号楼来源", "BLD002",
                        "MQTT", null, "越权尝试")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(403));
    }

    @Test
    void upgradeAndRollbackCreateHigherVersionsAndNeverReactivateHistory() {
        DataSourceView source = service.createSource(ADMIN_USER, ADMIN,
                sourceRequest("MQTT_BLD001_TEST_C"));
        AliasView alias = service.createAlias(ADMIN_USER, ADMIN, source.sourceId(),
                aliasRequest(15, 30));
        approve(service.submitSource(ADMIN_USER, ADMIN, source.sourceId(), "管理员提交首启").requestId(),
                "研发自审首启");
        PolicyVersionView v1 = service.listVersions(ADMIN_USER, ADMIN, alias.policyId()).getFirst();

        PolicyVersionView draftV2 = service.createVersion(ADMIN_USER, ADMIN, alias.policyId(),
                new PolicyVersionCreateRequest(policy(300, 10), "调整采集预期"));
        approve(service.submitVersion(ADMIN_USER, ADMIN, alias.policyId(), draftV2.versionId(),
                "提交第二版").requestId(), "研发自审第二版");
        PolicyVersionView v2 = service.listVersions(ADMIN_USER, ADMIN, alias.policyId()).getFirst();
        assertThat(v2.versionNo()).isEqualTo(2);

        PolicyVersionView rollbackDraft = service.copyVersion(ADMIN_USER, ADMIN, alias.policyId(),
                new CopyVersionRequest(v1.versionId(), "回滚到首版参数"));
        approve(service.submitVersion(ADMIN_USER, ADMIN, alias.policyId(), rollbackDraft.versionId(),
                "提交回滚版本").requestId(), "研发自审回滚版本");
        PolicyVersionView v3 = service.listVersions(ADMIN_USER, ADMIN, alias.policyId()).getFirst();
        assertThat(v3.versionNo()).isEqualTo(3);
        assertThat(v3.copiedFromVersionId()).isEqualTo(v1.versionId());
        assertThat(service.listVersions(ADMIN_USER, ADMIN, alias.policyId()))
                .filteredOn(version -> version.versionId().equals(v1.versionId()))
                .singleElement().extracting(PolicyVersionView::status).isEqualTo("RETIRED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM biz_collection_config_audit_log
                WHERE action_type='SELF_APPROVAL_DEV_MODE' AND operator_id=?
                """, Integer.class, ADMIN_USER)).isEqualTo(3);
    }

    @Test
    void disableAndReenableSeparateDatabaseStateFromRuntimeApplication() {
        DataSourceView source = service.createSource(ADMIN_USER, ADMIN,
                sourceRequest("MQTT_BLD001_TEST_D"));
        AliasView alias = service.createAlias(ADMIN_USER, ADMIN, source.sourceId(),
                aliasRequest(45, 5));
        approve(service.submitSource(ADMIN_USER, ADMIN, source.sourceId(), "提交首启").requestId(),
                "研发自审首启");
        runtimeStateService.refreshAll();
        assertThat(runtimeStateService.snapshot().aliases()).containsKey(alias.aliasId());

        PolicyVersionView disableDraft = service.createPolicyDisableDraft(
                ADMIN_USER, ADMIN, alias.policyId(), "暂不期待周期上报");
        assertThat(disableDraft.status()).isEqualTo("DRAFT");
        assertThat(service.listVersions(ADMIN_USER, ADMIN, alias.policyId()).getFirst().status())
                .isEqualTo("DRAFT");
        approve(service.submitVersion(ADMIN_USER, ADMIN, alias.policyId(), disableDraft.versionId(),
                "提交停用版本").requestId(), "研发自审停用版本");
        runtimeStateService.refreshAll();
        assertThat(pointConfigProvider.find(new PointAliasKey(
                "BLD001", source.sourceCode(), alias.sourcePointCode()))).isPresent();

        var disableSourceReview = service.submitSourceDisable(
                ADMIN_USER, ADMIN, source.sourceId(), "计划维护");
        assertThat(service.sourceDetail(ADMIN_USER, ADMIN, source.sourceId()).status()).isEqualTo("ENABLED");
        approve(disableSourceReview.requestId(), "研发自审停用来源");
        DataSourceView disabled = service.sourceDetail(ADMIN_USER, ADMIN, source.sourceId());
        assertThat(disabled.status()).isEqualTo("DISABLED");
        runtimeStateService.refreshAll();
        assertThat(runtimeStateService.snapshot().aliases()).doesNotContainKey(alias.aliasId());
        assertThat(service.listVersions(ADMIN_USER, ADMIN, alias.policyId()))
                .anyMatch(version -> version.status().equals("ACTIVE"));

        approve(service.submitSourceEnable(ADMIN_USER, ADMIN, source.sourceId(), "维护结束").requestId(),
                "研发自审重新启用来源");
        runtimeStateService.refreshAll();
        assertThat(runtimeStateService.snapshot().aliases()).containsKey(alias.aliasId());

        approve(service.submitAliasDisable(ADMIN_USER, ADMIN, source.sourceId(), alias.aliasId(),
                "停用单个别名").requestId(), "研发自审停用别名");
        runtimeStateService.refreshAll();
        assertThat(runtimeStateService.snapshot().aliases()).doesNotContainKey(alias.aliasId());
        approve(service.submitAliasEnable(ADMIN_USER, ADMIN, source.sourceId(), alias.aliasId(),
                "恢复单个别名").requestId(), "研发自审恢复别名");
        runtimeStateService.refreshAll();
        assertThat(runtimeStateService.snapshot().aliases()).containsKey(alias.aliasId());
    }

    @Test
    void rejectedAndWithdrawnReviewsUnfreezeDraftWithoutChangingRuntimeRevision() {
        DataSourceView source = service.createSource(ENERGY_USER, ENERGY,
                sourceRequest("MQTT_BLD001_TEST_E"));
        service.createAlias(ENERGY_USER, ENERGY, source.sourceId(), aliasRequest(20, 0));
        var first = service.submitSource(ENERGY_USER, ENERGY, source.sourceId(), "第一次提交");
        var rejected = service.rejectReview(ADMIN_USER, ADMIN, first.requestId(), "配置依据不足");
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(service.sourceDetail(ENERGY_USER, ENERGY, source.sourceId()).runtimeRevision()).isZero();

        var second = service.submitSource(ENERGY_USER, ENERGY, source.sourceId(), "补充说明后重提");
        var withdrawn = service.withdrawReview(ENERGY_USER, ENERGY, second.requestId());
        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
        assertThat(service.sourceDetail(ENERGY_USER, ENERGY, source.sourceId()).status()).isEqualTo("DRAFT");
    }

    @Test
    void directDraftPublicationAndMissingDutiesAreRejected() {
        DataSourceView source = service.createSource(ADMIN_USER, ADMIN,
                sourceRequest("MQTT_BLD001_REVIEW_ONLY"));
        AliasView alias = service.createAlias(ADMIN_USER, ADMIN, source.sourceId(),
                aliasRequest(70, 9));

        assertThatThrownBy(() -> service.submitSourceEnable(
                ADMIN_USER, ADMIN, source.sourceId(), "绕过审核首启"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo("BACKOFFICE_REVIEW_REQUIRED"));
        assertThatThrownBy(() -> service.publishVersion(
                ADMIN_USER, ADMIN, alias.policyId(), alias.draftVersionId(), "绕过审核发布"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo("BACKOFFICE_REVIEW_REQUIRED"));

        jdbcTemplate.update("DELETE FROM sys_user_backend_duty WHERE user_id=? AND duty_key=?",
                ADMIN_USER, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER.name());
        assertThatThrownBy(() -> service.submitSource(
                ADMIN_USER, ADMIN, source.sourceId(), "无职责提交"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo("BACKOFFICE_DUTY_REQUIRED"));

        grantDuty(ADMIN_USER, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER);
        var submitted = service.submitSource(ADMIN_USER, ADMIN, source.sourceId(), "提交待审");
        assertThat(submitted.allowedActions()).containsExactly("APPROVE", "REJECT", "WITHDRAW");
        jdbcTemplate.update("DELETE FROM sys_user_backend_duty WHERE user_id=? AND duty_key=?",
                ADMIN_USER, BackendDuty.BACKOFFICE_CHANGE_REVIEWER.name());
        assertThatThrownBy(() -> service.approveReview(
                ADMIN_USER, ADMIN, submitted.requestId(), "无职责审核"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo("BACKOFFICE_DUTY_REQUIRED"));
    }

    @Test
    void roleAndDraftVisibilityDoNotLeakOtherManagersContent() {
        long otherManager = 102L;
        jdbcTemplate.update("INSERT INTO sys_user_building(user_id,building_id) VALUES (?,?)",
                otherManager, "BLD001");
        DataSourceView draft = service.createSource(ENERGY_USER, ENERGY,
                sourceRequest("MQTT_BLD001_PRIVATE"));

        assertThat(service.listSources(ENERGY_USER, Set.of("BUILDING_OWNER"), "BLD001", 1, 200)
                .items()).noneMatch(item -> item.sourceId().equals(draft.sourceId()));
        assertThatThrownBy(() -> service.sourceDetail(otherManager, ENERGY, draft.sourceId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(404));
        assertThatThrownBy(() -> service.listSources(ENERGY_USER, Set.of("THIRD_PARTY"),
                "BLD001", 1, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(403));
    }

    private SourceCreateRequest sourceRequest(String code) {
        return new SourceCreateRequest(code, code + "名称", "BLD001", "MQTT", null, "测试创建");
    }

    private AliasCreateRequest aliasRequest(int interval, int delay) {
        return new AliasCreateRequest("NEW_" + interval + "_" + delay, "POINT001",
                policy(interval, delay), "配置测试测点");
    }

    private InitialPolicyRequest policy(int interval, int delay) {
        return new InitialPolicyRequest(interval, delay, "FIXED_DAYS", 45,
                "LONG_TERM", null, true);
    }

    private void approve(String requestId, String comment) {
        service.approveReview(ADMIN_USER, ADMIN, requestId, comment);
    }

    private void grantDuty(long userId, BackendDuty duty) {
        LocalDateTime now = LocalDateTime.now().minusMinutes(1);
        jdbcTemplate.update("""
                INSERT INTO sys_user_backend_duty
                (assignment_id,user_id,duty_key,status,effective_at,created_by,created_at)
                VALUES (?,?,?,'ACTIVE',?,?,?)
                """, UUID.randomUUID().toString().replace("-", ""), userId, duty.name(),
                Timestamp.valueOf(now), ADMIN_USER, Timestamp.valueOf(now));
    }
}
