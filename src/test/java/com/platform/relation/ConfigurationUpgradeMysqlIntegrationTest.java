package com.platform.relation;

import com.platform.audit.AuditGovernanceProperties;
import com.platform.carbon.CarbonWorkOrderRepository;
import com.platform.relation.RelationStagingContracts.EquipmentDefinition;
import com.platform.relation.RelationStagingContracts.PointDefinition;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 仅在显式确认的一次性空 MySQL 实例验证迁移与关系暂存持久化，不覆盖整应用或现场链路。 */
@EnabledIfEnvironmentVariable(named = "CONFIGURATION_MYSQL_IT_URL", matches = ".+")
class ConfigurationUpgradeMysqlIntegrationTest {
    private static final String BUILDING = "CFGIT-BLD-001";
    private static final String SPACE = "CFGIT-SPACE-001";
    private static final String SYSTEM = "CFGIT-SYSTEM-001";
    private static final String MODEL = "CFGIT-MODEL-001";
    private static final String VERSION = "CFGIT-VERSION-001";
    private static final String ROLLBACK_VERSION = "CFGIT-VERSION-002";
    private static final String EQUIPMENT = "CFGIT-EQUIPMENT-001";
    private static final String POINT = "CFGIT-POINT-001";
    private static final String ROLLBACK_EQUIPMENT = "CFGIT-EQUIPMENT-002";
    private static final String ROLLBACK_POINT = "CFGIT-POINT-002";

    @Test
    void upgradesV77ToV83AndKeepsStagedAssetsAndConfigurationCommandsTransactional() {
        assertThat(System.getenv("CONFIGURATION_MYSQL_IT_ISOLATED")).isEqualTo("true");
        String url = System.getenv("CONFIGURATION_MYSQL_IT_URL");
        assertThat(url).contains("/iot_platform");
        var dataSource = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("CONFIGURATION_MYSQL_IT_USER", "root"),
                System.getenv().getOrDefault("CONFIGURATION_MYSQL_IT_PASSWORD", ""));
        var jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("iot_platform");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema IN ('iot_platform','iot_platform_archive')
                """, Integer.class)).as("专项入口只能使用项目库和归档库均为空的一次性实例").isZero();

        Flyway.configure().dataSource(dataSource).locations("filesystem:src/env/init").target("77").load().migrate();
        int originalEquipment = count(jdbc, "SELECT COUNT(*) FROM biz_equipment");
        int originalPoints = count(jdbc, "SELECT COUNT(*) FROM biz_data_point");
        int originalFactors = count(jdbc, "SELECT COUNT(*) FROM biz_carbon_factor");
        seedExistingSummaryPolicy(jdbc);
        // 模拟已有部署库的默认排序规则与V24父表不同；新增外键表不能继承库默认值。
        jdbc.execute("ALTER DATABASE iot_platform CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        assertThat(jdbc.queryForObject("""
                SELECT default_collation_name FROM information_schema.schemata WHERE schema_name=DATABASE()
                """, String.class)).isEqualTo("utf8mb4_unicode_ci");
        var upgrade = Flyway.configure().dataSource(dataSource).locations("filesystem:src/env/init").target("83").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(6);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT table_collation FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name='biz_relation_staged_asset'
                """, String.class)).isEqualTo("utf8mb4_0900_ai_ci");
        assertThat(jdbc.queryForObject("""
                SELECT COLLATION_NAME FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='biz_carbon_configuration_command'
                 AND COLUMN_NAME='project_version_id'
                """, String.class)).isEqualTo(jdbc.queryForObject("""
                SELECT COLLATION_NAME FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='biz_carbon_project_version' AND COLUMN_NAME='version_id'
                """, String.class));
        verifyFormalSummaryPolicyConstraint(jdbc);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM biz_equipment")).isEqualTo(originalEquipment);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM biz_data_point")).isEqualTo(originalPoints);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM biz_carbon_factor")).isEqualTo(originalFactors);
        assertThat(count(jdbc,"SELECT COUNT(*) FROM sys_menu WHERE path='/operations/energy/energyItems' AND menu_type='C' AND status=1")).isEqualTo(1);
        assertThat(count(jdbc,"SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.id=rm.role_id JOIN sys_menu m ON m.id=rm.menu_id WHERE m.path='/operations/energy/energyItems' AND r.role_key IN ('PLATFORM_ADMIN','ENERGY_MANAGER','BUILDING_OWNER')")).isEqualTo(3);
        assertThat(count(jdbc,"SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.id=rm.role_id JOIN sys_menu m ON m.id=rm.menu_id WHERE m.path='/operations/energy/energyItems' AND r.role_key='THIRD_PARTY'")).isZero();

        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var staging = new RelationStagingRepository(jdbc);
        seedRelationScope(jdbc);
        Definitions definitions = definitions(jdbc, staging);
        stage(staging, VERSION, EQUIPMENT, POINT, "001", definitions);

        assertThat(count(jdbc, "SELECT COUNT(*) FROM biz_equipment WHERE equip_id=?", EQUIPMENT)).isZero();
        assertThat(count(jdbc, "SELECT COUNT(*) FROM biz_data_point WHERE point_id=?", POINT)).isZero();
        assertThat(count(jdbc, "SELECT COUNT(*) FROM biz_device_identity WHERE equip_id=?", EQUIPMENT)).isZero();
        assertThat(count(jdbc, "SELECT COUNT(*) FROM biz_point_alias WHERE point_id=?", POINT)).isZero();
        assertThat(count(jdbc, "SELECT COUNT(*) FROM biz_relation_node WHERE node_id IN (?,?) AND status='STAGED'",
                node("EQUIPMENT", EQUIPMENT), node("POINT", POINT))).isEqualTo(2);

        staging.publish(VERSION);
        assertThat(jdbc.queryForObject("SELECT building_id FROM biz_equipment WHERE equip_id=?", String.class, EQUIPMENT))
                .isEqualTo(BUILDING);
        assertThat(jdbc.queryForObject("SELECT space_id FROM biz_equipment WHERE equip_id=?", String.class, EQUIPMENT))
                .isEqualTo(SPACE);
        assertThat(jdbc.queryForObject("SELECT system_group_id FROM biz_equipment WHERE equip_id=?", String.class, EQUIPMENT))
                .isEqualTo(SYSTEM);
        assertThat(jdbc.queryForObject("SELECT equip_id FROM biz_data_point WHERE point_id=?", String.class, POINT))
                .isEqualTo(EQUIPMENT);
        assertThat(jdbc.queryForObject("SELECT system_group_id FROM biz_data_point WHERE point_id=?", String.class, POINT))
                .isEqualTo(SYSTEM);
        assertThat(count(jdbc, """
                SELECT COUNT(*) FROM biz_asset_assignment_version_item
                WHERE version_id=? AND object_type='POINT' AND object_id=? AND equipment_id=?
                  AND staged_equipment_id IS NULL
                """, VERSION, POINT, EQUIPMENT)).isEqualTo(1);
        assertThat(staging.list(VERSION)).extracting(RelationStagingRepository.Row::status)
                .containsOnly("PUBLISHED");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM biz_relation_node WHERE node_id IN (?,?) AND status='ACTIVE'",
                node("EQUIPMENT", EQUIPMENT), node("POINT", POINT))).isEqualTo(2);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM biz_device_identity WHERE equip_id=?", EQUIPMENT)).isZero();
        assertThat(count(jdbc, "SELECT COUNT(*) FROM biz_point_alias WHERE point_id=?", POINT)).isZero();

        var governance = new RelationGovernanceRepository(jdbc, new AuditGovernanceProperties(), staging);
        assertThat(governance.bumpDraft(VERSION, 0)).isEqualTo(1);
        assertThat(governance.bumpDraft(VERSION, 0)).isZero();

        insertDraftVersion(jdbc, ROLLBACK_VERSION, 2);
        stage(staging, ROLLBACK_VERSION, ROLLBACK_EQUIPMENT, ROLLBACK_POINT, "002", definitions);
        assertThat(staging.exists(VERSION, BUILDING, "EQUIPMENT", ROLLBACK_EQUIPMENT)).isFalse();
        assertThat(staging.exists(ROLLBACK_VERSION, BUILDING, "EQUIPMENT", ROLLBACK_EQUIPMENT)).isTrue();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                governance.updateAssetAssignment(VERSION, "POINT", POINT, SPACE, SYSTEM, ROLLBACK_EQUIPMENT, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT config_revision FROM biz_relation_version WHERE version_id=?", Long.class,
                VERSION)).isEqualTo(1L);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            staging.publish(ROLLBACK_VERSION);
            throw new IllegalStateException("injected-after-publish");
        })).hasMessageContaining("injected-after-publish");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM biz_equipment WHERE equip_id=?", ROLLBACK_EQUIPMENT)).isZero();
        assertThat(count(jdbc, "SELECT COUNT(*) FROM biz_data_point WHERE point_id=?", ROLLBACK_POINT)).isZero();
        assertThat(count(jdbc, "SELECT COUNT(*) FROM biz_asset_assignment_version_item WHERE version_id=?", ROLLBACK_VERSION))
                .isZero();
        assertThat(staging.list(ROLLBACK_VERSION)).extracting(RelationStagingRepository.Row::status)
                .containsOnly("STAGED");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM biz_relation_node WHERE node_id IN (?,?) AND status='STAGED'",
                node("EQUIPMENT", ROLLBACK_EQUIPMENT), node("POINT", ROLLBACK_POINT))).isEqualTo(2);

        verifyWorkOrderUniquenessAndRollback(jdbc, transactions);
        verifyNativeCompleteStatusConstraint(jdbc);
    }

    private static void seedExistingSummaryPolicy(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO biz_energy_boundary_summary_policy
                (policy_id,building_id,metering_boundary_id,energy_item_code,created_by)
                VALUES ('CFGIT-SUMMARY',?,'CFGIT-BOUNDARY','GRID_ELECTRICITY',1)
                """, BUILDING);
        jdbc.update("""
                INSERT INTO biz_energy_boundary_summary_policy_version
                (version_id,policy_id,version_no,aggregation_mode,status,source_type,evidence_reference,
                 effective_from,config_revision,created_by,approved_by,approved_at)
                VALUES ('CFGIT-SUMMARY-OLD','CFGIT-SUMMARY',1,'MAIN_METER_TOTAL','APPROVED',
                        'SIMULATION','合成旧研发口径','2024-01-01',7,1,2,'2024-01-01')
                """);
    }

    private static void verifyFormalSummaryPolicyConstraint(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForMap("""
                SELECT status,source_type,config_revision FROM biz_energy_boundary_summary_policy_version
                WHERE version_id='CFGIT-SUMMARY-OLD'
                """)).containsEntry("status", "APPROVED").containsEntry("source_type", "SIMULATION")
                .containsEntry("config_revision", 7);
        jdbc.update("""
                INSERT INTO biz_energy_boundary_summary_policy_version
                (version_id,policy_id,version_no,aggregation_mode,status,source_type,evidence_reference,
                 effective_from,config_revision,created_by)
                VALUES ('CFGIT-SUMMARY-FORMAL','CFGIT-SUMMARY',2,'MAIN_METER_TOTAL','PENDING_EXPERT',
                        'FORMAL','合成正式候选，待独立审核','2025-01-01',0,1)
                """);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE biz_energy_boundary_summary_policy_version SET source_type='UNKNOWN'
                WHERE version_id='CFGIT-SUMMARY-FORMAL'
                """)).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("chk_energy_boundary_summary_source");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE biz_energy_boundary_summary_policy_version SET status='APPROVED'
                WHERE version_id='CFGIT-SUMMARY-FORMAL'
                """)).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("chk_energy_boundary_summary_approval");
    }

    private static void seedRelationScope(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO building(building_id,building_name,building_type,total_gfa,climate_zone,region_code)
                VALUES (?,?,?,?,?,?)
                """, BUILDING, "配置升级隔离建筑", "办公", 1000, "温和", "330100");
        jdbc.update("""
                INSERT INTO biz_space(space_id,building_id,space_name,space_code,space_type)
                VALUES (?,?,?,?,?)
                """, SPACE, BUILDING, "隔离机房", "CFGIT-SPACE", "ROOM");
        jdbc.update("""
                INSERT INTO biz_system_group(system_group_id,system_group_code,building_id,system_type,system_group_name)
                VALUES (?,?,?,?,?)
                """, SYSTEM, "CFGIT-SYSTEM", BUILDING, "HVAC", "隔离系统");
        jdbc.update("""
                INSERT INTO biz_relation_model(model_id,scope_type,scope_id,building_id,governance_mode,
                                               config_revision,create_by,update_by)
                VALUES (?,'BUILDING',?,?,'GOVERNED',0,?,?)
                """, MODEL, BUILDING, BUILDING, 1L, 1L);
        insertDraftVersion(jdbc, VERSION, 1);
        jdbc.update("UPDATE biz_relation_model SET draft_version_id=? WHERE model_id=?", VERSION, MODEL);
    }

    private static void insertDraftVersion(JdbcTemplate jdbc, String versionId, int versionNo) {
        jdbc.update("""
                INSERT INTO biz_relation_version(version_id,model_id,building_id,version_no,status,
                                                 config_revision,change_reason,created_by)
                VALUES (?,?,?,?,'DRAFT',0,'configuration-upgrade-it',?)
                """, versionId, MODEL, BUILDING, versionNo, 1L);
    }

    private static Definitions definitions(JdbcTemplate jdbc, RelationStagingRepository staging) {
        String typeCode = jdbc.queryForObject("SELECT type_code FROM biz_equipment_type WHERE status=1 ORDER BY type_code LIMIT 1",
                String.class);
        Map<String, Object> type = staging.type(typeCode);
        String ruleId = jdbc.queryForObject("SELECT rule_id FROM biz_point_naming_rule WHERE status=1 ORDER BY rule_id LIMIT 1",
                String.class);
        Map<String, Object> rule = staging.rule(ruleId);
        String equipment = RelationStagingRepository.encode(new EquipmentDefinition(typeCode,
                RelationStagingRepository.string(type, "equip_category"), null, "configuration-it",
                RelationStagingRepository.fingerprint(type), null));
        String point = RelationStagingRepository.encode(new PointDefinition(ruleId,
                RelationStagingRepository.string(rule, "family_code"),
                RelationStagingRepository.string(rule, "component_code"), "IT", "ANALOG", "kWh", 1,
                "ONLINE", RelationStagingRepository.fingerprint(rule)));
        return new Definitions(equipment, point);
    }

    private static void stage(RelationStagingRepository staging, String version, String equipment,
                              String point, String suffix, Definitions definitions) {
        staging.insert("STAGED-E-" + suffix, version, BUILDING, "EQUIPMENT", equipment,
                node("EQUIPMENT", equipment), "CFGIT-EQ-" + suffix, "隔离设备" + suffix,
                definitions.equipment(), 1L, SPACE, SYSTEM, null);
        staging.insert("STAGED-P-" + suffix, version, BUILDING, "POINT", point,
                node("POINT", point), "CFGIT-PT-" + suffix, "隔离测点" + suffix,
                definitions.point(), 1L, null, SYSTEM, equipment);
    }

    private static void verifyWorkOrderUniquenessAndRollback(JdbcTemplate jdbc, TransactionTemplate transactions) {
        String project = "CFGIT-PROJECT-001";
        jdbc.update("""
                INSERT INTO biz_carbon_project_version(version_id,building_id,version_no,revision,status,
                                                       configuration_json,reason,created_by,created_at)
                VALUES (?,?,1,0,'DRAFT','{}','configuration-upgrade-it',?,?)
                """, project, BUILDING, 1L, System.currentTimeMillis());
        var commands = new CarbonWorkOrderRepository(jdbc);
        commands.insert("CFGIT-COMMAND-001", BUILDING, project, "dedupe-key", "a".repeat(64),
                "b".repeat(64), "{}", 1L);
        assertThatThrownBy(() -> commands.insert("CFGIT-COMMAND-002", BUILDING, project, "dedupe-key",
                "c".repeat(64), "d".repeat(64), "{}", 1L))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            commands.insert("CFGIT-COMMAND-ROLLBACK", BUILDING, project, "rollback-key", "e".repeat(64),
                    "f".repeat(64), "{}", 1L);
            throw new IllegalStateException("injected-command-rollback");
        })).hasMessageContaining("injected-command-rollback");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM biz_carbon_configuration_command WHERE idempotency_key=?",
                "rollback-key")).isZero();
    }

    private static void verifyNativeCompleteStatusConstraint(JdbcTemplate jdbc) {
        insertSnapshot(jdbc, "CFGIT-SNAPSHOT-NATIVE", "a".repeat(64), "CFGIT-PROJECTION-NATIVE",
                "LOCKED_NATIVE_COMPLETE");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM biz_energy_period_result_snapshot WHERE snapshot_id=?",
                "CFGIT-SNAPSHOT-NATIVE")).isEqualTo(1);
        assertThatThrownBy(() -> insertSnapshot(jdbc, "CFGIT-SNAPSHOT-UNKNOWN", "b".repeat(64),
                "CFGIT-PROJECTION-UNKNOWN", "UNKNOWN_STATUS"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("chk_energy_period_snapshot_status")
                .rootCause().isInstanceOfSatisfying(java.sql.SQLException.class,
                        sql -> assertThat(sql.getErrorCode()).isEqualTo(3819));
    }

    private static void insertSnapshot(JdbcTemplate jdbc, String id, String resultKey, String projection,
                                       String status) {
        jdbc.update("""
                INSERT INTO biz_energy_period_result_snapshot
                (snapshot_id,result_key,projection_id,building_id,point_id,period_type,period_start,period_end,
                 timezone_id,period_policy_version_id,snapshot_version,status,result_nature,energy_item_code,
                 native_quantity,native_unit_code,tce_value,tce_unit_code,coverage_ratio,issue_codes,
                 exception_policy_versions,evidence_json,evidence_hash,conversion_selection_json,activity_watermark,
                 supersedes_snapshot_id,source_batch_id,locked_by,locked_at)
                VALUES (?,?,?,?,?,'MONTH',CURRENT_TIMESTAMP(3),DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 MONTH),'Asia/Shanghai','CFGIT-POLICY',
                        1,?,'FORMAL','GRID_ELECTRICITY',1.000000000000,'kWh',NULL,NULL,1.000000,
                        'TCE_RULE_UNAVAILABLE','CFGIT-EXCEPTION','{}',?,NULL,CURRENT_TIMESTAMP(3),NULL,NULL,1,
                        CURRENT_TIMESTAMP(3))
                """, id, resultKey, projection, BUILDING, POINT, status, "e".repeat(64));
    }

    private static int count(JdbcTemplate jdbc, String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static String node(String type, String id) {
        return "CFGIT-NODE-" + type.charAt(0) + "-" + id.substring(id.lastIndexOf('-') + 1);
    }

    private record Definitions(String equipment, String point) { }
}
