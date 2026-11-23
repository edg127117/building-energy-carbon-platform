package com.platform.iot.roomsensor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.TdengineProperties;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.communication.CommunicationModels.View;
import com.platform.iot.communication.DeviceCommunicationService;
import com.platform.iot.roomsensor.RoomSensorReadings.ContactKind;
import com.platform.iot.roomsensor.RoomSensorRoomQueryService.ContactSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.platform.iot.roomsensor.RoomSensorRoomQueryService.Status.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoomSensorRoomQueryServiceTest {
    private static final String BUILDING = "building";
    private static final String ROOM = "r314";
    private static final String DATE = "2026-11-19T";
    private static final String ONLINE = "ONLINE";
    private JdbcTemplate mysql;
    private JdbcTemplate td;
    private ObjectProvider<RoomSensorHistoryStore> histories;
    private DeviceCommunicationService communication;

    @BeforeEach
    void setUp() {
        mysql = jdbc();
        td = jdbc();
        mysql.execute("CREATE TABLE biz_space(space_id VARCHAR(32),building_id VARCHAR(32),space_type VARCHAR(12),del_flag INT)");
        mysql.execute("CREATE TABLE biz_equipment(equip_id VARCHAR(32),building_id VARCHAR(32),space_id VARCHAR(32),del_flag INT,equip_name VARCHAR(100))");
        mysql.execute("""
                CREATE TABLE biz_device_identity(identity_id VARCHAR(32),equip_id VARCHAR(32),building_id VARCHAR(32),
                    identity_type VARCHAR(16),identity_value VARCHAR(32),expected_profile_code VARCHAR(32),status INT)
                """);
        mysql.execute("""
                CREATE TABLE biz_room_sensor_source(source_id VARCHAR(32),protocol VARCHAR(32),topic VARCHAR(32),
                    gateway_mac VARCHAR(32),freshness_seconds INT,identity_id VARCHAR(32),enabled INT,
                    contact_kind VARCHAR(8),contact_location VARCHAR(80))
                """);
        mysql.update("INSERT INTO biz_space VALUES (?,?, 'ROOM',0),('r313',?,'ROOM',0)", ROOM, BUILDING, BUILDING);
        bind("radar", "MSR01_RADAR", ROOM);
        bind("door", "S4_DOOR", ROOM);
        td.execute("CREATE SCHEMA iot_telemetry");
        var columns = """
                ts BIGINT, received_at BIGINT, source_id VARCHAR(32), building_id VARCHAR(32), identity_id VARCHAR(32),
                equip_id VARCHAR(32), space_id VARCHAR(32), gateway_mac VARCHAR(32), device_mac VARCHAR(32),
                """;
        td.execute("CREATE TABLE iot_telemetry.st_radar_observation (" + columns + "target_count INT)");
        td.execute("CREATE TABLE iot_telemetry.st_door_observation (" + columns + "door_open BOOLEAN)");
        var properties = new TdengineProperties();
        properties.setDatabase("iot_telemetry");
        var beans = new StaticListableBeanFactory();
        beans.addBean("history", new RoomSensorHistoryStore(td, properties, new ObjectMapper()));
        histories = beans.getBeanProvider(RoomSensorHistoryStore.class);
        communication = mock(DeviceCommunicationService.class);
        var online = new View(true, ONLINE, "RECENT_CONTACT", null, at("09:02:15").toEpochMilli(), "DEVICE", false, false, null, 1);
        when(communication.equipment("eq-radar", BUILDING)).thenReturn(online);
        when(communication.equipment("eq-door", BUILDING)).thenReturn(online);
    }

    @Test
    void radarUsesReceiveTimeWithRealZeroAndAnIndependentTenSecondValidityWindow() {
        radar("07:02:59", 5, 0, ROOM);
        radar("08:58:05", 2, 0, ROOM);
        radar("08:58:30", 4, 0, ROOM);
        radar("09:02:01", 4, 0, ROOM);
        radar("09:02:01", 0, 1, ROOM);
        radar("09:02:09", -3, 0, ROOM);
        radar("09:02:10", 99, 0, "r313");
        radar("09:02:11", 98, 0, ROOM);
        var report = atTime("09:02:10").occupancy(BUILDING, ROOM);
        assertThat(report.status()).isEqualTo(AVAILABLE);
        assertThat(report.currentCount()).isZero();
        assertThat(report.lastObservedAt()).isEqualTo(at("09:02:01"));
        assertThat(report.series1m()).hasSize(120);
        assertThat(report.series15m()).hasSize(8);
        assertThat(report.series1m().getLast().count()).isZero();
        assertThat(report.series1m().get(118).count()).isNull();
        assertThat(report.series1m().get(115).count()).isEqualTo(4);
        assertThat(report.series15m().getLast().count()).isZero();
        assertThat(report.hourlyAvg()).hasSize(24);
        assertThat(report.hourlyAvg().get(8).averageCount()).isEqualTo(4);
        assertThat(report.hourlyAvg().get(9).averageCount()).isZero();
        assertThat(report.hourlyAvg().get(10).averageCount()).isNull();

        var stale = atTime("09:02:11").occupancy(BUILDING, ROOM);
        assertThat(stale.currentCount()).isEqualTo(98);
        td.update("DELETE FROM iot_telemetry.st_radar_observation WHERE target_count=98");
        stale = atTime("09:02:11").occupancy(BUILDING, ROOM);
        assertThat(stale.status()).isEqualTo(STALE);
        assertThat(stale.currentCount()).isNull();
        assertThat(stale.lastObservedCount()).isZero();
        assertThat(stale.communication().state()).isEqualTo(ONLINE);
    }

    @Test
    void roundsHourlyAveragesFromValidSamplesAndLeavesMissingHoursEmpty() {
        radar("07:00:00", 0, 0, ROOM);
        radar("07:01:00", 1, 0, ROOM);
        radar("08:00:00", 0, 0, ROOM);
        radar("08:01:00", 0, 0, ROOM);
        radar("08:02:00", 1, 0, ROOM);
        radar("09:00:00", 2, 0, ROOM);
        radar("09:01:00", 3, 0, ROOM);

        var hours = atTime("10:00:00").occupancy(BUILDING, ROOM).hourlyAvg();
        assertThat(hours.subList(7, 11)).extracting(RoomSensorRoomQueryService.AverageHour::averageCount)
                .containsExactly(1, 0, 3, null);
        assertThat(hours.subList(7, 11)).extracting(RoomSensorRoomQueryService.AverageHour::sampleCount)
                .containsExactly(2, 3, 2, 0);
    }

    @Test
    void runningHourUsesTheSameMinuteSamplesAndUpdatesAsTheSnapshotAdvances() {
        radar("08:59:50", 5, 0, ROOM);
        radar("09:00:02", 5, 0, ROOM);
        radar("09:00:58", 0, 0, ROOM);
        radar("09:01:58", 1, 0, ROOM);
        radar("09:02:58", 0, 0, ROOM);

        var earlier = atTime("09:01:59").occupancy(BUILDING, ROOM);
        var later = atTime("09:02:59").occupancy(BUILDING, ROOM);
        assertThat(earlier.series1m().subList(118, 120)).extracting(RoomSensorRoomQueryService.CountSlot::count)
                .containsExactly(0, 1);
        assertThat(earlier.hourlyAvg().get(9).averageCount()).isEqualTo(1);
        assertThat(earlier.hourlyAvg().get(9).sampleCount()).isEqualTo(2);
        assertThat(later.series1m().subList(117, 120)).extracting(RoomSensorRoomQueryService.CountSlot::count)
                .containsExactly(0, 1, 0);
        assertThat(later.hourlyAvg().get(9).averageCount()).isZero();
        assertThat(later.hourlyAvg().get(9).sampleCount()).isEqualTo(3);
    }

    @Test
    void doorUsesTransitionsAndSplitsHoursWithoutDuplicatingOpenCounts() {
        door(OffsetDateTime.parse("2026-11-18T23:57:40+08:00").toInstant(), false);
        door(OffsetDateTime.parse("2026-11-18T23:59:45+08:00").toInstant(), true);
        door(at("00:00:10"), false);
        door(at("08:59:50"), true);
        door(at("08:59:55"), true);
        door(at("09:00:55"), false);
        door(at("09:01:00"), true);
        door(at("09:01:15"), false);
        door(at("09:01:20"), true);
        door(at("09:02:20"), false);

        var report = atTime("09:02:30").doorStats(BUILDING, ROOM);
        assertThat(report.status()).isEqualTo(AVAILABLE);
        assertThat(report.doorOpen()).isFalse();
        assertThat(report.currentDurationSec()).isEqualTo(10);
        assertThat(report.recentEvents()).hasSize(5);
        assertThat(report.recentEvents().getLast().durationSec()).isEqualTo(65);
        assertThat(report.recentEvents().getFirst().durationSec()).isEqualTo(60);
        assertThat(report.hourlyRows().getFirst().openCount()).isZero();
        assertThat(report.hourlyRows().getFirst().durationSec()).isEqualTo(10);
        assertThat(report.hourlyRows().get(8).openCount()).isEqualTo(1);
        assertThat(report.hourlyRows().get(8).durationSec()).isEqualTo(10);
        assertThat(report.hourlyRows().get(9).openCount()).isEqualTo(2);
        assertThat(report.hourlyRows().get(9).durationSec()).isEqualTo(130);
        assertThat(report.hourlyRows().get(9).hasLongOpen()).isTrue();
        assertThat(report.hourlyRows().get(10).openCount()).isNull();
        assertThat(report.durationBuckets()).extracting(RoomSensorRoomQueryService.DurationBucket::count).containsExactly(1, 1, 1);
        assertThat(report.durationBuckets()).extracting(RoomSensorRoomQueryService.DurationBucket::durationSec).containsExactly(15.0, 60.0, 65.0);

        var stale = atTime("09:07:20").doorStats(BUILDING, ROOM);
        assertThat(stale.status()).isEqualTo(STALE);
        assertThat(stale.doorOpen()).isNull();
        assertThat(stale.lastObservedOpen()).isFalse();
        assertThat(stale.communication().state()).isEqualTo(ONLINE);
    }

    @Test
    void firstOpenAndMissingOrAmbiguousBindingsDoNotInventEvents() {
        assertThat(atTime("09:03:50").doorStats(BUILDING, ROOM).status()).isEqualTo(NO_OBSERVATION);
        door(at("09:03:05"), true);
        door(at("09:03:10"), true);
        var first = atTime("09:03:50").doorStats(BUILDING, ROOM);
        assertThat(first.doorOpen()).isTrue();
        assertThat(first.lastTransitionAt()).isNull();
        assertThat(first.currentDurationSec()).isNull();
        assertThat(first.recentEvents()).isEmpty();
        assertThat(atTime("09:03:50").doorStats(BUILDING, "r313").status()).isEqualTo(UNCONFIGURED);
        bind("door2", "S4_DOOR", ROOM);
        assertThat(atTime("09:03:50").doorStats(BUILDING, ROOM).status()).isEqualTo(AMBIGUOUS);
    }

    @Test
    void changedBindingStopsResultBeforeItCanLeaveTheRoomScope() {
        radar("09:02:01", 1, 0, ROOM);
        when(communication.equipment("eq-radar", BUILDING)).thenAnswer(call -> {
            mysql.update("UPDATE biz_equipment SET space_id='r313' WHERE equip_id='eq-radar'");
            return View.unmonitored();
        });
        assertThatThrownBy(() -> atTime("09:02:03").occupancy(BUILDING, ROOM))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("COMFORT_ROOM_SENSOR_BINDING_CHANGED"));
    }

    @Test
    void protocolMustExactlyMatchTheEnabledIdentityProfile() {
        radar("09:02:01", 1, 0, ROOM);
        mysql.update("UPDATE biz_device_identity SET expected_profile_code='msr01_radar' WHERE identity_id='id-radar'");
        var report = atTime("09:02:03").occupancy(BUILDING, ROOM);
        assertThat(report.status()).isEqualTo(UNCONFIGURED);
        assertThat(report.currentCount()).isNull();
    }

    @Test
    void batchDoorBadgesUseFreshConfirmedRoomIdentityAndReceiveOrder() {
        bind("second-door", "S4_DOOR", "r313");
        long old = at("08:53:00").toEpochMilli();
        long open = at("09:01:50").toEpochMilli();
        long closed = at("09:02:00").toEpochMilli();
        addObservation("st_door_observation", "second-door", old, old, "r313", true);
        addObservation("st_door_observation", "second-door", closed, closed, ROOM, true);
        addObservation("st_door_observation", "door", closed, closed, ROOM, false);
        addObservation("st_door_observation", "door", open, closed + 1, ROOM, true);
        var query = atTime("09:02:03");
        assertThat(query.buildingDoorSummaries(BUILDING, Set.of(ROOM, "r313")))
                .containsOnlyKeys(ROOM, "r313").containsEntry(ROOM, new ContactSummary(0, 1, 0))
                .containsEntry("r313", new ContactSummary(0, 0, 1));
        bind("ambiguous-door", "S4_DOOR", ROOM);
        assertThat(query.buildingDoorSummaries(BUILDING, Set.of(ROOM, "r313"))).containsOnlyKeys("r313");
    }

    @Test
    void groupsMultipleInstalledContactsAndKeepsStatsAndAlarmsScopedToEachDoor() {
        bind("side", "S4_DOOR", ROOM);
        bind("window", "S4_DOOR", ROOM);
        bind("unknown-installation", "S4_DOOR", ROOM);
        bind("other", "S4_DOOR", "r313");
        when(communication.equipment("eq-side", BUILDING)).thenReturn(View.unmonitored());
        var catalog = new RoomSensorReadings(mysql, null, null, null);
        catalog.updateContactInstallation(BUILDING, "eq-door", "door", ContactKind.DOOR, "入口");
        catalog.updateContactInstallation(BUILDING, "eq-side", "side", ContactKind.DOOR, "侧门");
        catalog.updateContactInstallation(BUILDING, "eq-window", "window", ContactKind.WINDOW, "南窗");
        assertThat(catalog.contactInstallation(BUILDING, "eq-window")).hasValue(
                new RoomSensorReadings.ContactInstallation("window", ContactKind.WINDOW, "南窗"));
        assertThatThrownBy(() -> catalog.updateContactInstallation(BUILDING, "eq-door", "window", ContactKind.DOOR, "错绑"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("ROOM_SENSOR_BINDING_CHANGED"));

        door(at("09:00:00"), false);
        door(at("09:01:00"), true);
        long stale = at("08:50:00").toEpochMilli();
        long fresh = at("09:02:00").toEpochMilli();
        addObservation("st_door_observation", "side", stale, stale, ROOM, false);
        addObservation("st_door_observation", "window", fresh, fresh, ROOM, true);
        var query = atTime("09:02:30");
        var summary = query.doorStats(BUILDING, ROOM);
        assertThat(summary.status()).isEqualTo(SELECTION_REQUIRED);
        assertThat(summary.selectedDoorEquipmentId()).isNull();
        assertThat(summary.doors()).isEqualTo(new ContactSummary(1, 0, 1));
        assertThat(summary.windows()).isEqualTo(new ContactSummary(1, 0, 0));
        assertThat(summary.contacts()).filteredOn(contact -> "eq-side".equals(contact.equipmentId())).singleElement()
                .satisfies(contact -> {
                    assertThat(contact.open()).isNull();
                    assertThat(contact.lastObservedOpen()).isFalse();
                    assertThat(contact.lastObservedAt()).isEqualTo(at("08:50:00"));
                });
        assertThat(query.buildingDoorSummaries(BUILDING, Set.of(ROOM))).containsEntry(ROOM, summary.doors());
        assertThat(query.doorStats(BUILDING, ROOM, "eq-door").recentEvents()).hasSize(1);
        assertThat(query.doorStats(BUILDING, ROOM, "eq-side").status()).isEqualTo(STALE);
        for (String invalid : List.of("eq-window", "eq-other", "eq-unknown-installation")) {
            assertThatThrownBy(() -> query.doorStats(BUILDING, ROOM, invalid))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo("COMFORT_DOOR_NOT_FOUND"));
        }
        assertThat(query.doorsForAlarm(BUILDING, ROOM, at("09:02:30").toEpochMilli()))
                .extracting(RoomSensorRoomQueryService.DoorAlarmReading::equipmentId)
                .containsExactly("eq-door", "eq-side");
        assertThat(query.matchesDoorBinding(closure("door"))).isTrue();
        assertThat(query.matchesDoorBinding(closure("window"))).isFalse();
    }

    private RoomSensorObservation closure(String source) {
        return new RoomSensorObservation("close", source, "S4_DOOR", 0, 0, null, "id-" + source, "eq-" + source,
                BUILDING, ROOM, "ABC000456789", "ABC123456789", null, null, false);
    }

    private void bind(String id, String protocol, String room) {
        mysql.update("INSERT INTO biz_equipment VALUES (?,?,?,0,?)", "eq-" + id, BUILDING, room, id);
        mysql.update("INSERT INTO biz_device_identity VALUES (?,?,?,'MAC','ABC123456789',?,1)", "id-" + id, "eq-" + id, BUILDING, protocol);
        mysql.update("INSERT INTO biz_room_sensor_source VALUES (?,?,'topic','ABC000456789',300,?,1,NULL,NULL)", id, protocol, "id-" + id);
    }

    private void radar(String time, int count, long offset, String room) {
        long receive = at(time).toEpochMilli();
        addObservation("st_radar_observation", "radar", receive, receive + offset, room, count);
    }

    private void door(Instant time, boolean open) {
        long receive = time.toEpochMilli();
        addObservation("st_door_observation", "door", receive, receive, ROOM, open);
    }

    private void addObservation(String table, String source, long receive, long key, String room, Object value) {
        td.update("INSERT INTO iot_telemetry." + table + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                key, receive, source, BUILDING, "id-" + source, "eq-" + source, room, "ABC000456789", "ABC123456789", value);
    }

    private RoomSensorRoomQueryService atTime(String time) {
        return new RoomSensorRoomQueryService(mysql, histories, communication, Clock.fixed(at(time), ZoneOffset.UTC));
    }

    private static Instant at(String time) { return OffsetDateTime.parse(DATE + time + "+08:00").toInstant(); }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
    }
}
