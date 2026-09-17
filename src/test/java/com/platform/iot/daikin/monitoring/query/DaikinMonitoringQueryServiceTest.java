package com.platform.iot.daikin.monitoring.query;

import com.platform.framework.exception.BusinessException;
import com.platform.system.mapper.SysMenuMapper;
import com.platform.system.model.entity.SysMenu;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DaikinMonitoringQueryServiceTest {
    private static final long NOW = Instant.parse("2026-09-16T03:00:00Z").toEpochMilli();
    private static final Set<String> OPS = Set.of("BUILDING_OWNER");
    private JdbcTemplate jdbc;
    private BuildingScopeService buildings;
    private SysMenuMapper menus;
    private DaikinMonitoringQueryService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:daikin-monitoring-query-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("daikin-monitoring-state-test.sql"))
                .execute(dataSource);
        jdbc.execute("""
                CREATE TABLE biz_equipment(
                  equip_id VARCHAR(32) PRIMARY KEY,building_id VARCHAR(32) NOT NULL,
                  space_id VARCHAR(32),system_group_id VARCHAR(32),del_flag TINYINT NOT NULL,
                  equip_code VARCHAR(50),equip_name VARCHAR(100))
                """);
        jdbc.execute("""
                CREATE TABLE biz_device_identity(
                  identity_id VARCHAR(32) PRIMARY KEY,equip_id VARCHAR(32) NOT NULL,
                  building_id VARCHAR(32) NOT NULL,identity_type VARCHAR(20) NOT NULL,status TINYINT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE biz_daikin_directory(
                  pending_id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(200) NOT NULL,device_kind VARCHAR(10) NOT NULL,
                  site_id VARCHAR(200))
                """);
        jdbc.execute("CREATE TABLE biz_pending_device(pending_id VARCHAR(64),bound_identity_id VARCHAR(32),status VARCHAR(20))");
        jdbc.execute("CREATE TABLE biz_daikin_project_mapping(source_id VARCHAR(200),site_id VARCHAR(200),building_id VARCHAR(32))");
        buildings = mock(BuildingScopeService.class);
        menus = mock(SysMenuMapper.class);
        SysMenu menu = new SysMenu();
        menu.setMenuType("C");
        menu.setPath("/operations/realtime/hvac");
        when(menus.selectVisibleMenusByUserId(7L)).thenReturn(List.of(menu));
        service = new DaikinMonitoringQueryService(jdbc, buildings, menus,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    void searchesBeforePaginationWithinBuildingAndTreatsWildcardsLiterally() {
        insertTarget("identity-A1", "equipment-A1", "BLD-A", "source-A", 1);
        insertTarget("identity-A2", "equipment-A2", "BLD-A", "source-A", 1);
        insertTarget("identity-B", "equipment-B", "BLD-B", "source-B", 1);
        jdbc.update("UPDATE biz_equipment SET equip_name='Room 301' WHERE equip_id IN ('equipment-A2','equipment-B')");
        var result = service.devices(7L, OPS, "BLD-A", 1, 1, null, null, " 301 ");
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).extracting(DaikinMonitoringQueryDtos.DeviceListItem::equipmentId)
                .containsExactly("equipment-A2");
        String code = jdbc.queryForObject("SELECT equip_code FROM biz_equipment WHERE equip_id='equipment-A2'", String.class);
        assertThat(service.devices(7L, OPS, "BLD-A", 1, 20, null, null, code).total()).isEqualTo(1);
        assertThat(service.devices(7L, OPS, "BLD-A", 1, 20, null, null, "%").total()).isZero();
        assertThat(service.devices(7L, OPS, "BLD-A", 1, 20, null, null, " ").total()).isEqualTo(2);
    }

    @Test
    void alarmGrantOnlyAllowsItsOwnListAndStillChecksBuildingScope() {
        SysMenu alarm = new SysMenu();
        alarm.setMenuType("C");
        alarm.setPath("/operations/alarms/liveAlarms");
        when(menus.selectVisibleMenusByUserId(7L)).thenReturn(List.of(alarm));
        assertThat(service.currentExceptions(7L, OPS, "BLD-A", null, 50).items()).isEmpty();
        verify(buildings).checkAccess(7L, OPS, "BLD-A");
        assertThatThrownBy(() -> service.exceptionHistory(7L, OPS, "BLD-A", null, 50)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.devices(7L, OPS, "BLD-A", 1, 20, null, null)).isInstanceOf(BusinessException.class);
        alarm.setPath("/operations/alarms/historyAlarms");
        assertThat(service.exceptionHistory(7L, OPS, "BLD-A", null, 50).items()).isEmpty();
        assertThatThrownBy(() -> service.currentExceptions(7L, OPS, "BLD-A", null, 50)).isInstanceOf(BusinessException.class);
    }

    @Test
    void requiresExactHvacLeafAndBuildingScopeBeforeQuerying() {
        insertTarget("identity-A", "equipment-A", "BLD-A", "source-A", 1);
        when(menus.selectVisibleMenusByUserId(7L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.devices(7L, OPS, "BLD-A", 1, 20, null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo("DAIKIN_MONITORING_FORBIDDEN"));
        verifyNoInteractions(buildings);

        SysMenu unrelated = new SysMenu();
        unrelated.setMenuType("C");
        unrelated.setPath("/operations/realtime/power");
        when(menus.selectVisibleMenusByUserId(7L)).thenReturn(List.of(unrelated));
        assertThatThrownBy(() -> service.requireEquipment(7L, OPS, "equipment-A"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(buildings);
    }

    @Test
    void deviceListIsBuildingIsolatedAndBoundedToOneHundred() {
        insertTarget("identity-A1", "equipment-A1", "BLD-A", "source-A", 1);
        insertTarget("identity-A2", "equipment-A2", "BLD-A", "source-A", 1);
        insertTarget("identity-A3", "equipment-A3", "BLD-A", "source-A", 1);
        insertTarget("identity-B", "equipment-B", "BLD-B", "source-B", 1);
        insertCurrent("identity-A1", "onOff", "BLD-A", 1, "BLD-A", 1, "\"on\"", "on");
        jdbc.update("UPDATE biz_daikin_current_state SET last_valid_at_ms=? WHERE identity_id='identity-A1'",
                NOW - 400_000);
        insertException("VENDOR_EQUIPMENT", "DEVICE", "source-A", "identity-A1",
                "equipment-A1", "BLD-B", NOW - 1_000, null);

        var first = service.devices(7L, OPS, "BLD-A", 1, 2, null, null);
        var second = service.devices(7L, OPS, "BLD-A", 2, 2, null, null);
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.items()).extracting(DaikinMonitoringQueryDtos.DeviceListItem::equipmentId)
                .containsExactly("equipment-A1", "equipment-A2");
        assertThat(second.items()).extracting(DaikinMonitoringQueryDtos.DeviceListItem::equipmentId)
                .containsExactly("equipment-A3");
        assertThat(first.items()).allSatisfy(item -> {
            assertThat(item.deviceKind()).isEqualTo("INDOOR");
            assertThat(item.active()).isTrue();
        });
        assertThat(first.items().get(0)).satisfies(item -> {
            assertThat(item.onOff().value()).isEqualTo("on");
            assertThat(item.onOff().status()).isEqualTo("MISSING");
            assertThat(item.onOff().lastValidAt()).isEqualTo(NOW - 400_000);
            assertThat(item.onOff().stale()).isTrue();
            assertThat(item.hasActiveException()).isFalse();
        });
        assertThat(service.devices(7L, OPS, "BLD-A", 1, 20, "other-space", null).items())
                .isEmpty();
        assertThat(service.devices(7L, OPS, "BLD-A", 1, 20, null, "OUTDOOR").items())
                .isEmpty();
        assertThatThrownBy(() -> service.devices(7L, OPS, "BLD-A", 1, 101, null, null))
                .isInstanceOf(BusinessException.class);
        verify(buildings, times(5)).checkAccess(7L, OPS, "BLD-A");
    }

    @Test
    void equipmentQueryRechecksCurrentTargetBuildingScope() {
        insertTarget("identity-A", "equipment-A", "BLD-B", "source-A", 1);
        doThrow(new BusinessException(403, "FORBIDDEN", "无权访问建筑"))
                .when(buildings).checkAccess(7L, OPS, "BLD-B");

        assertThatThrownBy(() -> service.current(7L, OPS, "equipment-A"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(403));
        verify(buildings).checkAccess(7L, OPS, "BLD-B");
    }

    @Test
    void currentExcludesTemperatureAndHidesValueOwnedByPreviousBuilding() {
        insertTarget("identity-A", "equipment-A", "BLD-B", "source-A", 2);
        jdbc.update("UPDATE biz_daikin_monitoring_target SET building_id='BLD-A' WHERE identity_id='identity-A'");
        insertCurrent("identity-A", "onOff", "BLD-A", 1, "BLD-B", 2, "\"on\"", "on");
        assertThat(service.current(7L, OPS, "equipment-A").lastValidAt()).isNull();
        insertCurrent("identity-A", "roomTemp", "BLD-B", 2, "BLD-B", 2, "24.5", "24.5");

        var result = service.current(7L, OPS, "equipment-A");

        assertThat(result.buildingId()).isEqualTo("BLD-B");
        assertThat(result.lastValidAt()).isEqualTo(NOW - 2_000);
        assertThat(result.fields()).hasSize(1);
        assertThat(result.fields().get(0)).satisfies(field -> {
            assertThat(field.fieldName()).isEqualTo("onOff");
            assertThat(field.valueVisible()).isFalse();
            assertThat(field.rawJson()).isNull();
            assertThat(field.normalizedValue()).isNull();
            assertThat(field.lastValidAt()).isNull();
            assertThat(field.lastAttemptMappingVersion()).isEqualTo(2);
        });
        verify(buildings, times(2)).checkAccess(7L, OPS, "BLD-B");
    }

    @Test
    void deactivatedTargetRemainsQueryableAndReportsInactive() {
        insertTarget("identity-A", "equipment-A", "BLD-B", "source-A", 2);
        jdbc.update("UPDATE biz_daikin_monitoring_target SET active=0 WHERE identity_id='identity-A'");

        assertThat(service.requireEquipment(7L, OPS, "equipment-A")).isEqualTo("BLD-B");
        assertThat(service.current(7L, OPS, "equipment-A").active()).isFalse();
    }

    @Test
    void stateEventCursorKeepsSnapshotBuildingAndOneYearBoundary() {
        insertTarget("identity-A", "equipment-A", "BLD-B", "source-A", 2);
        insertEvent("identity-A", "BLD-B", NOW - 1_000, "on", 2);
        insertEvent("identity-A", "BLD-B", NOW - 2_000, "off", 2);
        insertEvent("identity-A", "BLD-A", NOW - 500, "other-building", 1);
        insertEvent("identity-A", "BLD-B", NOW - java.time.Duration.ofDays(365).toMillis() - 1,
                "expired", 2);

        var first = service.stateEvents(7L, OPS, "equipment-A", null, 1);
        var second = service.stateEvents(7L, OPS, "equipment-A", first.nextCursor(), 1);

        assertThat(first.items()).extracting(DaikinMonitoringQueryDtos.StateEventView::afterNormalizedValue)
                .containsExactly("on");
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(second.items()).extracting(DaikinMonitoringQueryDtos.StateEventView::afterNormalizedValue)
                .containsExactly("off");
        assertThat(second.nextCursor()).isNull();
        assertThatThrownBy(() -> service.stateEvents(7L, OPS, "equipment-A", "bad", 10))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo("DAIKIN_MONITORING_INVALID_CURSOR"));
    }

    @Test
    void sourceExceptionAppearsOncePerRelatedBuildingWithoutCrossBuildingImpactData() {
        insertTarget("identity-B", "equipment-B", "BLD-B", "source-shared", 1);
        insertTarget("identity-C", "equipment-C", "BLD-C", "source-shared", 1);
        insertTarget("identity-D", "equipment-D", "BLD-D", "source-other", 1);
        insertException("SOURCE_FETCH", "SOURCE", "source-shared", null, null,
                null, NOW - 10_000, null);
        insertException("VENDOR_EQUIPMENT", "DEVICE", "source-shared", "identity-B",
                "equipment-B", "BLD-B", NOW - 5_000, null);
        insertException("SOURCE_FETCH", "SOURCE", "source-other", null, null,
                null, NOW - 1_000, null);

        var visible = service.currentExceptions(7L, OPS, "BLD-B", null, 100);

        assertThat(visible.items()).hasSize(2);
        assertThat(visible.items()).extracting(DaikinMonitoringQueryDtos.ExceptionView::type)
                .containsExactlyInAnyOrder("SOURCE_FETCH", "VENDOR_EQUIPMENT");
        assertThat(visible.items().stream().filter(item -> "SOURCE".equals(item.scopeType())).toList())
                .singleElement().satisfies(item -> {
                    assertThat(item.buildingId()).isEqualTo("BLD-B");
                    assertThat(item.identityId()).isNull();
                    assertThat(item.equipmentId()).isNull();
                });
        assertThat(service.currentExceptions(7L, OPS, "BLD-A", null, 100).items()).isEmpty();
    }

    @Test
    void exceptionHistoryUsesRecoveryTimeForOneYearRetention() {
        insertTarget("identity-B", "equipment-B", "BLD-B", "source-B", 1);
        insertException("DEVICE_STALE", "DEVICE", "source-B", "identity-B", "equipment-B",
                "BLD-B", NOW - 10_000, NOW - 1_000);
        insertException("DEVICE_STALE", "DEVICE", "source-B", "identity-B", "equipment-B",
                "BLD-B", NOW - java.time.Duration.ofDays(400).toMillis(),
                NOW - java.time.Duration.ofDays(365).toMillis() - 1);

        var history = service.exceptionHistory(7L, OPS, "BLD-B", null, 100);

        assertThat(history.items()).singleElement()
                .satisfies(item -> assertThat(item.recoveredAt()).isEqualTo(NOW - 1_000));
    }

    private void insertTarget(String identity, String equipment, String building, String source, int mapping) {
        String pending = "pending-" + identity;
        jdbc.update("INSERT INTO biz_equipment VALUES (?,?,'space','system',0,?,?)", equipment, building, equipment, "Room unit");
        jdbc.update("INSERT INTO biz_device_identity VALUES (?,?,?,'DAIKIN_UNIT',1)",
                identity, equipment, building);
        jdbc.update("INSERT INTO biz_daikin_directory(pending_id,source_id,device_kind) VALUES (?,?,'INDOOR')", pending, source);
        jdbc.update("""
                INSERT INTO biz_daikin_monitoring_target
                  (identity_id,source_id,pending_id,equipment_id,building_id,space_id,system_group_id,
                   mapping_version,active,first_planned_at_ms,last_valid_at_ms,last_round_id)
                VALUES (?,?,?,?,?,'space','system',?,1,?,?,1)
                """, identity, source, pending, equipment, building, mapping,
                NOW - 60_000, NOW - 1_000);
    }

    private void insertCurrent(String identity, String field, String valueBuilding, int valueMapping,
                               String attemptBuilding, int attemptMapping, String raw, String normalized) {
        jdbc.update("""
                INSERT INTO biz_daikin_current_state
                  (identity_id,field_name,source_id,pending_id,equipment_id,building_id,space_id,system_group_id,
                   mapping_version,raw_json,normalized_value,field_status,last_valid_at_ms,last_attempt_raw_json,
                   last_attempt_at_ms,last_attempt_building_id,last_attempt_space_id,last_attempt_system_group_id,
                   last_attempt_mapping_version,last_round_id)
                VALUES (?,?, 'source-A','pending','equipment-A',?,'space','system',?,?,?,'MISSING',?,NULL,
                        ? ,?,'space','system',?,2)
                """, identity, field, valueBuilding, valueMapping, raw, normalized, NOW - 2_000,
                NOW - 1_000, attemptBuilding, attemptMapping);
    }

    private void insertEvent(String identity, String building, long observedAt, String after, int mapping) {
        jdbc.update("""
                INSERT INTO biz_daikin_state_event
                  (identity_id,field_name,round_id,source_id,pending_id,equipment_id,building_id,space_id,
                   system_group_id,mapping_version,before_raw_json,before_normalized_value,after_raw_json,
                   after_normalized_value,previous_observed_at_ms,observed_at_ms,after_gap)
                VALUES (?,'onOff',?,'source-A','pending','equipment-A',?,'space','system',?,
                        '"before"','before','"after"',?,?,?,0)
                """, identity, observedAt, building, mapping, after, observedAt - 60_000, observedAt);
    }

    private void insertException(String type, String scope, String source, String identity,
                                 String equipment, String building, long detectedAt, Long recoveredAt) {
        jdbc.update("""
                INSERT INTO biz_daikin_exception_instance
                  (active_key,exception_type,scope_type,source_id,identity_id,field_name,pending_id,equipment_id,
                   building_id,space_id,system_group_id,mapping_version,first_detected_at_ms,last_detected_at_ms,
                   recovered_at_ms,last_round_id)
                VALUES (?,?,?,?,?,NULL,NULL,?,?,NULL,NULL,NULL,?,?,?,1)
                """, recoveredAt == null ? type + source + String.valueOf(identity) : null, type, scope,
                source, identity, equipment, building, detectedAt, detectedAt, recoveredAt);
    }
}
