package com.platform.iot.comfort;

import com.platform.framework.exception.BusinessException;
import com.platform.iot.daikin.monitoring.DaikinTemperatureQueryService;
import com.platform.iot.daikin.monitoring.DaikinTemperatureQueryService.Current;
import com.platform.iot.daikin.monitoring.DaikinTemperatureQueryService.History;
import com.platform.iot.daikin.monitoring.DaikinTemperatureQueryService.Reading;
import com.platform.system.mapper.SysMenuMapper;
import com.platform.system.model.entity.SysMenu;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ComfortQueryServiceTest {
    private static final long USER = 31L;
    private static final Set<String> ROLES = Set.of("BUILDING_OWNER");
    private static final Instant DAY_START = Instant.parse("2026-11-18T16:00:00Z");
    private static final Instant BOUND_AT = DAY_START.plusSeconds(600);
    private static final Instant NOW = Instant.parse("2026-11-19T01:30:00Z");

    private final BuildingScopeService buildings = mock(BuildingScopeService.class);
    private final SysMenuMapper menus = mock(SysMenuMapper.class);
    private final DaikinTemperatureQueryService temperature = mock(DaikinTemperatureQueryService.class);
    private ComfortQueryService service;

    @BeforeEach
    void setUp() {
        var mysql = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:comfort-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        mysql.execute("""
                CREATE TABLE biz_space(space_id VARCHAR(32),space_name VARCHAR(100),building_id VARCHAR(32),
                    parent_space_id VARCHAR(32),space_type VARCHAR(32),del_flag INT,space_code VARCHAR(50),floor_level INT)
                """);
        mysql.execute("""
                CREATE TABLE biz_equipment(equip_id VARCHAR(32),equip_name VARCHAR(100),building_id VARCHAR(32),
                    space_id VARCHAR(32),del_flag INT)
                """);
        mysql.execute("""
                CREATE TABLE biz_device_identity(identity_id VARCHAR(32),equip_id VARCHAR(32),building_id VARCHAR(32),
                    status INT,identity_type VARCHAR(32),expected_profile_code VARCHAR(32))
                """);
        mysql.execute("CREATE TABLE biz_room_sensor_source(identity_id VARCHAR(32),protocol VARCHAR(32),enabled INT)");
        mysql.execute("""
                CREATE TABLE biz_daikin_monitoring_target(identity_id VARCHAR(32),equipment_id VARCHAR(32),
                    building_id VARCHAR(32),space_id VARCHAR(32),active INT,pending_id VARCHAR(32),
                    source_id VARCHAR(32),first_planned_at_ms BIGINT,mapping_version INT)
                """);
        mysql.execute("CREATE TABLE biz_daikin_directory(pending_id VARCHAR(32),source_id VARCHAR(32),device_kind VARCHAR(32))");
        mysql.update("""
                INSERT INTO biz_space VALUES
                    ('floor','三层','B',NULL,'FLOOR',0,'F03',3),
                    ('room-314','B314','B','floor','ROOM',0,'B314',NULL),
                    ('room-313','B313','B','floor','ROOM',0,'B313',NULL),
                    ('wrong-profile','B311','B','floor','ROOM',0,'B311',NULL),
                    ('disabled-room','B312','B','floor','ROOM',0,'B312',NULL),
                    ('deleted-room','B315','B','floor','ROOM',1,'B315',NULL),
                    ('other-building','A309','A',NULL,'ROOM',0,'A309',NULL)
                """);
        attachSensor(mysql, "radar", "room-314", "MSR01_RADAR", "MSR01_RADAR", 1);
        attachSensor(mysql, "disabled", "disabled-room", "S4_DOOR", "S4_DOOR", 0);
        attachSensor(mysql, "wrong", "wrong-profile", "S4_DOOR", "MSR01_RADAR", 1);
        attachUnit(mysql, "indoor-1", "room-314", "room-314", "INDOOR");
        attachUnit(mysql, "indoor-2", "room-314", "room-314", "INDOOR");
        attachUnit(mysql, "indoor-3", "room-314", "room-314", "INDOOR");
        attachUnit(mysql, "outdoor", "room-314", "room-314", "OUTDOOR");
        attachUnit(mysql, "moved", "room-314", "room-313", "INDOOR");
        attachUnit(mysql, "room-313-unit", "room-313", "room-313", "INDOOR");

        var menu = new SysMenu();
        menu.setMenuType("C");
        menu.setPath("/operations/realtime/comfort");
        when(menus.selectVisibleMenusByUserId(USER)).thenReturn(List.of(menu));
        when(buildings.canAccess(USER, ROLES, "B")).thenReturn(true);
        doThrow(new BusinessException(403, "无权访问该建筑")).when(buildings).checkAccess(USER, ROLES, "A");
        service = new ComfortQueryService(mysql, buildings, menus, temperature,
                Set.of("room-313", "deleted-room", "other-building"), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void listsOnlyActiveEligibleRoomsUnderRequestedBuilding() {
        var rooms = service.spaces(USER, ROLES, "B");
        assertThat(rooms).extracting(ComfortQueryService.Space::spaceId).containsExactly("room-313", "room-314");
        assertThat(rooms.getLast().floorName()).isEqualTo("三层");
        verifyNoInteractions(temperature);
    }

    @Test
    void rejectsMissingMenuAndUnauthorizedBuildingBeforeReadingTemperature() {
        assertThatExceptionOfType(BusinessException.class).isThrownBy(() -> service.spaces(99L, ROLES, "B"))
                .satisfies(error -> assertThat(error.getCode()).isEqualTo(403));
        assertThatExceptionOfType(BusinessException.class).isThrownBy(() -> service.spaces(USER, ROLES, "A"))
                .satisfies(error -> assertThat(error.getCode()).isEqualTo(403));
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.overview(USER, ROLES, "other-building", null))
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo("COMFORT_SPACE_NOT_FOUND"));
        verifyNoInteractions(temperature);
    }

    @Test
    void averagesOnlyCurrentValidIndoorReadingsAndRejectsOtherSpacesOutdoorAndMovingUnits() {
        var earlier = NOW.minusSeconds(40);
        var latest = NOW.minusSeconds(10);
        when(temperature.current(USER, ROLES, "indoor-1", "roomTemp")).thenReturn(current(earlier, 22.0, false));
        when(temperature.current(USER, ROLES, "indoor-2", "roomTemp")).thenReturn(current(latest, 26.0, false));
        when(temperature.current(USER, ROLES, "indoor-3", "roomTemp")).thenReturn(current(NOW.minusSeconds(600), 50.0, true));
        var overview = service.overview(USER, ROLES, "room-314", null);
        assertThat(overview.temperatureSources()).extracting(ComfortQueryService.TemperatureSource::equipmentId)
                .containsExactly("indoor-1", "indoor-2", "indoor-3");
        assertThat(overview.temperatureSourceStatus()).isEqualTo("ROOM_AVERAGE");
        assertThat(overview.selectedSource()).isNull();
        assertThat(overview.indoorTemperature()).isEqualTo(new ComfortQueryService.TemperatureObservation(24.0, latest, false));
        assertThat(overview.lastValidIndoorTemperature()).isNull();
        for (String id : List.of("room-313-unit", "outdoor", "moved")) {
            assertThatExceptionOfType(BusinessException.class)
                    .isThrownBy(() -> service.trends(USER, ROLES, "room-314", id))
                    .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo("COMFORT_TEMPERATURE_SOURCE_NOT_FOUND"));
        }
    }

    @Test
    void leavesCurrentTemperatureEmptyWhenAllIndoorReadingsAreMissingOrInvalid() {
        when(temperature.current(USER, ROLES, "indoor-1", "roomTemp")).thenReturn(current(NOW, null, false));
        when(temperature.current(USER, ROLES, "indoor-2", "roomTemp")).thenReturn(current(NOW.plusSeconds(1), 20.0, false));
        when(temperature.current(USER, ROLES, "indoor-3", "roomTemp")).thenReturn(
                new Current("roomTemp", "point", "°C", new Reading(NOW.toEpochMilli(), 25.0, 0, null, false, false),
                        "MISSING", "PLATFORM_OBSERVED_AT"));
        var overview = service.overview(USER, ROLES, "room-314", null);
        assertThat(overview.indoorTemperature()).isNull();
        assertThat(overview.lastValidIndoorTemperature()).isEqualTo(
                new ComfortQueryService.TemperatureObservation(25.0, NOW, false));
    }

    @Test
    void exposesTheLastRoomAverageSeparatelyWhenNoIndoorUnitsAreFresh() {
        var last = NOW.minusSeconds(600);
        when(temperature.current(USER, ROLES, "indoor-1", "roomTemp")).thenReturn(current(last, 22.0, true));
        when(temperature.current(USER, ROLES, "indoor-2", "roomTemp")).thenReturn(current(last.minusSeconds(300), 26.0, true));
        when(temperature.current(USER, ROLES, "indoor-3", "roomTemp")).thenReturn(current(last.minusSeconds(301), 50.0, true));

        var overview = service.overview(USER, ROLES, "room-314", null);
        assertThat(overview.indoorTemperature()).isNull();
        assertThat(overview.lastValidIndoorTemperature()).isEqualTo(
                new ComfortQueryService.TemperatureObservation(24.0, last, true));
    }

    @Test
    void averagesOneLatestValidReadingPerUnitAndHourWithoutCarryingAcrossGaps() {
        var first = BOUND_AT.plusSeconds(20);
        var later = BOUND_AT.plusSeconds(100);
        var blocked = BOUND_AT.plusSeconds(200);
        var nextHour = DAY_START.plusSeconds(3700);
        long from = BOUND_AT.toEpochMilli();
        long end = NOW.toEpochMilli() + 1;
        when(temperature.history(USER, ROLES, "indoor-1", "roomTemp", from, end, null, 1000))
                .thenReturn(history(reading(first, 10.0), reading(later, 22.0), reading(blocked, null)));
        when(temperature.history(USER, ROLES, "indoor-2", "roomTemp", from, end, null, 1000))
                .thenReturn(history(reading(first, 26.0), reading(nextHour, 21.0)));
        when(temperature.history(USER, ROLES, "indoor-3", "roomTemp", from, end, null, 1000))
                .thenReturn(history(reading(blocked, null)));

        var trend = service.trends(USER, ROLES, "room-314", null);
        assertThat(trend.temperatureSourceStatus()).isEqualTo("ROOM_AVERAGE");
        assertThat(trend.hours()).hasSize(24);
        assertThat(trend.hours().getFirst().indoorTemperature())
                .isEqualTo(new ComfortQueryService.TemperatureObservation(24.0, later, false));
        assertThat(trend.hours().get(1).indoorTemperature().celsius()).isEqualTo(21.0);
        assertThat(trend.hours().get(2).indoorTemperature()).isNull();
        assertThat(trend.hours().getLast().indoorTemperature()).isNull();
    }

    private static Current current(Instant time, Double value, boolean stale) {
        return new Current("roomTemp", "point", "°C", new Reading(time.toEpochMilli(), value, 0, null, false, stale),
                "PRESENT", "PLATFORM_OBSERVED_AT");
    }

    private static History history(Reading... readings) {
        return new History("roomTemp", "point", "°C", List.of(readings), null, "PLATFORM_OBSERVED_AT");
    }

    private static Reading reading(Instant time, Double value) {
        return new Reading(time.toEpochMilli(), value, 0, null, false, true);
    }

    @Test
    void retainsCurrentTemperatureSourceTimeAndStaleness() {
        var observed = NOW.minusSeconds(400);
        when(temperature.current(USER, ROLES, "room-313-unit", "roomTemp")).thenReturn(
                new Current("roomTemp", "point-1", "°C",
                        new Reading(observed.toEpochMilli(), 24.3, 0, null, false, true), "PRESENT", "PLATFORM_OBSERVED_AT"));
        var overview = service.overview(USER, ROLES, "room-313", null);
        assertThat(overview.selectedSource().equipmentId()).isEqualTo("room-313-unit");
        assertThat(overview.pointId()).isEqualTo("point-1");
        assertThat(overview.indoorTemperature()).isEqualTo(
                new ComfortQueryService.TemperatureObservation(24.3, observed, true));
    }

    @Test
    void observationBeforeCurrentBindingRemainsMissing() {
        var old = new Reading(BOUND_AT.toEpochMilli() - 1, 24.3, 0, null, false, true);
        when(temperature.current(USER, ROLES, "indoor-1", "roomTemp"))
                .thenReturn(new Current("roomTemp", "point-1", "°C", old, "PRESENT", "PLATFORM_OBSERVED_AT"));
        assertThat(service.overview(USER, ROLES, "room-314", "indoor-1").indoorTemperature()).isNull();
    }

    @Test
    void fillsOnlyObservedBeijingHourSlotsAndRetainsBlockedTemperaturesAcrossPages() {
        var first = new Reading(BOUND_AT.toEpochMilli(), 23.0, 0, null, false, true);
        var latest = new Reading(BOUND_AT.plusSeconds(60).toEpochMilli(), 23.7, 0, null, false, true);
        var blocked = new Reading(DAY_START.plusSeconds(7200).toEpochMilli(), null, 2, null, true, true);
        long end = NOW.toEpochMilli() + 1;
        when(temperature.history(USER, ROLES, "indoor-1", "roomTemp", BOUND_AT.toEpochMilli(), end, null, 1000))
                .thenReturn(new History("roomTemp", "point-1", "°C", List.of(first), first.observedAt(), "PLATFORM_OBSERVED_AT"));
        when(temperature.history(USER, ROLES, "indoor-1", "roomTemp", BOUND_AT.toEpochMilli(), end, first.observedAt(), 1000))
                .thenReturn(new History("roomTemp", "point-1", "°C", List.of(latest, blocked), null, "PLATFORM_OBSERVED_AT"));

        var trends = service.trends(USER, ROLES, "room-314", "indoor-1");
        assertThat(trends.pointId()).isEqualTo("point-1");
        assertThat(trends.timeZone()).isEqualTo("Asia/Shanghai");
        assertThat(trends.hours()).hasSize(24);
        assertThat(trends.hours().getFirst().startsAt()).isEqualTo(DAY_START);
        assertThat(trends.hours().getFirst().indoorTemperature().celsius()).isEqualTo(23.7);
        assertThat(trends.hours().get(1).indoorTemperature()).isNull();
        assertThat(trends.hours().get(2).indoorTemperature().celsius()).isNull();
        assertThat(trends.hours().get(2).indoorTemperature().observedAt())
                .isEqualTo(Instant.ofEpochMilli(blocked.observedAt()));
        assertThat(trends.hours().getLast().indoorTemperature()).isNull();
    }

    private static void attachSensor(JdbcTemplate mysql, String id, String room, String protocol, String profile, int enabled) {
        mysql.update("INSERT INTO biz_equipment VALUES (?,?,'B',?,0)", id, id, room);
        mysql.update("INSERT INTO biz_device_identity VALUES (?,?,'B',1,'MAC',?)", id, id, profile);
        mysql.update("INSERT INTO biz_room_sensor_source VALUES (?,?,?)", id, protocol, enabled);
    }

    private static void attachUnit(JdbcTemplate mysql, String id, String room, String targetRoom, String kind) {
        mysql.update("INSERT INTO biz_equipment VALUES (?,?,'B',?,0)", id, id, room);
        mysql.update("INSERT INTO biz_device_identity VALUES (?,?,'B',1,'DAIKIN_UNIT',NULL)", id, id);
        mysql.update("INSERT INTO biz_daikin_monitoring_target VALUES (?,?,'B',?,1,?,'source',?,1)",
                id, id, targetRoom, id, BOUND_AT.toEpochMilli());
        mysql.update("INSERT INTO biz_daikin_directory VALUES (?,'source',?)", id, kind);
    }
}
