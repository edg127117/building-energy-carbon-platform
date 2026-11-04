package com.platform.iot.daikin.monitoring.query;

import com.platform.framework.exception.BusinessException;
import com.platform.iot.daikin.monitoring.DaikinMonitoringTargets;
import com.platform.iot.daikin.monitoring.state.DaikinMonitoringStateService;
import com.platform.iot.daikin.mapping.DaikinDevicePageDecoder;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.system.mapper.SysMenuMapper;
import com.platform.system.model.entity.SysMenu;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DaikinBuildingMonitoringServiceTest {
    private static final long NOW = Instant.parse("2026-11-02T03:00:00Z").toEpochMilli();
    private static final Set<String> ROLES = Set.of("BUILDING_OWNER");
    private JdbcTemplate jdbc;
    private BuildingScopeService buildings;
    private SysMenuMapper menus;
    private DaikinBuildingMonitoringService service;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:building-monitoring-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("daikin-monitoring-state-test.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE biz_equipment(equip_id VARCHAR(32) PRIMARY KEY,building_id VARCHAR(32),space_id VARCHAR(32),
                  system_group_id VARCHAR(32),product_id VARCHAR(32),equip_code VARCHAR(50),equip_name VARCHAR(100),del_flag INT);
                CREATE TABLE biz_device_identity(identity_id VARCHAR(32) PRIMARY KEY,equip_id VARCHAR(32),building_id VARCHAR(32),
                  identity_type VARCHAR(32),status INT,identity_value VARCHAR(200),expected_profile_code VARCHAR(50));
                CREATE TABLE biz_pending_device(pending_id VARCHAR(32),bound_identity_id VARCHAR(32),status VARCHAR(20));
                CREATE TABLE biz_daikin_directory(pending_id VARCHAR(32),source_id VARCHAR(200),site_id VARCHAR(32),
                  controller_id VARCHAR(32),device_kind VARCHAR(10),unit_id VARCHAR(32));
                CREATE TABLE biz_daikin_project_mapping(source_id VARCHAR(200),site_id VARCHAR(32),building_id VARCHAR(32),mapping_version INT);
                CREATE TABLE biz_device_product(product_id VARCHAR(32),status VARCHAR(20),expected_profile_code VARCHAR(50),identity_type VARCHAR(32));
                CREATE TABLE biz_space(space_id VARCHAR(32),building_id VARCHAR(32),space_name VARCHAR(100),parent_space_id VARCHAR(32),space_type VARCHAR(20),del_flag INT);
                INSERT INTO biz_device_product VALUES ('product','ENABLED','DAIKIN_INDOOR_V2','DAIKIN_UNIT');
                INSERT INTO biz_space VALUES ('floor','B','三楼',NULL,'FLOOR',0),('room','B','实验室','floor','ROOM',0);
                """);
        buildings = mock(BuildingScopeService.class);
        menus = mock(SysMenuMapper.class);
        var menu = new SysMenu(); menu.setMenuType("C"); menu.setPath("/operations/realtime/hvac");
        when(menus.selectVisibleMenusByUserId(7L)).thenReturn(List.of(menu));
        service = serviceAt(NOW);
    }

    @Test
    void aggregatesCompleteScopeBeyondListPageAndNeverConflatesCommunicationWithPower() {
        for (int i = 0; i < 105; i++) device("d" + i, "B", "INDOOR");
        state("d0", "onOff", "on", "PRESENT", NOW - 1_000);
        state("d0", "mode", "cooling", "PRESENT", NOW - 2_000);
        state("d0", "fanSpeed", "middleHigh", "PRESENT", NOW - 3_000);
        state("d1", "onOff", "off", "PRESENT", NOW);
        state("d1", "controller.isConnectionUp", "false", "PRESENT", NOW);
        state("d2", "controller.isConnectionUp", "true", "PRESENT", NOW);
        var summary = service.summary(7L, ROLES, "B");
        assertThat(summary.total()).isEqualTo(105);
        assertThat(summary.on()).isEqualTo(1);
        assertThat(summary.off()).isZero();
        assertThat(summary.unknown()).isEqualTo(104);
        assertThat(summary.on() + summary.off() + summary.unknown()).isEqualTo(summary.total());
        assertThat(summary.unknownReasons()).containsEntry("COMMUNICATION_ERROR", 1L).containsEntry("MISSING", 103L);
        assertThat(summary.modes().counts()).containsEntry("cooling", 1L).containsEntry("fan", 0L);
        assertThat(summary.speeds().counts()).containsEntry("middleHigh", 1L).containsEntry("middle", 0L);
        assertThat(summary.expiresAt()).isEqualTo(NOW - 3_000 + 300_001);
        verify(buildings).checkAccess(7L, ROLES, "B");
    }

    @Test
    void distinguishesOffStaleMissingInvalidFutureAndConflictingValues() {
        for (String id : List.of("off", "stale", "invalid", "future", "conflict", "moved", "boundary")) device(id, "B", "INDOOR");
        state("off", "onOff", "off", "PRESENT", NOW);
        state("stale", "onOff", "on", "PRESENT", NOW - 300_001);
        state("invalid", "onOff", "on", "INVALID", NOW);
        state("future", "onOff", "on", "PRESENT", NOW + 1);
        state("conflict", "onOff", "off", "PRESENT", NOW);
        state("conflict", "unitStatus", "operating", "PRESENT", NOW);
        state("moved", "onOff", "on", "PRESENT", NOW);
        jdbc.update("UPDATE biz_daikin_current_state SET last_attempt_building_id='OTHER' WHERE identity_id='i-moved'");
        state("boundary", "onOff", "on", "PRESENT", NOW - 300_000);
        var summary = service.summary(7L, ROLES, "B");
        assertThat(summary.on()).isEqualTo(1); assertThat(summary.off()).isEqualTo(1); assertThat(summary.unknown()).isEqualTo(5);
        assertThat(summary.unknownReasons()).containsEntry("STALE", 1L).containsEntry("INVALID", 1L)
                .containsEntry("INVALID_TIME", 1L).containsEntry("STATE_CONFLICT", 1L).containsEntry("OWNERSHIP_CHANGED", 1L);
        assertThat(summary.expiresAt()).isEqualTo(NOW + 1);
        // 边界值此时过期；原先晚一毫秒的来源时间此时已到达，不能把两台设备的身份混为同一个断言。
        assertThat(serviceAt(NOW + 1).summary(7L, ROLES, "B").unknownReasons()).containsEntry("STALE", 2L);
    }

    @Test
    void retainsExtraEnumsAndDoesNotTurnMissingOrControlCapabilitiesIntoZeroOrUnsupported() {
        device("a", "B", "INDOOR"); device("b", "B", "INDOOR");
        state("a", "onOff", "on", "PRESENT", NOW); state("b", "onOff", "on", "PRESENT", NOW);
        state("a", "mode", "dry", "PRESENT", NOW); state("a", "fanSpeed", "middleLow", "PRESENT", NOW);
        state("b", "mode", null, "UNKNOWN", NOW); state("b", "fanSpeedSetList", "[]", "PRESENT", NOW);
        var result = service.summary(7L, ROLES, "B");
        assertThat(result.modes().counts()).containsEntry("dry", 1L).containsEntry("cooling", 0L);
        assertThat(result.modes().observed()).isEqualTo(1); assertThat(result.modes().unknown()).isEqualTo(1);
        assertThat(result.speeds().counts()).containsEntry("middleLow", 1L).containsEntry("low", 0L);
        assertThat(result.speeds().unknown()).isEqualTo(1); assertThat(result.speeds().unsupported()).isZero();
        jdbc.update("DELETE FROM biz_daikin_current_state WHERE field_name IN ('mode','fanSpeed')");
        result = service.summary(7L, ROLES, "B");
        assertThat(result.modes().counts()).isEmpty(); assertThat(result.speeds().counts()).isEmpty();
        assertThat(result.modes().unknown()).isEqualTo(2); assertThat(result.speeds().unknown()).isEqualTo(2);
    }

    @Test
    void expiresUnknownSnapshotAtTheCommunicationEvidenceBoundaryToo() {
        device("a", "B", "INDOOR");
        state("a", "onOff", "on", "PRESENT", NOW);
        state("a", "inCommunicationError", "true", "PRESENT", NOW - 200_000);
        var result = service.summary(7L, ROLES, "B");
        assertThat(result.unknown()).isEqualTo(1);
        assertThat(result.expiresAt()).isEqualTo(NOW + 100_001);
        assertThat(serviceAt(NOW + 100_001).summary(7L, ROLES, "B").on()).isEqualTo(1);
    }

    @Test
    void excludesInactiveProductsOutdoorOtherBuildingsAndCountsIdentityConflictOnce() {
        device("normal", "B", "INDOOR"); device("disabled", "B", "INDOOR"); device("draft", "B", "INDOOR");
        device("out", "B", "OUTDOOR"); device("other", "C", "INDOOR");
        jdbc.update("UPDATE biz_device_identity SET status=0 WHERE equip_id='disabled'");
        jdbc.update("INSERT INTO biz_device_product VALUES ('draft','DRAFT','DAIKIN_INDOOR_V2','DAIKIN_UNIT')");
        jdbc.update("UPDATE biz_equipment SET product_id='draft' WHERE equip_id='draft'");
        device("dup", "B", "INDOOR");
        jdbc.update("INSERT INTO biz_device_identity SELECT 'extra',equip_id,building_id,identity_type,status,'extra',expected_profile_code FROM biz_device_identity WHERE equip_id='dup'");
        jdbc.update("INSERT INTO biz_pending_device VALUES ('extra','extra','BOUND')");
        jdbc.update("INSERT INTO biz_daikin_directory VALUES ('extra','s-dup','site','controller','INDOOR','extra')");
        state("dup", "onOff", "on", "PRESENT", NOW);
        var result = service.summary(7L, ROLES, "B");
        assertThat(result.total()).isEqualTo(2); assertThat(result.on()).isZero();
        assertThat(result.unknownReasons()).containsEntry("IDENTITY_CONFLICT", 1L);
        assertThat(service.ranking(7L, ROLES, "B", "2026-11", 5).excludedReasons()).containsEntry("IDENTITY_CONFLICT", 1L);
        jdbc.update("DELETE FROM biz_daikin_monitoring_target WHERE equipment_id='normal'");
        assertThat(service.summary(7L, ROLES, "B").total()).isEqualTo(2);
    }

    @Test
    void checksExactMenuAndBuildingBeforeEitherSummaryOrRanking() {
        doThrow(new BusinessException(403, "DENIED", "denied")).when(buildings).checkAccess(7L, ROLES, "PRIVATE");
        assertThatThrownBy(() -> service.summary(7L, ROLES, "PRIVATE")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.ranking(7L, ROLES, "PRIVATE", "2026-11", 5)).isInstanceOf(BusinessException.class);
        when(menus.selectVisibleMenusByUserId(7L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.summary(7L, ROLES, "B")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.ranking(7L, ROLES, "B", "2026-11", 5)).isInstanceOf(BusinessException.class);
        verify(buildings, never()).checkAccess(7L, ROLES, "B");
    }

    @Test
    void ranksCurrentMonthObservedTimeWithStableTiesScopeAndCoverageEvidenceAndReflectsCorrections() {
        for (String id : List.of("a", "b", "c", "missing", "invalid")) device(id, "B", "INDOOR");
        device("other", "C", "INDOOR");
        day("a", "B", 1, "2026-11-01", 3_600_000, 7_200_000);
        day("a", "B", 1, "2026-10-31", 80_000_000, 80_000_000);
        day("a", "B", 2, "2026-11-01", 60_000_000, 60_000_000);
        day("a", "C", 1, "2026-11-01", 70_000_000, 70_000_000);
        day("b", "B", 1, "2026-11-01", 3_600_000, 5_000_000);
        day("c", "B", 1, "2026-11-01", 0, 5_000_000);
        day("invalid", "B", 1, "2026-11-01", 7_000_000, 6_000_000);
        var result = service.ranking(7L, ROLES, "B", "2026-11", 2);
        assertThat(result.items()).extracting(DaikinBuildingMonitoringDtos.RuntimeRank::equipmentId).containsExactly("a", "b");
        assertThat(result.rankedDevices()).isEqualTo(3); assertThat(result.totalDevices()).isEqualTo(5);
        assertThat(result.excludedReasons()).containsEntry("NO_COVERAGE", 1L).containsEntry("INVALID_RUNTIME", 1L);
        assertThat(result.statisticsZone()).isEqualTo("Asia/Shanghai");
        assertThat(result.periodStart()).isEqualTo(Instant.parse("2026-10-31T16:00:00Z").toEpochMilli());
        assertThat(result.items().getFirst().hours()).isEqualByComparingTo("1");
        assertThat(result.items().getFirst().coveredHours()).isEqualByComparingTo("2");
        assertThat(result.items().getFirst().coveragePercentage()).isEqualByComparingTo("5.71");
        assertThat(result.items().getFirst().spaceName()).isEqualTo("实验室");
        assertThat(result.items().getFirst().floorName()).isEqualTo("三楼");
        assertThat(result.items().getFirst().coverageStatus()).isEqualTo("PARTIAL");
        jdbc.update("UPDATE biz_daikin_observed_runtime_day SET on_ms=1800000 WHERE identity_id='i-a' AND building_id='B' AND mapping_version=1 AND day_start_ms=?", result.periodStart());
        result = service.ranking(7L, ROLES, "B", "2026-11", 5);
        assertThat(result.items()).extracting(DaikinBuildingMonitoringDtos.RuntimeRank::equipmentId).containsExactly("b", "a", "c");
        assertThat(result.items().get(1).hours()).isEqualByComparingTo("0.5");
        assertThat(result.items().get(1).percentage()).isEqualByComparingTo("50");
        assertThat(result.items().get(2).hours()).isZero();
        assertThat(result.items().get(2).coveredHours()).isEqualByComparingTo("1.3889");
        assertThat(result.items().get(2).coveragePercentage()).isEqualByComparingTo("3.97");
    }

    @Test
    void keepsTimeZoneMonthBoundaryAndDoesNotGuessMissingOrCyclicLocations() {
        device("a", "B", "INDOOR");
        day("a", "B", 1, "2026-10-31", 60_000, 120_000);
        var boundary = serviceAt(Instant.parse("2026-10-31T16:00:00Z").toEpochMilli());
        assertThat(boundary.ranking(7L, ROLES, "B", "2026-11", 5).items()).isEmpty();
        assertThat(boundary.ranking(7L, ROLES, "B", "2026-10", 5).items()).hasSize(1);
        jdbc.update("UPDATE biz_space SET parent_space_id='room',space_type='ROOM' WHERE space_id='floor'");
        assertThat(boundary.ranking(7L, ROLES, "B", "2026-10", 5).items().getFirst().floorName()).isNull();
        jdbc.update("UPDATE biz_equipment SET space_id='nonexistent' WHERE equip_id='a'");
        assertThat(boundary.ranking(7L, ROLES, "B", "2026-10", 5).items().getFirst().spaceName()).isNull();
    }

    @Test
    void rejectsFutureOutOfRetentionMalformedMonthAndUnboundedLimitAndReturnsRealEmptyScope() {
        for (String month : List.of("2026-12", "2025-01", "2026-1", "2026-13", "garbage")) {
            assertThatThrownBy(() -> service.ranking(7L, ROLES, "B", month, 5)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service.ranking(7L, ROLES, "B", "2026-11", 101)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.ranking(7L, ROLES, "B", "2026-11", 0)).isInstanceOf(BusinessException.class);
        var summary = service.summary(7L, ROLES, "B");
        assertThat(summary.total()).isZero(); assertThat(summary.modes().counts()).containsEntry("cooling", 0L);
        assertThat(service.ranking(7L, ROLES, "B", "2026-11", 5).items()).isEmpty();
    }

    @Test
    void decodedTestObservationsUseNormalPersistenceBeforeSummaryAndRuntimeQueries() throws Exception {
        device("source-test", "B", "INDOOR");
        var persistence = new DaikinMonitoringStateService(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())));
        var target = new DaikinMonitoringStateService.Target("s-source-test", "p-source-test", "i-source-test",
                "source-test", "B", "room", "group", 1);
        var key = new DaikinDeviceKey("s-source-test", "site", "controller", DaikinDeviceKey.Kind.INDOOR, "source-test");
        var mapper = new ObjectMapper();
        var decoder = new DaikinDevicePageDecoder(DaikinDevicePageDecoder.FieldPolicy.unconfirmed());
        var on = mapper.readTree("{\"onOff\":\"on\",\"mode\":\"cooling\",\"fanSpeed\":\"low\"}");
        var controller = mapper.readTree("{\"isConnectionUp\":true}");
        persistence.observe(target, 1, decoder.decodeStateSnapshot(key, "test-vendor-id", on, controller, Instant.ofEpochMilli(NOW - 60_000)));
        assertThat(service.summary(7L, ROLES, "B").on()).isEqualTo(1);
        var off = mapper.readTree("{\"onOff\":\"off\",\"mode\":\"cooling\",\"fanSpeed\":\"low\"}");
        var observation = decoder.decodeStateSnapshot(key, "test-vendor-id", off, controller, Instant.ofEpochMilli(NOW));
        persistence.observe(target, 2, observation);
        persistence.observe(target, 2, observation);
        assertThat(service.summary(7L, ROLES, "B").off()).isEqualTo(1);
        var item = service.ranking(7L, ROLES, "B", "2026-11", 5).items().getFirst();
        assertThat(item.onMillis()).isEqualTo(60_000);
        assertThat(item.coveredMillis()).isEqualTo(60_000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_state_event WHERE field_name='onOff'", Long.class)).isEqualTo(1);
    }

    private DaikinBuildingMonitoringService serviceAt(long now) {
        var access = new DaikinMonitoringQueryService(jdbc, buildings, menus, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));
        return new DaikinBuildingMonitoringService(jdbc, access, new DaikinMonitoringTargets(jdbc), Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));
    }

    private void device(String id, String building, String kind) {
        jdbc.update("INSERT INTO biz_equipment VALUES (?,?, 'room','group','product',?, ?,0)", id, building, "AC-" + id, "空调" + id);
        jdbc.update("INSERT INTO biz_device_identity VALUES (?,?,?,'DAIKIN_UNIT',1,?,'DAIKIN_INDOOR_V2')", "i-" + id, id, building, id);
        jdbc.update("INSERT INTO biz_pending_device VALUES (?,?,'BOUND')", "p-" + id, "i-" + id);
        jdbc.update("INSERT INTO biz_daikin_directory VALUES (?,?,'site','controller',?,?)", "p-" + id, "s-" + id, kind, id);
        jdbc.update("INSERT INTO biz_daikin_project_mapping VALUES (?,'site',?,1)", "s-" + id, building);
        jdbc.update("INSERT INTO biz_daikin_monitoring_target VALUES (?,?,?,?,?,'room','group',1,1,?,NULL,NULL)",
                "i-" + id, "s-" + id, "p-" + id, id, building, NOW - 500_000);
    }

    private void state(String id, String field, String value, String status, long at) {
        jdbc.update("""
                INSERT INTO biz_daikin_current_state(identity_id,field_name,source_id,pending_id,equipment_id,building_id,
                  space_id,system_group_id,mapping_version,normalized_value,field_status,last_valid_at_ms,last_attempt_at_ms,
                  last_attempt_building_id,last_attempt_mapping_version,last_round_id)
                VALUES (?,?,?,?,?,'B','room','group',1,?,?,?,?, 'B',1,1)
                """, "i-" + id, field, "s-" + id, "p-" + id, id, value, status, at, at);
    }

    private void day(String id, String building, int mapping, String date, long on, long covered) {
        long start = java.time.LocalDate.parse(date).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        jdbc.update("INSERT INTO biz_daikin_observed_runtime_day VALUES (?,?,?,?,?,?)", "i-" + id, building, mapping, start, on, covered);
    }
}
