package com.platform.iot.daikin.monitoring.state;

import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.model.DaikinDeviceObservation;
import com.platform.iot.daikin.model.DaikinFieldValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static com.platform.iot.daikin.model.DaikinFieldValue.Status.MISSING;
import static com.platform.iot.daikin.model.DaikinFieldValue.Status.PRESENT;
import static com.platform.iot.daikin.model.DaikinFieldValue.Status.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

class DaikinMonitoringStateServiceTest {
    private static final long NOW = Instant.parse("2026-09-16T01:00:00Z").toEpochMilli();
    private JdbcTemplate jdbc;
    private MutableClock clock;
    private DaikinMonitoringStateService service;
    private DaikinMonitoringStateService.Target target;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:daikin-monitoring-state-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("daikin-monitoring-state-test.sql"))
                .execute(dataSource);
        clock = new MutableClock(NOW);
        service = new DaikinMonitoringStateService(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), clock);
        target = target("building-A", 3);
        service.registerTarget(target, NOW);
    }

    @Test
    void freshObservationBetweenCandidateQueryAndLockPreventsFalseStaleException() {
        clock.set(NOW + 600000);
        JdbcTemplate spy = org.mockito.Mockito.spy(jdbc);
        service = new DaikinMonitoringStateService(spy,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), clock);
        org.mockito.Mockito.doAnswer(invocation -> {
            Object candidates = invocation.callRealMethod();
            service.observe(target, 100, observation(clock.millis(), Map.of("onOff", value("on"))));
            return candidates;
        }).when(spy).query(org.mockito.ArgumentMatchers.contains("FROM biz_daikin_monitoring_target t"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<DaikinMonitoringStateService.Target>>any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(service.scanStale(clock.millis(), 10)).isZero();
        assertThat(activeExceptions("DEVICE_STALE")).isZero();
    }

    @Test
    void delayedPersistenceDoesNotRecoverAnAlreadyStaleDevice() {
        clock.set(NOW + 600000);
        service.scanStale(clock.millis(), 10);
        assertThat(activeExceptions("DEVICE_STALE")).isOne();
        service.observe(target, 100, observation(NOW, Map.of("onOff", value("on"))));
        assertThat(activeExceptions("DEVICE_STALE")).isOne();
        assertThat(current("onOff").get("LAST_VALID_AT_MS")).isEqualTo(NOW);
    }

    @Test
    void oldAndDuplicateRoundsCannotOverwriteCurrentStateOrDuplicateEvent() {
        service.observe(target, 200, observation(NOW + 2_000, Map.of("onOff", value("on"))));
        service.observe(target, 100, observation(NOW + 3_000, Map.of("onOff", value("off"))));
        service.observe(target, 200, observation(NOW + 4_000, Map.of("onOff", value("off"))));

        assertThat(current("onOff").get("NORMALIZED_VALUE")).isEqualTo("on");
        assertThat(current("onOff").get("LAST_VALID_AT_MS")).isEqualTo(NOW + 2_000);
        assertThat(count("biz_daikin_state_event")).isZero();
    }

    @Test
    void observedRuntimeSplitsAtBeijingMidnightAndCountsPreviousOnStateOnce() {
        long beforeMidnight = Instant.parse("2026-09-16T15:59:00Z").toEpochMilli();
        service.observe(target, 100, observation(beforeMidnight, Map.of("onOff", value("on"))));
        service.observe(target, 200, observation(beforeMidnight + 120_000, Map.of("onOff", value("off"))));
        service.observe(target, 200, observation(beforeMidnight + 120_000, Map.of("onOff", value("off"))));
        service.observe(target, 300, observation(beforeMidnight + 180_000, Map.of("onOff", value("on"))));

        var days = jdbc.queryForList("SELECT on_ms,covered_ms FROM biz_daikin_observed_runtime_day ORDER BY day_start_ms");
        assertThat(days).hasSize(2);
        assertThat(days.get(0).get("ON_MS")).isEqualTo(60_000L);
        assertThat(days.get(0).get("COVERED_MS")).isEqualTo(60_000L);
        assertThat(days.get(1).get("ON_MS")).isEqualTo(60_000L);
        assertThat(days.get(1).get("COVERED_MS")).isEqualTo(120_000L);
    }

    @Test
    void observedRuntimeExcludesMissingAndLongGaps() {
        service.observe(target, 100, observation(NOW, Map.of("onOff", value("on"))));
        service.observe(target, 200, observation(NOW + 60_000, Map.of("onOff", unavailable(MISSING))));
        service.observe(target, 300, observation(NOW + 120_000, Map.of("onOff", value("on"))));
        service.observe(target, 400, observation(NOW + 480_001, Map.of("onOff", value("off"))));

        assertThat(count("biz_daikin_observed_runtime_day")).isZero();
    }

    @Test
    void observedRuntimeDoesNotCountAcrossDeactivation() {
        service.observe(target, 100, observation(NOW, Map.of("onOff", value("on"))));
        service.deactivateTarget(target.identityId());
        service.registerTarget(target, NOW + 30_000);
        service.observe(target, 200, observation(NOW + 60_000, Map.of("onOff", value("off"))));

        assertThat(count("biz_daikin_observed_runtime_day")).isZero();
    }

    @Test
    void sameValueRefreshesFieldTimeWithoutCreatingChangeEvent() {
        service.observe(target, 100, observation(NOW, Map.of("mode", value("cooling"))));
        service.observe(target, 200, observation(NOW + 60_000, Map.of("mode", value("cooling"))));

        assertThat(current("mode").get("LAST_VALID_AT_MS")).isEqualTo(NOW + 60_000);
        assertThat(current("mode").get("LAST_ATTEMPT_AT_MS")).isEqualTo(NOW + 60_000);
        assertThat(count("biz_daikin_state_event")).isZero();
    }

    @Test
    void unavailableFieldPreservesValueAndCannotRecoverExplicitVendorFault() {
        service.observe(target, 100,
                observation(NOW, Map.of("inEquipmentError", bool(true))));
        service.observe(target, 200,
                observation(NOW + 60_000, Map.of("inEquipmentError", unavailable(MISSING))));
        service.observe(target, 300,
                observation(NOW + 120_000, Map.of("inEquipmentError", unavailable(UNKNOWN))));

        Map<String, Object> current = current("inEquipmentError");
        assertThat(current.get("NORMALIZED_VALUE")).isEqualTo("true");
        assertThat(current.get("LAST_VALID_AT_MS")).isEqualTo(NOW);
        assertThat(current.get("LAST_ATTEMPT_AT_MS")).isEqualTo(NOW + 120_000);
        assertThat(current.get("LAST_ATTEMPT_RAW_JSON")).isEqualTo("\"unknown\"");
        assertThat(current.get("FIELD_STATUS")).isEqualTo("UNKNOWN");
        assertThat(activeExceptions("VENDOR_EQUIPMENT")).isOne();

        service.observe(target, 400,
                observation(NOW + 180_000, Map.of("inEquipmentError", bool(false))));
        assertThat(activeExceptions("VENDOR_EQUIPMENT")).isZero();
        assertThat(jdbc.queryForObject("SELECT recovered_at_ms FROM biz_daikin_exception_instance", Long.class))
                .isEqualTo(NOW + 180_000);
    }

    @Test
    void changedStateAfterGapCapturesBeforeAfterAndCurrentOwnershipSnapshot() {
        service.observe(target, 100, observation(NOW, Map.of("onOff", value("off"))));
        DaikinMonitoringStateService.Target remapped = new DaikinMonitoringStateService.Target(
                "source-A", "pending-A", "identity-A", "equipment-A",
                "building-A", "space-B", "system-A", 4);
        service.registerTarget(remapped, NOW + 1_000);
        service.observe(remapped, 200,
                observation(NOW + 300_001, Map.of("onOff", value("on"))));

        Map<String, Object> event = jdbc.queryForMap("SELECT * FROM biz_daikin_state_event");
        assertThat(event.get("BEFORE_RAW_JSON")).isEqualTo("\"off\"");
        assertThat(event.get("AFTER_RAW_JSON")).isEqualTo("\"on\"");
        assertThat(event.get("PREVIOUS_OBSERVED_AT_MS")).isEqualTo(NOW);
        assertThat(event.get("AFTER_GAP")).isEqualTo(1);
        assertThat(event.get("BUILDING_ID")).isEqualTo("building-A");
        assertThat(event.get("SPACE_ID")).isEqualTo("space-B");
        assertThat(event.get("MAPPING_VERSION")).isEqualTo(4);
    }

    @Test
    void crossBuildingChangeDoesNotCarryPreviousBuildingValueIntoEvent() {
        service.observe(target, 100, observation(NOW, Map.of("onOff", value("off"))));
        DaikinMonitoringStateService.Target moved = target("building-B", 4);
        service.registerTarget(moved, NOW + 1_000);
        service.observe(moved, 200,
                observation(NOW + 300_001, Map.of("onOff", value("on"))));

        assertThat(count("biz_daikin_state_event")).isZero();
        assertThat(current("onOff").get("BUILDING_ID")).isEqualTo("building-B");
        assertThat(current("onOff").get("NORMALIZED_VALUE")).isEqualTo("on");
    }

    @Test
    void threeDistinctFailedRoundsOpenOneSourceExceptionAndNewerSuccessRecoversIt() {
        service.recordFailure("source-A", 100);
        service.recordFailure("source-A", 100);
        service.recordFailure("source-A", 90);
        service.recordFailure("source-A", 200);
        service.recordFailure("source-A", 300);
        service.recordFailure("source-A", 300);

        assertThat(jdbc.queryForObject(
                "SELECT consecutive_failure_rounds FROM biz_daikin_source_result", Integer.class)).isEqualTo(3);
        assertThat(activeExceptions("SOURCE_FETCH")).isOne();
        assertThat(count("biz_daikin_exception_instance")).isOne();

        clock.set(NOW + 400_000);
        service.recordSuccess("source-A", 400);
        assertThat(activeExceptions("SOURCE_FETCH")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT consecutive_failure_rounds FROM biz_daikin_source_result", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT recovered_at_ms FROM biz_daikin_exception_instance", Long.class)).isEqualTo(NOW + 400_000);
    }

    @Test
    void firstPlannedTargetExpiresAfterFiveMinutesAndRepeatedScanDoesNotDuplicate() {
        DaikinMonitoringStateService.Target second = target("identity-B", "building-A", 3);
        service.registerTarget(second, NOW);

        assertThat(service.scanStale(NOW + 300_000, 10)).isZero();
        assertThat(service.scanStale(NOW + 300_001, 1)).isOne();
        assertThat(service.scanStale(NOW + 360_000, 1)).isOne();
        assertThat(service.scanStale(NOW + 420_000, 1)).isZero();
        assertThat(activeExceptions("DEVICE_STALE")).isEqualTo(2);
        assertThat(count("biz_daikin_exception_instance")).isEqualTo(2);

        service.deactivateTarget(target.identityId());
        assertThat(service.scanStale(NOW + 480_000, 10)).isZero();
        assertThat(activeExceptions("DEVICE_STALE")).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT active FROM biz_daikin_monitoring_target WHERE identity_id='identity-A'
                """, Integer.class)).isZero();
    }

    @Test
    void metadataCannotRefreshOrRecoverStaleButRuntimeFieldCan() {
        service.scanStale(NOW + 300_001, 10);
        service.observe(target, 100,
                observation(NOW + 310_000, Map.of("formalName", value("Meeting room"))));

        assertThat(activeExceptions("DEVICE_STALE")).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT last_valid_at_ms FROM biz_daikin_monitoring_target", Long.class)).isNull();
        assertThat(DaikinMonitoringStateService.hasValidRuntimeFields(
                observation(NOW, Map.of("formalName", value("name"))))).isFalse();

        service.observe(target, 200,
                observation(NOW + 320_000, Map.of("controller.isConnectionUp", bool(true))));
        assertThat(activeExceptions("DEVICE_STALE")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT last_valid_at_ms FROM biz_daikin_monitoring_target", Long.class))
                .isEqualTo(NOW + 320_000);
    }

    @Test
    void temperatureKeepsIndependentFreshnessWithoutHighFrequencyChangeEvents() {
        service.observe(target, 100, observation(NOW, Map.of(
                "roomTemp", value("24.0"), "temperature", value("26.0"))));
        service.observe(target, 200, observation(NOW + 60_000, Map.of(
                "roomTemp", value("24.5"), "temperature", unavailable(UNKNOWN))));

        assertThat(current("roomTemp").get("LAST_VALID_AT_MS")).isEqualTo(NOW + 60_000);
        assertThat(current("temperature").get("LAST_VALID_AT_MS")).isEqualTo(NOW);
        assertThat(current("temperature").get("FIELD_STATUS")).isEqualTo("UNKNOWN");
        assertThat(count("biz_daikin_state_event")).isZero();
    }

    @Test
    void absentMapFieldIsMissingAndMovedAttemptCannotReassignItsOldValue() {
        service.observe(target, 100, observation(NOW, Map.of("onOff", value("on"))));
        DaikinMonitoringStateService.Target moved = target("building-B", 4);
        service.registerTarget(moved, NOW + 60_000);
        service.observe(moved, 200,
                observation(NOW + 60_000, Map.of("mode", value("cooling"))));

        Map<String, Object> current = current("onOff");
        assertThat(current.get("NORMALIZED_VALUE")).isEqualTo("on");
        assertThat(current.get("RAW_JSON")).isEqualTo("\"on\"");
        assertThat(current.get("LAST_VALID_AT_MS")).isEqualTo(NOW);
        assertThat(current.get("FIELD_STATUS")).isEqualTo("MISSING");
        assertThat(current.get("LAST_ATTEMPT_RAW_JSON")).isNull();
        assertThat(current.get("BUILDING_ID")).isEqualTo("building-A");
        assertThat(current.get("LAST_ATTEMPT_BUILDING_ID")).isEqualTo("building-B");
        assertThat(current.get("LAST_ATTEMPT_MAPPING_VERSION")).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT first_planned_at_ms FROM biz_daikin_monitoring_target
                WHERE identity_id='identity-A'
                """, Long.class)).isEqualTo(NOW + 60_000);
    }

    @Test
    void newerRoundWithOlderObservationCannotRegressValueOrRecoverException() {
        service.observe(target, 100,
                observation(NOW + 120_000, Map.of("inEquipmentError", bool(true))));
        service.observe(target, 200,
                observation(NOW + 60_000, Map.of("inEquipmentError", bool(false))));

        Map<String, Object> current = current("inEquipmentError");
        assertThat(current.get("NORMALIZED_VALUE")).isEqualTo("true");
        assertThat(current.get("LAST_VALID_AT_MS")).isEqualTo(NOW + 120_000);
        assertThat(activeExceptions("VENDOR_EQUIPMENT")).isOne();
    }

    @Test
    void maintenanceFilterAndControllerCommunicationUseIndependentCategories() {
        service.observe(target, 100, observation(NOW, Map.of(
                "inMantenanceMode", bool(true),
                "isFilterDirty", bool(true),
                "controller.isConnectionUp", bool(false))));

        assertThat(activeExceptions("VENDOR_MAINTENANCE")).isOne();
        assertThat(activeExceptions("FILTER_MAINTENANCE")).isOne();
        assertThat(activeExceptions("CONTROLLER_COMMUNICATION")).isOne();
        assertThat(activeExceptions("VENDOR_EQUIPMENT")).isZero();
    }

    private DaikinMonitoringStateService.Target target(String buildingId, int mappingVersion) {
        return target("identity-A", buildingId, mappingVersion);
    }

    private DaikinMonitoringStateService.Target target(String identityId, String buildingId,
                                                        int mappingVersion) {
        return new DaikinMonitoringStateService.Target("source-A", "pending-A", identityId,
                "equipment-A", buildingId, "space-A", "system-A", mappingVersion);
    }

    private DaikinDeviceObservation observation(long observedAt, Map<String, DaikinFieldValue> fields) {
        return new DaikinDeviceObservation(new DaikinDeviceKey("source-A", "site-A", "controller-A",
                DaikinDeviceKey.Kind.INDOOR, "unit-A"), "factory-equipment-A", "site", "unit",
                Instant.ofEpochMilli(observedAt), null, fields);
    }

    private static DaikinFieldValue value(String value) {
        return new DaikinFieldValue(PRESENT, "\"" + value + "\"", value);
    }

    private static DaikinFieldValue bool(boolean value) {
        return new DaikinFieldValue(PRESENT, Boolean.toString(value), Boolean.toString(value));
    }

    private static DaikinFieldValue unavailable(DaikinFieldValue.Status status) {
        return new DaikinFieldValue(status, status == MISSING ? null : "\"unknown\"", null);
    }

    private Map<String, Object> current(String fieldName) {
        return jdbc.queryForMap("SELECT * FROM biz_daikin_current_state WHERE field_name=?", fieldName);
    }

    private int activeExceptions(String type) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_daikin_exception_instance
                WHERE exception_type=? AND active_key IS NOT NULL
                """, Integer.class, type);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static final class MutableClock extends Clock {
        private long millis;

        private MutableClock(long millis) { this.millis = millis; }
        private void set(long millis) { this.millis = millis; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public long millis() { return millis; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }
}
