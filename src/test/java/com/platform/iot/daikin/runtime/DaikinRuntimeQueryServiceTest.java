package com.platform.iot.daikin.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.monitoring.DaikinMonitoringTargets;
import com.platform.iot.daikin.monitoring.query.DaikinMonitoringQueryService;
import com.platform.system.mapper.SysMenuMapper;
import com.platform.system.model.entity.SysMenu;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DaikinRuntimeQueryServiceTest {
    private static final Set<String> OPS = Set.of("BUILDING_OWNER");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZONE);
    private JdbcTemplate jdbc;
    private SysMenuMapper menus;
    private BuildingScopeService buildings;
    private DaikinRuntimeQueryService query;
    private DaikinRuntimeStore store;
    private DaikinMonitoringTargets.Target target;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:runtime-query-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("daikin-runtime-test.sql"),
                new ClassPathResource("daikin-monitoring-state-test.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE biz_equipment(equip_id VARCHAR(32),building_id VARCHAR(32),del_flag INT)");
        jdbc.execute("CREATE TABLE biz_device_identity(identity_id VARCHAR(32),equip_id VARCHAR(32),building_id VARCHAR(32),identity_type VARCHAR(32),status INT)");
        jdbc.execute("CREATE TABLE biz_pending_device(pending_id VARCHAR(32),bound_identity_id VARCHAR(32),status VARCHAR(32))");
        jdbc.execute("CREATE TABLE biz_daikin_directory(pending_id VARCHAR(32),source_id VARCHAR(200),site_id VARCHAR(32),device_kind VARCHAR(32))");
        jdbc.execute("CREATE TABLE biz_daikin_project_mapping(source_id VARCHAR(200),site_id VARCHAR(32),building_id VARCHAR(32),mapping_version INT)");
        jdbc.update("INSERT INTO biz_equipment VALUES ('equipment','building',0)");
        jdbc.update("INSERT INTO biz_device_identity VALUES ('identity','equipment','building','DAIKIN_UNIT',0)");
        jdbc.update("INSERT INTO biz_pending_device VALUES ('pending','identity','BOUND')");
        jdbc.update("INSERT INTO biz_daikin_directory VALUES ('pending','source','site','INDOOR')");
        jdbc.update("INSERT INTO biz_daikin_project_mapping VALUES ('source','site','building',1)");
        menus = mock(SysMenuMapper.class);
        buildings = mock(BuildingScopeService.class);
        var menu = new SysMenu();
        menu.setMenuType("C");
        menu.setPath("/operations/realtime/hvac");
        when(menus.selectVisibleMenusByUserId(7L)).thenReturn(List.of(menu));
        var access = new DaikinMonitoringQueryService(jdbc, buildings, menus);
        query = new DaikinRuntimeQueryService(jdbc, access, new ObjectMapper(), CLOCK);
        store = new DaikinRuntimeStore(jdbc, new ObjectMapper());
        target = new DaikinMonitoringTargets.Target(new DaikinDeviceKey("source", "site", "controller", DaikinDeviceKey.Kind.INDOOR, "001"),
                "pending", "identity", "factory-identity", "DAIKIN_INDOOR", "equipment", "code", "building", "room", "group", 1);
        save(LocalDate.of(2026, 9, 15), "0");
    }

    @Test
    void checksMenuAndBuildingEvenForDisabledDeviceAndNeedsNoMinuteSnapshot() {
        var page = query.values(7L, OPS, "equipment", "DAY", null, 50);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().metrics().get("totalRuntime")).isEqualByComparingTo("0");
        verify(buildings).checkAccess(7L, OPS, "building");
        when(menus.selectVisibleMenusByUserId(7L)).thenReturn(List.of());
        assertThatThrownBy(() -> query.values(7L, OPS, "equipment", "DAY", null, 50)).isInstanceOf(BusinessException.class);
    }

    @Test
    void deniedBuildingAlsoDeniesRevisionEndpoint() {
        String id = query.values(7L, OPS, "equipment", "DAY", null, 50).items().getFirst().valueId();
        doThrow(new BusinessException(403, "FORBIDDEN", "禁止访问")).when(buildings).checkAccess(7L, OPS, "building");
        assertThatThrownBy(() -> query.values(7L, OPS, "equipment", "DAY", null, 50)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.revisions(7L, OPS, "equipment", id, 0, 50)).isInstanceOf(BusinessException.class);
    }

    @Test
    void runtimeSourceExceptionIsVisibleBeforeMinuteMonitoringStarts() {
        jdbc.update("""
                INSERT INTO biz_daikin_exception_instance(active_key,exception_type,scope_type,source_id,
                  first_detected_at_ms,last_detected_at_ms) VALUES ('runtime','RUNTIME_FETCH','SOURCE','source',?,?)
                """, CLOCK.millis(), CLOCK.millis());
        var access = new DaikinMonitoringQueryService(jdbc, buildings, menus);
        assertThat(access.currentExceptions(7L, OPS, "building", null, 50).items()).hasSize(1);
        assertThat(access.currentExceptions(7L, OPS, "other", null, 50).items()).isEmpty();
    }

    @Test
    void movingBuildingOrChangingMappingCannotExposeOldSnapshots() {
        String id = query.values(7L, OPS, "equipment", "DAY", null, 50).items().getFirst().valueId();
        jdbc.update("UPDATE biz_daikin_project_mapping SET mapping_version=2");
        assertThat(query.values(7L, OPS, "equipment", "DAY", null, 50).items()).isEmpty();
        assertThat(query.revisions(7L, OPS, "equipment", id, 0, 50).items()).isEmpty();
        jdbc.update("UPDATE biz_daikin_project_mapping SET mapping_version=1,building_id='other'");
        jdbc.update("UPDATE biz_equipment SET building_id='other'");
        jdbc.update("UPDATE biz_device_identity SET building_id='other'");
        assertThat(query.values(7L, OPS, "equipment", "DAY", null, 50).items()).isEmpty();
        assertThat(query.revisions(7L, OPS, "equipment", id, 0, 50).items()).isEmpty();
    }

    @Test
    void historyWindowAndBoundedCursorDoNotMixGranularitiesOrSumRevisions() {
        save(LocalDate.of(2024, 1, 1), "900");
        save(LocalDate.of(2026, 9, 14), "1");
        save(LocalDate.of(2026, 9, 15), "2");
        var first = query.values(7L, OPS, "equipment", "DAY", null, 1);
        var second = query.values(7L, OPS, "equipment", "DAY", first.nextCursor(), 1);
        assertThat(first.items()).hasSize(1);
        assertThat(second.items()).hasSize(1);
        assertThat(second.nextCursor()).isNull();
        assertThat(query.values(7L, OPS, "equipment", "MONTH", null, 50).items()).isEmpty();
        String revisedId = jdbc.queryForObject("SELECT value_id FROM biz_daikin_runtime_value WHERE revision_no=2", String.class);
        var revisions = query.revisions(7L, OPS, "equipment", revisedId, 0, 1);
        assertThat(revisions.nextCursor()).isEqualTo(1);
        assertThat(revisions.items().getFirst().metrics().get("totalRuntime")).isEqualByComparingTo("0");
        assertThat(query.revisions(7L, OPS, "equipment", revisedId, 1, 1).items().getFirst().metrics().get("totalRuntime"))
                .isEqualByComparingTo("2");
        assertThatThrownBy(() -> query.values(7L, OPS, "equipment", "DAY", null, 101)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.values(7L, OPS, "equipment", "DAY", "invalid", 50)).isInstanceOf(BusinessException.class);
    }

    private void save(LocalDate date, String metric) {
        store.save(target, new DaikinRuntimePeriod(DaikinRuntimePeriod.Granularity.DAY, date, ZONE), "hour",
                new DaikinRuntimeClientProvider.Reading(target.key(), DaikinRuntimeClientProvider.Status.PRESENT,
                        Map.of("totalRuntime", new BigDecimal(metric)), true), "PRESENT", CLOCK.millis());
    }
}
