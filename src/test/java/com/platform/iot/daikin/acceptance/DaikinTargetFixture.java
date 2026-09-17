package com.platform.iot.daikin.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.BackendDuty;
import com.platform.audit.sensitive.SensitiveChangeService;
import com.platform.audit.system.BindTypedPendingDeviceHandler;
import com.platform.cache.MenuCacheService;
import com.platform.iot.daikin.onboarding.DaikinDirectoryService;
import com.platform.iot.daikin.runtime.DaikinRuntimeScheduler;
import com.platform.iot.daikin.sync.DaikinDirectorySyncService;
import com.platform.iot.daikin.monitoring.DaikinMonitoringScheduler;
import com.platform.iot.onboarding.DeviceOnboardingService;
import com.platform.iot.onboarding.DeviceProductService;
import com.platform.iot.onboarding.api.DeviceOnboardingContracts;
import com.platform.iot.onboarding.api.DeviceProductContracts;
import com.platform.system.service.BuildingScopeService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 只创建带 DAIKIN_TARGET 前缀的专用 fixture；目录、绑定、采集和统计仍由生产服务执行。
 */
@Component
@Profile("daikin-target")
@ConditionalOnProperty(name = {"daikin.target.enabled", "daikin.target.isolated"}, havingValue = "true")
final class DaikinTargetFixture {
    static final String SOURCE_ID = "DAIKIN_TARGET_SOURCE";
    static final String SITE_ID = "DAIKIN_TARGET_SITE";
    static final String CONTROLLER_ID = "DAIKIN_TARGET_LC";
    static final String UNIT_ID = "DAIKIN_TARGET_INDOOR_1";
    static final String BUILDING_ID = "DAIKIN_TARGET_BLD1";
    static final String OTHER_BUILDING_ID = "DAIKIN_TARGET_BLD2";
    static final String SPACE_ID = "DAIKIN_TARGET_SPACE1";
    static final String GROUP_ID = "DAIKIN_TARGET_GROUP1";
    static final String NUMERIC_SOURCE_ID = "DAIKIN_TARGET_HTTP";
    static final String PRODUCT_CODE = "DAIKIN_TARGET_IN_STATE";
    static final long ADMIN_ID = 1L;
    static final long OWNER_ID = 98_001L;
    static final long REALTIME_MENU_ID = 9_800_101L;
    static final long LIVE_ALARM_MENU_ID = 9_800_102L;
    static final long HISTORY_ALARM_MENU_ID = 9_800_103L;
    static final Set<String> ADMIN_ROLES = Set.of("PLATFORM_ADMIN");

    private final JdbcTemplate mysql;
    private final JdbcTemplate taos;
    private final PasswordEncoder passwords;
    private final String adminPassword;
    private final String ownerPassword;
    private final String tdDatabase;
    private final DaikinDirectoryService directory;
    private final DaikinDirectorySyncService sync;
    private final DeviceProductService products;
    private final DeviceOnboardingService onboarding;
    private final SensitiveChangeService changes;
    private final DaikinMonitoringScheduler monitoring;
    private final DaikinRuntimeScheduler runtime;
    private final BuildingScopeService buildingScope;
    private final MenuCacheService menuCache;
    private final ObjectMapper mapper;

    DaikinTargetFixture(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql,
            @Qualifier("taosJdbcTemplate") JdbcTemplate taos,
            PasswordEncoder passwords,
            @Value("${daikin.target.admin-password}") String adminPassword,
            @Value("${daikin.target.owner-password}") String ownerPassword,
            @Value("${tdengine.database}") String tdDatabase,
            DaikinDirectoryService directory, DaikinDirectorySyncService sync,
            DeviceProductService products, DeviceOnboardingService onboarding,
            SensitiveChangeService changes, DaikinMonitoringScheduler monitoring,
            DaikinRuntimeScheduler runtime, BuildingScopeService buildingScope,
            MenuCacheService menuCache, ObjectMapper mapper) {
        this.mysql = mysql;
        this.taos = taos;
        this.passwords = passwords;
        this.adminPassword = adminPassword;
        this.ownerPassword = ownerPassword;
        this.tdDatabase = tdDatabase;
        this.directory = directory;
        this.sync = sync;
        this.products = products;
        this.onboarding = onboarding;
        this.changes = changes;
        this.monitoring = monitoring;
        this.runtime = runtime;
        this.buildingScope = buildingScope;
        this.menuCache = menuCache;
        this.mapper = mapper;
    }

    synchronized void initialize() {
        mysql.update("UPDATE sys_user SET password=?,status=1,del_flag=0 WHERE id=? AND username='admin'",
                passwords.encode(adminPassword), ADMIN_ID);
        mysql.update("""
                INSERT INTO sys_user(id,username,password,nickname,status,del_flag)
                VALUES (?,?,?,?,1,0)
                ON DUPLICATE KEY UPDATE password=VALUES(password),nickname=VALUES(nickname),status=1,del_flag=0
                """, OWNER_ID, "daikin_target_owner", passwords.encode(ownerPassword), "隔离验收业主");
        insertBuildingsAndScope();
        Long ownerRole = mysql.queryForObject(
                "SELECT id FROM sys_role WHERE role_key='BUILDING_OWNER'", Long.class);
        mysql.update("INSERT IGNORE INTO sys_user_role(user_id,role_id) VALUES (?,?)", OWNER_ID, ownerRole);
        mysql.update("INSERT IGNORE INTO sys_user_building(user_id,building_id) VALUES (?,?)", OWNER_ID, BUILDING_ID);
        ensureTargetMenus();
        grant("DAIKIN_TARGET_OWNER_SUBMIT", OWNER_ID, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER, OWNER_ID);
        grant("DAIKIN_TARGET_ADMIN_SUBMIT", ADMIN_ID, BackendDuty.BACKOFFICE_CHANGE_SUBMITTER, ADMIN_ID);
        grant("DAIKIN_TARGET_ADMIN_REVIEW", ADMIN_ID, BackendDuty.BACKOFFICE_CHANGE_REVIEWER, ADMIN_ID);
        buildingScope.evict(OWNER_ID);
        menuCache.evict(ADMIN_ID);
        menuCache.evict(OWNER_ID);

        if (count("SELECT COUNT(*) FROM biz_daikin_source WHERE source_id=?", SOURCE_ID) == 0) {
            directory.registerSource(SOURCE_ID, ADMIN_ID, ADMIN_ROLES);
        }
        if (count("SELECT COUNT(*) FROM biz_daikin_project_mapping WHERE source_id=? AND site_id=?",
                SOURCE_ID, SITE_ID) == 0) {
            directory.mapProject(SOURCE_ID, SITE_ID, BUILDING_ID, ADMIN_ID, ADMIN_ROLES);
        }
        mysql.update("""
                INSERT INTO biz_data_source(source_id,source_code,source_name,building_id,source_category,
                  transport_type,status,config_revision,runtime_revision)
                VALUES (?,?,?,?,'DEVICE_ACCESS','HTTP','ENABLED',1,1)
                ON DUPLICATE KEY UPDATE source_name=VALUES(source_name),building_id=VALUES(building_id),
                  transport_type='HTTP',status='ENABLED'
                """, NUMERIC_SOURCE_ID, "DAIKIN_TARGET_HTTP", "隔离验收温度来源", BUILDING_ID);
        ensureProduct();
    }

    private void ensureTargetMenus() {
        upsertTargetMenu(REALTIME_MENU_ID, "隔离验收实时监测", "/operations/realtime/hvac", 98_101);
        upsertTargetMenu(LIVE_ALARM_MENU_ID, "隔离验收实时报警", "/operations/alarms/liveAlarms", 98_102);
        upsertTargetMenu(HISTORY_ALARM_MENU_ID, "隔离验收历史报警", "/operations/alarms/historyAlarms", 98_103);
        for (long menuId : List.of(REALTIME_MENU_ID, LIVE_ALARM_MENU_ID, HISTORY_ALARM_MENU_ID)) {
            mysql.update("""
                    INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
                    SELECT id,? FROM sys_role WHERE role_key IN ('PLATFORM_ADMIN','BUILDING_OWNER')
                    """, menuId);
        }
    }

    private void upsertTargetMenu(long menuId, String name, String path, int sortOrder) {
        mysql.update("""
                INSERT INTO sys_menu(id,parent_id,menu_name,menu_type,path,component,perms,icon,
                  visible,status,sort_order)
                VALUES (?,0,?,'C',?,NULL,NULL,NULL,1,1,?)
                ON DUPLICATE KEY UPDATE parent_id=0,menu_name=VALUES(menu_name),menu_type='C',
                  path=VALUES(path),component=NULL,perms=NULL,icon=NULL,visible=1,status=1,
                  sort_order=VALUES(sort_order)
                """, menuId, name, path, sortOrder);
    }

    synchronized Map<String, Object> syncDirectory() {
        var requested = sync.request(SOURCE_ID, ADMIN_ID);
        return dispatchDirectory(requested.jobId());
    }

    synchronized Map<String, Object> dispatchDirectory() {
        String jobId = optionalString("""
                SELECT job_id FROM biz_daikin_directory_sync_job
                WHERE source_id=? ORDER BY create_time DESC LIMIT 1
                """, SOURCE_ID);
        if (jobId == null) throw new IllegalStateException("DAIKIN_TARGET_SYNC_JOB_NOT_QUEUED");
        return dispatchDirectory(jobId);
    }

    private Map<String, Object> dispatchDirectory(String jobId) {
        sync.scheduledSync();
        await("directory sync", () -> {
            String status = sync.get(SOURCE_ID, jobId).status();
            return status.equals("SUCCEEDED") || status.equals("FAILED") ? status : null;
        });
        var job = sync.get(SOURCE_ID, jobId);
        if (!"SUCCEEDED".equals(job.status())) {
            throw new IllegalStateException("DAIKIN_TARGET_SYNC_FAILED:" + job.errorCode());
        }
        return state();
    }

    synchronized Map<String, Object> approveAndActivate() {
        String pendingId = requirePendingId();
        var connection = onboarding.connection(pendingId, ADMIN_ROLES);
        if (connection.identityId() == null) {
            var point = new DeviceOnboardingContracts.PointBindingRequest(
                    "roomTemp", null, "AHU98_roomTemp", "隔离验收室温",
                    "RULE_AHU_MAIN", "AHU", "MAIN", "AI");
            var binding = new DeviceOnboardingContracts.TypedBindRequest(productId(), BUILDING_ID,
                    SPACE_ID, GROUP_ID, null,
                    new DeviceOnboardingContracts.NewEquipmentRequest("隔离验收内机", "Daikin-Test-Provider"),
                    List.of(point), NUMERIC_SOURCE_ID);
            var command = new BindTypedPendingDeviceHandler.Command(pendingId, binding);
            executeSeparated(BindTypedPendingDeviceHandler.CODE, mapper.valueToTree(command),
                    "DAIKIN_TARGET_BIND_V2_" + pendingId);
            connection = onboarding.connection(pendingId, ADMIN_ROLES);
        }
        if (!"ACTIVE".equals(connection.identityStatus())) {
            executeSeparated("ACTIVATE_DEVICE_IDENTITY",
                    mapper.valueToTree(Map.of("identityId", connection.identityId())),
                    "DAIKIN_TARGET_ACTIVATE_" + connection.identityId());
        }
        return state();
    }

    synchronized Map<String, Object> collect() {
        requireActive();
        long previousRound = optionalLong("SELECT round_id FROM biz_daikin_monitor_round WHERE source_id=?", SOURCE_ID);
        awaitMonitoringRound(() -> {
            monitoring.schedule();
            List<Map<String, Object>> rows = mysql.queryForList(
                    "SELECT round_id,status FROM biz_daikin_monitor_round WHERE source_id=?", SOURCE_ID);
            if (rows.isEmpty()) return null;
            long round = ((Number) rows.getFirst().get("round_id")).longValue();
            String status = rows.getFirst().get("status").toString();
            if (round <= previousRound || !(status.equals("SUCCEEDED") || status.equals("FAILED"))) return null;
            return status;
        });
        String status = mysql.queryForObject(
                "SELECT status FROM biz_daikin_monitor_round WHERE source_id=?", String.class, SOURCE_ID);
        if (!"SUCCEEDED".equals(status)) throw new IllegalStateException("DAIKIN_TARGET_COLLECTION_FAILED");
        String pointId = requirePointId();
        Long persisted = taos.queryForObject("SELECT COUNT(*) FROM " + tdDatabase
                + ".st_raw_event WHERE point_id=? AND source_system='DAIKIN_V2'", Long.class, pointId);
        if (persisted == null || persisted < 1) {
            throw new IllegalStateException("DAIKIN_TARGET_TDENGINE_WRITE_NOT_VISIBLE");
        }
        return state();
    }

    synchronized Map<String, Object> collectRuntime() {
        requireActive();
        long before = count("SELECT COUNT(*) FROM biz_daikin_runtime_value WHERE source_id=?", SOURCE_ID);
        await("runtime persistence", () -> {
            runtime.schedule();
            long current = count("SELECT COUNT(*) FROM biz_daikin_runtime_value WHERE source_id=?", SOURCE_ID);
            return current > before ? current : null;
        });
        return state();
    }

    synchronized Map<String, Object> resetObservations() {
        List<String> identities = mysql.queryForList("""
                SELECT p.bound_identity_id FROM biz_daikin_directory d
                JOIN biz_pending_device p ON p.pending_id=d.pending_id
                WHERE d.source_id=? AND p.bound_identity_id IS NOT NULL
                """, String.class, SOURCE_ID);
        mysql.update("DELETE FROM biz_daikin_runtime_revision WHERE source_id=?", SOURCE_ID);
        mysql.update("DELETE FROM biz_daikin_runtime_value WHERE source_id=?", SOURCE_ID);
        mysql.update("DELETE FROM biz_daikin_runtime_job WHERE source_id=?", SOURCE_ID);
        mysql.update("DELETE FROM biz_daikin_runtime_plan WHERE source_id=?", SOURCE_ID);
        mysql.update("DELETE FROM biz_daikin_monitor_inbox WHERE source_id=?", SOURCE_ID);
        mysql.update("DELETE FROM biz_daikin_monitor_round WHERE source_id=?", SOURCE_ID);
        mysql.update("DELETE FROM biz_daikin_state_event WHERE source_id=?", SOURCE_ID);
        mysql.update("DELETE FROM biz_daikin_exception_instance WHERE source_id=?", SOURCE_ID);
        mysql.update("DELETE FROM biz_daikin_source_result WHERE source_id=?", SOURCE_ID);
        mysql.update("DELETE FROM biz_daikin_current_state WHERE source_id=?", SOURCE_ID);
        for (String identity : identities) {
            mysql.update("DELETE FROM biz_daikin_monitoring_target WHERE identity_id=?", identity);
        }
        String pointId = optionalString("SELECT a.point_id FROM biz_point_alias a WHERE a.source_id=? "
                + "AND a.source_system='DAIKIN_V2' LIMIT 1", NUMERIC_SOURCE_ID);
        if (pointId != null && pointId.matches("[A-Za-z0-9_]+")) {
            try {
                // 保留子表结构，避免生产仓储已缓存“子表存在”后被测试控制面从库中删除。
                taos.execute("DELETE FROM " + tdDatabase + ".st_raw_event_" + pointId);
            } catch (org.springframework.dao.DataAccessException missingTable) {
                String message = String.valueOf(missingTable.getMessage()).toLowerCase(java.util.Locale.ROOT);
                if (!(message.contains("table does not exist") || message.contains("table not exist"))) {
                    throw missingTable;
                }
            }
        }
        return state();
    }

    Map<String, Object> state() {
        String pendingId = optionalString(
                "SELECT pending_id FROM biz_daikin_directory WHERE source_id=? AND unit_id=?",
                SOURCE_ID, UNIT_ID);
        String identityId = pendingId == null ? null : optionalString(
                "SELECT bound_identity_id FROM biz_pending_device WHERE pending_id=?", pendingId);
        String equipmentId = identityId == null ? null : optionalString(
                "SELECT equip_id FROM biz_device_identity WHERE identity_id=?", identityId);
        String pointId = equipmentId == null ? null : optionalString(
                "SELECT point_id FROM biz_data_point WHERE equip_id=? AND suffix_code='roomTemp'", equipmentId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ready", true);
        result.put("vendorWireConfirmed", false);
        result.put("provider", "TEST_ONLY_SIMULATED_TRANSPORT");
        result.put("sourceId", SOURCE_ID);
        result.put("sourceName", "隔离验收大金来源");
        result.put("pendingId", pendingId);
        result.put("identityId", identityId);
        result.put("equipmentId", equipmentId);
        result.put("equipmentName", "隔离验收内机");
        result.put("pointId", pointId);
        result.put("buildingId", BUILDING_ID);
        result.put("buildingName", "隔离验收建筑");
        result.put("otherBuildingId", OTHER_BUILDING_ID);
        result.put("otherBuildingName", "隔离对照建筑");
        result.put("productId", productId());
        result.put("numericSourceId", NUMERIC_SOURCE_ID);
        result.put("adminUsername", "admin");
        result.put("ownerUsername", "daikin_target_owner");
        result.put("monitoringRoundStatus", optionalString(
                "SELECT status FROM biz_daikin_monitor_round WHERE source_id=?", SOURCE_ID));
        result.put("temperatureRows", pointId == null ? 0L : temperatureRows(pointId));
        result.put("runtimeValues", count("SELECT COUNT(*) FROM biz_daikin_runtime_value WHERE source_id=?", SOURCE_ID));
        result.put("runtimeMetric", "totalRuntime");
        result.put("runtimeUnit", "minute");
        result.put("expectedRuntimeMinutes", 120);
        result.put("activeExceptions", count(
                "SELECT COUNT(*) FROM biz_daikin_exception_instance WHERE source_id=? AND active_key IS NOT NULL",
                SOURCE_ID));
        return result;
    }

    private void insertBuildingsAndScope() {
        mysql.update("""
                INSERT INTO building(building_id,building_name,building_code,building_type,total_gfa,climate_zone,region_code)
                VALUES (?,?,?,'办公',1000.00,'夏热冬冷','330100')
                ON DUPLICATE KEY UPDATE building_name=VALUES(building_name)
                """, BUILDING_ID, "隔离验收建筑", "DAIKIN-TARGET-1");
        mysql.update("""
                INSERT INTO building(building_id,building_name,building_code,building_type,total_gfa,climate_zone,region_code)
                VALUES (?,?,?,'办公',800.00,'夏热冬冷','330100')
                ON DUPLICATE KEY UPDATE building_name=VALUES(building_name)
                """, OTHER_BUILDING_ID, "隔离对照建筑", "DAIKIN-TARGET-2");
        mysql.update("""
                INSERT INTO biz_space(space_id,building_id,parent_space_id,space_name,space_code,space_type,floor_level,del_flag)
                VALUES (?,?,NULL,?,?, 'ROOM',1,0)
                ON DUPLICATE KEY UPDATE space_name=VALUES(space_name),del_flag=0
                """, SPACE_ID, BUILDING_ID, "隔离验收空间", "DAIKIN-TARGET-SPACE");
        mysql.update("""
                INSERT INTO biz_system_group(system_group_id,system_group_code,building_id,system_type,system_group_name,del_flag)
                VALUES (?,?,?,'AIR_CONDITIONING',?,0)
                ON DUPLICATE KEY UPDATE system_group_name=VALUES(system_group_name),del_flag=0
                """, GROUP_ID, "DAIKIN-TARGET-GROUP", BUILDING_ID, "隔离验收系统");
    }

    private void ensureProduct() {
        if (count("SELECT COUNT(*) FROM biz_device_product WHERE product_code=?", PRODUCT_CODE) == 0) {
            var temperature = new DeviceProductContracts.PointTemplateRequest(
                    "roomTemp", "隔离验收室温", "roomTemp", "°C",
                    new BigDecimal("-30"), new BigDecimal("60"), false, true, 1, true);
            products.createTypedState(new DeviceProductContracts.TypedStateRequest(
                    PRODUCT_CODE, "隔离验收大金内机产品", "Daikin-Test-Provider", null,
                    "IDU", "DAIKIN_INDOOR_V2", List.of(temperature)), ADMIN_ID, ADMIN_ROLES);
        }
        String productId = productId();
        String status = mysql.queryForObject(
                "SELECT status FROM biz_device_product WHERE product_id=?", String.class, productId);
        if (!"ENABLED".equals(status)) products.enable(productId, ADMIN_ID, ADMIN_ROLES);
    }

    private void executeSeparated(String operation, com.fasterxml.jackson.databind.JsonNode command, String key) {
        var draft = changes.createDraft(OWNER_ID, operation, command, key);
        var submitted = draft.status().name().equals("DRAFT") ? changes.submit(OWNER_ID, draft.requestId()) : draft;
        if (submitted.status().name().equals("PENDING_REVIEW")) {
            changes.approve(ADMIN_ID, submitted.requestId(), "隔离目标环境审核");
        }
        var current = changes.detail(ADMIN_ID, submitted.requestId());
        if (current.status().name().equals("APPROVED")) changes.execute(ADMIN_ID, submitted.requestId());
    }

    private void requireActive() {
        String pending = requirePendingId();
        var connection = onboarding.connection(pending, ADMIN_ROLES);
        if (!"ACTIVE".equals(connection.identityStatus())) {
            throw new IllegalStateException("DAIKIN_TARGET_IDENTITY_NOT_ACTIVE");
        }
    }

    private String productId() {
        return mysql.queryForObject("SELECT product_id FROM biz_device_product WHERE product_code=?",
                String.class, PRODUCT_CODE);
    }

    private String requirePendingId() {
        String value = optionalString("SELECT pending_id FROM biz_daikin_directory WHERE source_id=? AND unit_id=?",
                SOURCE_ID, UNIT_ID);
        if (value == null) throw new IllegalStateException("DAIKIN_TARGET_DIRECTORY_NOT_SYNCED");
        return value;
    }

    private String requirePointId() {
        String value = optionalString("SELECT a.point_id FROM biz_point_alias a WHERE a.source_id=? "
                + "AND a.source_system='DAIKIN_V2' AND a.source_point_code LIKE '%:roomTemp' LIMIT 1",
                NUMERIC_SOURCE_ID);
        if (value == null) throw new IllegalStateException("DAIKIN_TARGET_TEMPERATURE_POINT_MISSING");
        return value;
    }

    private long temperatureRows(String pointId) {
        Long result = taos.queryForObject("SELECT COUNT(*) FROM " + tdDatabase
                + ".st_raw_event WHERE point_id=? AND source_system='DAIKIN_V2'", Long.class, pointId);
        return result == null ? 0 : result;
    }

    private void grant(String assignment, long user, BackendDuty duty, long createdBy) {
        mysql.update("""
                INSERT INTO sys_user_backend_duty
                  (assignment_id,user_id,duty_key,status,effective_at,created_by,created_at)
                VALUES (?,?,?,'ACTIVE',?,?,?)
                ON DUPLICATE KEY UPDATE status='ACTIVE',effective_at=VALUES(effective_at)
                """, assignment, user, duty.name(), LocalDateTime.now().minusMinutes(1), createdBy,
                LocalDateTime.now());
    }

    private long count(String sql, Object... parameters) {
        Long value = mysql.queryForObject(sql, Long.class, parameters);
        return value == null ? 0 : value;
    }

    private String optionalString(String sql, Object... parameters) {
        List<String> values = mysql.queryForList(sql, String.class, parameters);
        return values.isEmpty() ? null : values.getFirst();
    }

    private long optionalLong(String sql, Object... parameters) {
        List<Long> values = mysql.queryForList(sql, Long.class, parameters);
        return values.isEmpty() ? -1 : values.getFirst();
    }

    private static <T> T await(String operation, java.util.function.Supplier<T> probe) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                T value = probe.get();
                if (value != null) return value;
            } catch (RuntimeException failure) {
                last = failure;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("DAIKIN_TARGET_WAIT_INTERRUPTED", interrupted);
            }
        }
        throw new IllegalStateException("DAIKIN_TARGET_TIMEOUT:" + operation, last);
    }

    /** 生产采集周期最小为60秒；保留已完成轮次并等待下一个自然分钟，不篡改调度检查点。 */
    private static <T> T awaitMonitoringRound(java.util.function.Supplier<T> probe) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75);
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                T value = probe.get();
                if (value != null) return value;
            } catch (RuntimeException failure) {
                last = failure;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("DAIKIN_TARGET_WAIT_INTERRUPTED", interrupted);
            }
        }
        throw new IllegalStateException("DAIKIN_TARGET_TIMEOUT:monitoring round", last);
    }
}
