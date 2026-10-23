package com.platform.hvac.asset.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.BackendDuty;
import com.platform.audit.TraceContext;
import com.platform.audit.sensitive.SensitiveChangeRecord;
import com.platform.audit.sensitive.SensitiveChangeService;
import com.platform.audit.sensitive.SensitiveChangeStatus;
import com.platform.hvac.service.BuildingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class D01TestBuildingRetirementIntegrationTest {
    private static final long ADMIN = 1L;
    private static final String NAME = "D01专用验收（合成数据，非现场建筑）";
    private static final String HASH = "a573b7f9c11c1e098cfd3163d8c4a8ec1a69430423f58d56d8f389cda0a02070";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private SensitiveChangeService changes;
    @Autowired private BuildingService buildings;
    @Autowired private AssetManagementService assets;
    @Autowired private com.platform.hvac.service.BizEquipmentService equipment;
    @Autowired private com.platform.iot.onboarding.DeviceOnboardingService onboarding;
    @Autowired private com.platform.iot.collection.CollectionPolicyService collection;
    @Autowired private com.platform.hvac.service.BizSpaceService spaces;
    @Autowired private com.platform.hvac.service.BizSystemGroupService groups;
    @Autowired private com.platform.hvac.service.BizDataPointService points;

    private String buildingId;
    private final List<String> dutyIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        buildingId = "D01T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(java.util.Locale.ROOT);
        jdbc.update("""
                INSERT INTO building (building_id,building_name,building_code,building_type,
                total_gfa,climate_zone,region_code,del_flag)
                VALUES (?,?,?,'办公',100,'夏热冬冷','320100',0)
                """, buildingId, NAME, buildingId);
        for (BackendDuty duty : List.of(BackendDuty.BACKOFFICE_CHANGE_SUBMITTER,
                BackendDuty.BACKOFFICE_CHANGE_REVIEWER)) {
            if (jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sys_user_backend_duty
                    WHERE user_id=? AND duty_key=? AND status='ACTIVE'
                    """, Integer.class, ADMIN, duty.name()) == 0) {
                String assignment = UUID.randomUUID().toString().replace("-", "");
                jdbc.update("""
                        INSERT INTO sys_user_backend_duty
                        (assignment_id,user_id,duty_key,status,effective_at,created_by,created_at)
                        VALUES (?,?,?,'ACTIVE',?,?,?)
                        """, assignment, ADMIN, duty.name(), Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)),
                        ADMIN, Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)));
                dutyIds.add(assignment);
            }
        }
        MDC.put(TraceContext.MDC_KEY, "0123456789abcdef0123456789abcdef");
    }

    @AfterEach
    void tearDown() {
        MDC.remove(TraceContext.MDC_KEY);
        jdbc.update("DELETE FROM biz_telemetry_message_intent WHERE building_id=?", buildingId);
        jdbc.update("DELETE FROM biz_telemetry_raw_intent WHERE building_id=?", buildingId);
        jdbc.update("DELETE FROM biz_collection_review_request WHERE building_id=?", buildingId);
        jdbc.update("DELETE FROM biz_data_point WHERE building_id=?", buildingId);
        jdbc.update("DELETE FROM biz_device_identity WHERE building_id=?", buildingId);
        jdbc.update("DELETE FROM biz_equipment WHERE building_id=?", buildingId);
        jdbc.update("DELETE FROM biz_system_group WHERE building_id=?", buildingId);
        jdbc.update("DELETE FROM biz_space WHERE building_id=?", buildingId);
        jdbc.update("DELETE FROM biz_data_source WHERE building_id=?", buildingId);
        jdbc.update("DELETE FROM biz_carbon_project_version WHERE building_id=?", buildingId);
        jdbc.update("DELETE FROM biz_test_building_retirement WHERE building_id=?", buildingId);
        jdbc.update("DELETE FROM sys_security_audit_event WHERE building_id=?", buildingId);
        jdbc.update("DELETE FROM sys_sensitive_change_request WHERE building_id=?", buildingId);
        jdbc.update("DELETE FROM building WHERE building_id=?", buildingId);
        dutyIds.forEach(id -> jdbc.update("DELETE FROM sys_user_backend_duty WHERE assignment_id=?", id));
    }

    @Test
    void approvedExclusionPrecedesArchiveAndPreservesFailedEvidenceAndHistoricalBuilding() {
        insertSource("DISABLED");
        insertIdentity(0);
        jdbc.update("INSERT INTO biz_space(space_id,building_id,space_name) VALUES(?,?,'D01 synthetic space')", buildingId, buildingId);
        jdbc.update("INSERT INTO biz_system_group(system_group_id,building_id,system_group_code) VALUES(?,?,?)", buildingId, buildingId, buildingId);
        jdbc.update("""
                INSERT INTO biz_data_point(point_id,point_code,building_id,equip_id,naming_rule_id,family_code,component_code,status)
                VALUES (?,?,?,?,'RULE_ENV_T','WCR','MAIN','ONLINE')
                """, buildingId, buildingId, buildingId, buildingId);
        // 归档前已经提交的启用申请，也不能在归档后恢复采集。
        jdbc.update("""
                INSERT INTO biz_collection_review_request(request_id,building_id,target_type,target_id,
                target_config_revision,status,submitted_by,submitted_at)
                VALUES (?,?,'SOURCE_ACTIVATION',?,0,'PENDING',1,CURRENT_TIMESTAMP(3))
                """, buildingId, buildingId, buildingId);
        jdbc.update("""
                INSERT INTO biz_telemetry_raw_intent
                (point_key,event_ms,point_id,building_id,payload_hash,snapshot_json,storage_state,retry_count)
                VALUES ('d01-test-key',1,'point-1',?,?,'{}','FAILED',10)
                """, buildingId, "0".repeat(64));
        jdbc.update("""
                INSERT INTO biz_telemetry_message_intent
                (canonical_message_id,building_id,payload_hash,snapshot_json,state,created_ms,retry_count)
                VALUES ('d01-test-message',?,?,'{}','FAILED',1,10)
                """, buildingId, "1".repeat(64));
        long before = buildings.list(1, 100, null, null).getData().getTotal();

        SensitiveChangeRecord draft = draft("EXCLUDE_D01_TEST_BUILDING", HASH);
        assertThatThrownBy(() -> changes.execute(ADMIN, draft.requestId())).isInstanceOf(com.platform.framework.exception.BusinessException.class);
        assertThat(buildings.list(1, 100, null, null).getData().getTotal()).isEqualTo(before);
        changes.submit(ADMIN, draft.requestId());
        changes.approve(ADMIN, draft.requestId(), "保留证据后排除测试建筑");
        assertThat(buildings.list(1, 100, null, null).getData().getTotal()).isEqualTo(before);
        assertThat(changes.execute(ADMIN, draft.requestId()).change().status()).isEqualTo(SensitiveChangeStatus.EXECUTED);
        assertThat(buildings.list(1, 100, null, null).getData().getTotal()).isEqualTo(before - 1);
        assertThat(assets.buildingDetail(buildingId, List.of("PLATFORM_ADMIN")).status()).isEqualTo("EXCLUDED");

        SensitiveChangeRecord archive = draft("ARCHIVE_D01_TEST_BUILDING", HASH);
        changes.submit(ADMIN, archive.requestId());
        changes.approve(ADMIN, archive.requestId(), "同一证据快照完成保留引用归档");
        changes.execute(ADMIN, archive.requestId());

        assertThat(assets.buildingDetail(buildingId, List.of("PLATFORM_ADMIN")).status()).isEqualTo("ARCHIVED");
        assertThat(buildings.getById(buildingId)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT del_flag FROM building WHERE building_id=?", Integer.class, buildingId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_device_identity WHERE building_id=?",
                Integer.class, buildingId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM biz_data_source WHERE building_id=?",
                String.class, buildingId)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("""
                SELECT storage_state FROM biz_telemetry_raw_intent WHERE building_id=?
                """, String.class, buildingId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                SELECT state FROM biz_telemetry_message_intent WHERE building_id=?
                """, String.class, buildingId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_security_audit_event WHERE building_id=? AND result='SUCCESS'
                """, Integer.class, buildingId)).isGreaterThanOrEqualTo(2);
        assertThatThrownBy(() -> buildings.delete(buildingId)).hasMessageContaining("不可再修改");
        assertThatThrownBy(() -> equipment.delete(buildingId)).hasMessageContaining("不可再修改");
        assertThatThrownBy(() -> spaces.delete(buildingId)).hasMessageContaining("不可再修改");
        assertThatThrownBy(() -> groups.delete(buildingId)).hasMessageContaining("不可再修改");
        assertThatThrownBy(() -> points.delete(buildingId)).hasMessageContaining("不可再修改");
        assertThatThrownBy(() -> onboarding.activate(buildingId, ADMIN, java.util.Set.of("PLATFORM_ADMIN")))
                .hasMessageContaining("不可再修改");
        assertThatThrownBy(() -> collection.approveReview(ADMIN, List.of("PLATFORM_ADMIN"), buildingId, "旧申请不能恢复接入"))
                .hasMessageContaining("不可再修改");
        assertThat(points.getById(buildingId).getStatus()).isEqualTo("ONLINE");
        assertThat(equipment.list(1, 100, null, null, null, null).getData().getRecords())
                .noneMatch(item -> buildingId.equals(item.getBuildingId()));
        assertThat(equipment.list(1, 100, buildingId, null, null, null).getData().getRecords())
                .singleElement().extracting(com.platform.hvac.model.entity.BizEquipment::getEquipId).isEqualTo(buildingId);
    }

    @Test
    void activeSourceAndMismatchedSnapshotBlockExecutionWithoutPartialRetirement() {
        insertSource("ENABLED");
        SensitiveChangeRecord exclusion = draft("EXCLUDE_D01_TEST_BUILDING", HASH);
        changes.submit(ADMIN, exclusion.requestId());
        changes.approve(ADMIN, exclusion.requestId(), "验证活跃来源阻断");
        assertThatThrownBy(() -> changes.execute(ADMIN, exclusion.requestId())).hasMessageContaining("敏感操作执行失败");
        assertThat(changes.detail(ADMIN, exclusion.requestId()).status()).isEqualTo(SensitiveChangeStatus.EXECUTION_FAILED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_test_building_retirement WHERE building_id=?",
                Integer.class, buildingId)).isZero();

        jdbc.update("UPDATE biz_data_source SET status='DISABLED' WHERE source_id=?", buildingId);
        insertIdentity(1);
        SensitiveChangeRecord activeIdentity = draft("EXCLUDE_D01_TEST_BUILDING", HASH);
        changes.submit(ADMIN, activeIdentity.requestId());
        changes.approve(ADMIN, activeIdentity.requestId(), "验证活跃身份阻断");
        assertThatThrownBy(() -> changes.execute(ADMIN, activeIdentity.requestId())).hasMessageContaining("敏感操作执行失败");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_test_building_retirement WHERE building_id=?",
                Integer.class, buildingId)).isZero();
        jdbc.update("UPDATE biz_device_identity SET status=0 WHERE building_id=?", buildingId);
        approveAndExecute("EXCLUDE_D01_TEST_BUILDING", HASH);
        SensitiveChangeRecord wrongArchive = draft("ARCHIVE_D01_TEST_BUILDING", "f".repeat(64));
        changes.submit(ADMIN, wrongArchive.requestId());
        changes.approve(ADMIN, wrongArchive.requestId(), "验证摘要不一致阻断");
        assertThatThrownBy(() -> changes.execute(ADMIN, wrongArchive.requestId())).hasMessageContaining("敏感操作执行失败");
        assertThat(assets.buildingDetail(buildingId, List.of("PLATFORM_ADMIN")).status()).isEqualTo("EXCLUDED");
    }

    @Test
    void archiveRequiresPriorExclusionAndFormalProjectPreventsExclusion() {
        SensitiveChangeRecord archive = draft("ARCHIVE_D01_TEST_BUILDING", HASH);
        changes.submit(ADMIN, archive.requestId());
        changes.approve(ADMIN, archive.requestId(), "验证禁止跳过统计排除");
        assertThatThrownBy(() -> changes.execute(ADMIN, archive.requestId())).hasMessageContaining("敏感操作执行失败");
        jdbc.update("""
                INSERT INTO biz_carbon_project_version
                (version_id,building_id,version_no,status,configuration_json,reason,created_by,created_at)
                VALUES (?,?,1,'DRAFT','{}','已有正式项目引用',1,1)
                """, buildingId, buildingId);
        SensitiveChangeRecord exclusion = draft("EXCLUDE_D01_TEST_BUILDING", HASH);
        changes.submit(ADMIN, exclusion.requestId());
        changes.approve(ADMIN, exclusion.requestId(), "验证正式项目引用保护");
        assertThatThrownBy(() -> changes.execute(ADMIN, exclusion.requestId())).hasMessageContaining("敏感操作执行失败");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_test_building_retirement WHERE building_id=?",
                Integer.class, buildingId)).isZero();
    }

    @Test
    void actualBuildingCannotBeRetiredEvenWithApprovedRequest() {
        jdbc.update("UPDATE building SET building_name='实际建筑' WHERE building_id=?", buildingId);
        var command = json.createObjectNode().put("buildingId", buildingId)
                .put("expectedBuildingName", "实际建筑").put("evidenceSha256", HASH);
        SensitiveChangeRecord change = changes.createDraft(ADMIN, "EXCLUDE_D01_TEST_BUILDING", command, UUID.randomUUID().toString());
        changes.submit(ADMIN, change.requestId());
        changes.approve(ADMIN, change.requestId(), "验证非合成建筑保护");
        assertThatThrownBy(() -> changes.execute(ADMIN, change.requestId())).hasMessageContaining("敏感操作执行失败");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_test_building_retirement WHERE building_id=?",
                Integer.class, buildingId)).isZero();
    }

    private SensitiveChangeRecord draft(String operation, String hash) {
        var command = json.createObjectNode().put("buildingId", buildingId)
                .put("expectedBuildingName", NAME).put("evidenceSha256", hash);
        return changes.createDraft(ADMIN, operation, command, UUID.randomUUID().toString());
    }

    private void insertSource(String status) {
        jdbc.update("""
                INSERT INTO biz_data_source (source_id,source_code,source_name,building_id,
                source_category,transport_type,status)
                VALUES (?,?,?,?,'DEVICE_ACCESS','MQTT',?)
                """, buildingId, buildingId, "D01 synthetic source", buildingId, status);
    }

    private void insertIdentity(int status) {
        jdbc.update("""
                INSERT INTO biz_equipment (equip_id,equip_code,type_code,building_id)
                VALUES (?,?,'WCR',?)
                """, buildingId, buildingId, buildingId);
        jdbc.update("""
                INSERT INTO biz_device_identity (identity_id,identity_type,identity_value,equip_id,
                building_id,expected_profile_code,status)
                VALUES (?,'MAC',?,?,?,'HVAC_DEVICE_V1',?)
                """, buildingId, buildingId, buildingId, buildingId, status);
    }

    private void approveAndExecute(String operation, String hash) {
        SensitiveChangeRecord change = draft(operation, hash);
        changes.submit(ADMIN, change.requestId());
        changes.approve(ADMIN, change.requestId(), "D01 测试建筑归档验证");
        changes.execute(ADMIN, change.requestId());
    }
}
