package com.platform.iot.daikin.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.audit.*;
import com.platform.system.mapper.SysMenuMapper;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.model.entity.SysMenu;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static com.platform.iot.daikin.control.DaikinControlContracts.*;

/** 仅 H2 和内存厂家，无套接字、实际来源地址或用户凭据。 */
class ControlFixture {
    final ObjectMapper json = new ObjectMapper();
    final MutableClock clock = new MutableClock();
    final JdbcTemplate jdbc;
    final DaikinControlProperties props = new DaikinControlProperties();
    final DaikinControlAccess access;
    final DaikinControlPolicy policy = new DaikinControlPolicy();
    final DaikinControlStore store;
    final FakeGateway gateway = new FakeGateway();
    final DaikinControlService service;
    boolean auditFails;

    ControlFixture() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:control-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000", "sa", "");
        jdbc = new JdbcTemplate(ds);
        String auditSchema = new ClassPathResource("audit-governance-schema-test.sql").getContentAsString(StandardCharsets.UTF_8);
        String setup = """
                CREATE TABLE sys_user(id BIGINT PRIMARY KEY,status INT,del_flag INT);
                CREATE TABLE user_roles(user_id BIGINT,role_key VARCHAR(50));
                CREATE TABLE sys_user_building(user_id BIGINT,building_id VARCHAR(32));
                CREATE TABLE biz_equipment(equip_id VARCHAR(32) PRIMARY KEY,building_id VARCHAR(32),product_id VARCHAR(32),del_flag INT);
                CREATE TABLE biz_device_identity(identity_id VARCHAR(32) PRIMARY KEY,equip_id VARCHAR(32),building_id VARCHAR(32),
                  identity_type VARCHAR(32),status INT,expected_profile_code VARCHAR(32));
                CREATE TABLE biz_pending_device(pending_id VARCHAR(32) PRIMARY KEY,bound_identity_id VARCHAR(32),status VARCHAR(20));
                CREATE TABLE biz_daikin_source(source_id VARCHAR(200) PRIMARY KEY);
                CREATE TABLE biz_daikin_directory(pending_id VARCHAR(32) PRIMARY KEY,source_id VARCHAR(200),site_id VARCHAR(200),
                  controller_id VARCHAR(200),unit_id VARCHAR(200),equipment_id VARCHAR(200),device_kind VARCHAR(10),missing INT);
                CREATE TABLE biz_device_product(product_id VARCHAR(32) PRIMARY KEY,status VARCHAR(20),identity_type VARCHAR(32),expected_profile_code VARCHAR(32));
                CREATE TABLE biz_daikin_project_mapping(source_id VARCHAR(200),site_id VARCHAR(200),building_id VARCHAR(32),mapping_version INT);
                """;
        setup += auditSchema.substring(auditSchema.indexOf("CREATE TABLE sys_backend_duty"), auditSchema.indexOf("CREATE TABLE sys_sensitive_change_request"));
        setup += auditSchema.substring(auditSchema.indexOf("CREATE TABLE sys_security_audit_event"), auditSchema.indexOf("ALTER TABLE biz_collection"));
        new ResourceDatabasePopulator(new ByteArrayResource(setup.getBytes(StandardCharsets.UTF_8)),
                new ClassPathResource("daikin-control-test.sql")).execute(ds);
        // 对齐真实 V25 的审计结果约束，不能用无 CHECK 的 H2 夹具放过领域状态误写。
        jdbc.execute("ALTER TABLE sys_security_audit_event ADD CONSTRAINT chk_security_audit_result CHECK (result IN ('SUCCESS','REJECTED','DENIED','FAILED'))");
        jdbc.update("INSERT INTO sys_user VALUES (7,1,0),(8,1,0),(9,1,0),(10,1,0)");
        jdbc.update("INSERT INTO user_roles VALUES (7,'ENERGY_MANAGER'),(8,'BUILDING_OWNER'),(9,'PLATFORM_ADMIN'),(10,'THIRD_PARTY')");
        jdbc.update("INSERT INTO sys_user_building VALUES (7,'building'),(8,'building'),(10,'building')");
        jdbc.update("INSERT INTO biz_equipment VALUES ('equipment','building','product',0),('other','other-building','product',0)");
        jdbc.update("INSERT INTO biz_device_identity VALUES ('identity','equipment','building','DAIKIN_UNIT',1,'DAIKIN_INDOOR')");
        jdbc.update("INSERT INTO biz_pending_device VALUES ('pending','identity','BOUND')");
        jdbc.update("INSERT INTO biz_daikin_source VALUES ('source')");
        jdbc.update("INSERT INTO biz_daikin_directory VALUES ('pending','source','site','controller','unit-11','vendor-22','INDOOR',0)");
        jdbc.update("INSERT INTO biz_device_product VALUES ('product','ENABLED','DAIKIN_UNIT','DAIKIN_INDOOR')");
        jdbc.update("INSERT INTO biz_daikin_project_mapping VALUES ('source','site','building',1)");
        jdbc.update("""
                INSERT INTO biz_daikin_control_grant(equipment_id,identity_id,source_id,vendor_id,building_id,mapping_version,
                  enabled,air_flow_confirmed,version,confirmed_by,confirmed_at,evidence,relationship_hash,approved_actions)
                VALUES ('equipment','identity','source','vendor-22','building',1,TRUE,TRUE,1,9,0,'isolated fixture',?,?)
                """, policy.relationshipHash(new DaikinControlGateway.Snapshot(gateway.controller, gateway.unit)), """
                {"ON_OFF":["on","off"],"TEMPERATURE":{"minimum":16,"maximum":32},"MODE":["cooling","heating","fan"],
                 "AIR_FLOW":["airFlowZero","airFlowOne","airFlowTwo","airFlowThree","airFlowFour","airFlowSeven","airFlowAuto"],
                 "FAN_SPEED":["low","middle","high","automatic","middleLow","middleHigh"]}
                """);
        grant(7); grant(8); grant(10);
        var auditProperties = new AuditGovernanceProperties();
        auditProperties.setEnvironmentMode(AuditEnvironmentMode.TEST);
        var roles = mock(SysRoleMapper.class);
        when(roles.selectRoleKeysByUserId(anyLong())).thenAnswer(i -> jdbc.queryForList("SELECT role_key FROM user_roles WHERE user_id=?", String.class, (Long)i.getArgument(0)));
        var menus = mock(SysMenuMapper.class);
        var menu = new SysMenu(); menu.setMenuType("C"); menu.setPath("/operations/realtime/hvac");
        when(menus.selectVisibleMenusByUserId(anyLong())).thenReturn(List.of(menu));
        access = new DaikinControlAccess(jdbc, new JdbcBackendDutyService(jdbc, auditProperties), roles, menus, props);
        AuditEvidenceWriter writer = new JdbcSecurityAuditEvidenceWriter(jdbc, new AuditSummarySanitizer());
        store = new DaikinControlStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), json,
                evidence -> { if (auditFails) throw new IllegalStateException("simulated audit failure"); writer.append(evidence); }, auditProperties);
        props.setEnabled(true);
        service = new DaikinControlService(props, access, policy, store, List.of(gateway), clock);
    }

    void grant(long user) {
        jdbc.update("""
                INSERT INTO sys_user_backend_duty(assignment_id,user_id,duty_key,status,effective_at,expires_at,source_request_id,created_by,created_at)
                VALUES (?,?,'HVAC_DEVICE_CONTROL','ACTIVE',TIMESTAMP '2020-01-01 00:00:00',NULL,NULL,9,CURRENT_TIMESTAMP)
                """, "grant-" + user, user);
    }
    Submit request(Action action, String value, String key) { return new Submit(action, value, key, service.options(7, "equipment").revision()); }
    Operation submit(Action action, String value) { return service.submit(7, "equipment", request(action, value, "request-123"), "test-trace"); }
    void stabilize(DaikinControlService targetService) {
        clock.advance(15_000); targetService.reconcile();
        for (int i = 0; i < 6; i++) { clock.advance(30_000); targetService.reconcile(); }
    }

    class FakeGateway implements DaikinControlGateway {
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger reads = new AtomicInteger();
        String code = "10000";
        boolean loseResponse;
        boolean failRead;
        boolean apply = true;
        Consumer<DaikinControlAccess.Target> onRead = t -> { };
        Runnable onSend = () -> { };
        final ObjectNode controller = json.createObjectNode().put("lcNo", "controller").put("isConnectionUp", true).put("inForcedStop", false);
        final ObjectNode unit;
        FakeGateway() {
            try { unit = (ObjectNode) json.readTree("""
                    {"id":"vendor-22","unitId":"unit-11","unitStatus":"stopped","inCommunicationError":false,
                    "inEquipmentError":false,"inMaintenanceMode":false,"isGroupSlave":false,"masterSlaveFlag":"Master",
                    "masterSlaveIds":[],"onOff":"off","mode":"cooling","temperature":24,"fanSpeed":"low","airflowDirection":"airFlowZero",
                    "onOffModeSetList":["on","off"],"modeSetList":["cooling","heating","fan"],"fanSpeedSetList":["low","high","automatic"],
                    "DefaultSetpointRange":{"min":16,"max":32,"step":1},"limitSettempCool":"off","limitSettempHeat":"off"}
                    """); } catch (Exception e) { throw new IllegalStateException(e); }
        }
        @Override public boolean supports(String source) { return "source".equals(source); }
        @Override public Snapshot read(DaikinControlAccess.Target target) {
            reads.incrementAndGet(); onRead.accept(target);
            if (failRead) throw new IllegalStateException("simulated unavailable");
            return new Snapshot(controller.deepCopy(), unit.deepCopy());
        }
        @Override public Reply send(DaikinControlAccess.Target target, Action action, String value) {
            writes.incrementAndGet(); onSend.run();
            if (apply && code.equals("10000")) {
                if (action == Action.TEMPERATURE) unit.put("temperature", Integer.parseInt(value)); else unit.put(action.observedField, value);
                if (action == Action.ON_OFF) unit.put("unitStatus", value.equals("on") ? "operating" : "stopped");
            }
            if (loseResponse) throw new IllegalStateException("simulated response lost after acceptance");
            return new Reply(code);
        }
    }
    static class MutableClock extends Clock {
        long now = Instant.parse("2026-10-11T00:00:00Z").toEpochMilli();
        void advance(long millis) { now += millis; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }
}
