package com.platform.iot.daikin.onboarding;

import com.platform.framework.exception.BusinessException;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.model.DaikinDeviceObservation;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DaikinDirectoryServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-15T02:00:00Z");
    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");

    private JdbcTemplate jdbc;
    private DaikinDirectoryService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:daikin-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        new ResourceDatabasePopulator(new ClassPathResource("daikin-schema-test.sql"))
                .execute(dataSource);
        service = new DaikinDirectoryService(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void persistsCompleteCatalogIntoPendingAndMappedDirectory() {
        service.registerSource("source-A", 7L, ADMIN);
        insertBuilding("BLD-A");
        service.mapProject("source-A", "site-1", "BLD-A", 7L, ADMIN);

        DaikinDeviceObservation observation = observation("unit-1", "device-1", NOW.minusSeconds(2));
        service.acceptCompleteCatalog("source-A", DaikinDeviceKey.Kind.INDOOR, NOW,
                List.of(observation));

        String pendingId = jdbc.queryForObject(
                "SELECT pending_id FROM biz_daikin_directory", String.class);
        assertThat(service.isDirectoryPending(pendingId)).isTrue();
        assertThat(service.requireMappedBuilding(pendingId)).isEqualTo("BLD-A");
        assertThat(service.pendingIdsForBuildings(Set.of("BLD-A"))).containsExactly(pendingId);
        assertThat(service.detail(pendingId)).satisfies(view -> {
            assertThat(view.kind()).isEqualTo(DaikinDeviceKey.Kind.INDOOR);
            assertThat(view.deviceName()).isEqualTo("设备unit-1");
            assertThat(view.buildingId()).isEqualTo("BLD-A");
            assertThat(view.missing()).isFalse();
        });
        assertThat(jdbc.queryForMap("SELECT * FROM biz_pending_device WHERE pending_id=?", pendingId))
                .containsEntry("IDENTITY_TYPE", "DAIKIN_UNIT")
                .containsEntry("PROFILE_CODE", "DAIKIN_INDOOR_V2")
                .containsEntry("REPORT_COUNT", 1L)
                .containsEntry("STATUS", "DISCOVERED");
        service.requireBinding(pendingId, "BLD-A", "DAIKIN_INDOOR_V2");
    }

    @Test
    void sameRoundIsIdempotentAndOlderRoundCannotOverwrite() {
        service.registerSource("source-A", 7L, ADMIN);
        DaikinDeviceObservation current = observation("unit-1", "new-name", NOW.minusSeconds(2));
        service.acceptCompleteCatalog("source-A", DaikinDeviceKey.Kind.INDOOR, NOW,
                List.of(current));
        String pendingId = jdbc.queryForObject("SELECT pending_id FROM biz_daikin_directory", String.class);

        service.acceptCompleteCatalog("source-A", DaikinDeviceKey.Kind.INDOOR, NOW,
                List.of(current));
        service.acceptCompleteCatalog("source-A", DaikinDeviceKey.Kind.INDOOR, NOW.minusSeconds(10),
                List.of(observation("unit-1", "old-name", NOW.minusSeconds(11))));

        assertThat(jdbc.queryForObject(
                "SELECT report_count FROM biz_pending_device WHERE pending_id=?", Long.class, pendingId))
                .isEqualTo(1L);
        assertThat(service.detail(pendingId).deviceName()).isEqualTo("设备unit-1");
    }

    @Test
    void databaseMillisecondPrecisionMakesNanosecondReplayIdempotent() {
        service.registerSource("source-A", 7L, ADMIN);
        Instant first = Instant.parse("2026-09-15T01:59:59.123100Z");
        Instant replay = Instant.parse("2026-09-15T01:59:59.123900Z");
        service.acceptCompleteCatalog("source-A", DaikinDeviceKey.Kind.INDOOR, NOW,
                List.of(observation("unit-1", "device-1", first)));

        service.acceptCompleteCatalog("source-A", DaikinDeviceKey.Kind.INDOOR, NOW,
                List.of(observation("unit-1", "device-1", replay)));

        assertThat(jdbc.queryForObject("SELECT report_count FROM biz_pending_device", Long.class))
                .isEqualTo(1L);
        assertThat(service.detail(jdbc.queryForObject(
                "SELECT pending_id FROM biz_daikin_directory", String.class)).observedAt())
                .isEqualTo(Instant.parse("2026-09-15T01:59:59.123Z"));
    }

    @Test
    void sameRoundWithDifferentPersistedMetadataIsRejected() {
        service.registerSource("source-A", 7L, ADMIN);
        Instant observedAt = NOW.minusSeconds(1);
        service.acceptCompleteCatalog("source-A", DaikinDeviceKey.Kind.INDOOR, NOW,
                List.of(observation("unit-1", "device-1", observedAt)));

        assertThatThrownBy(() -> service.acceptCompleteCatalog(
                "source-A", DaikinDeviceKey.Kind.INDOOR, NOW,
                List.of(observation("unit-1", "changed", observedAt))))
                .isInstanceOf(BusinessException.class);
        assertThat(service.detail(jdbc.queryForObject(
                "SELECT pending_id FROM biz_daikin_directory", String.class)).equipmentId())
                .isEqualTo("device-1");
    }

    @Test
    void onlyLaterCompleteCatalogMarksAbsentDeviceMissingWithoutDeleting() {
        service.registerSource("source-A", 7L, ADMIN);
        service.acceptCompleteCatalog("source-A", DaikinDeviceKey.Kind.INDOOR, NOW.minusSeconds(10),
                List.of(observation("unit-1", "one", NOW.minusSeconds(11)),
                        observation("unit-2", "two", NOW.minusSeconds(11))));
        String missingId = jdbc.queryForObject(
                "SELECT pending_id FROM biz_daikin_directory WHERE unit_id='unit-2'", String.class);

        service.acceptCompleteCatalog("source-A", DaikinDeviceKey.Kind.INDOOR, NOW,
                List.of(observation("unit-1", "one", NOW.minusSeconds(1))));

        assertThat(service.detail(missingId).missing()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_pending_device WHERE pending_id=?", Integer.class, missingId))
                .isEqualTo(1);
    }

    @Test
    void rejectsPartialIdentityDuplicateAndUnsafeEmptyRound() {
        service.registerSource("source-A", 7L, ADMIN);
        DaikinDeviceObservation item = observation("unit-1", "one", NOW.minusSeconds(1));

        assertThatThrownBy(() -> service.acceptCompleteCatalog(
                "source-A", DaikinDeviceKey.Kind.INDOOR, List.of()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.acceptCompleteCatalog(
                "source-A", DaikinDeviceKey.Kind.INDOOR, NOW, List.of(item, item)))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_pending_device", Integer.class))
                .isZero();
    }

    @Test
    void boundDirectoryPreventsProjectRemapAndBindingChecksOriginalIdentity() {
        service.registerSource("source-A", 7L, ADMIN);
        insertBuilding("BLD-A");
        insertBuilding("BLD-B");
        service.mapProject("source-A", "site-1", "BLD-A", 7L, ADMIN);
        service.acceptCompleteCatalog("source-A", DaikinDeviceKey.Kind.INDOOR, NOW,
                List.of(observation("unit-1", "one", NOW.minusSeconds(1))));
        String pendingId = jdbc.queryForObject("SELECT pending_id FROM biz_daikin_directory", String.class);
        jdbc.update("UPDATE biz_pending_device SET status='BOUND' WHERE pending_id=?", pendingId);

        assertThatThrownBy(() -> service.mapProject(
                "source-A", "site-1", "BLD-B", 7L, ADMIN))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.requireBinding(
                pendingId, "BLD-B", "DAIKIN_INDOOR_V2"))
                .isInstanceOf(BusinessException.class);
        assertThat(service.requireMappedBuilding(pendingId)).isEqualTo("BLD-A");
    }

    @Test
    void onlyAdministratorCanRegisterOrMapSource() {
        assertThatThrownBy(() -> service.registerSource("source-A", 7L, Set.of("ENERGY_MANAGER")))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_source", Integer.class))
                .isZero();
    }

    private DaikinDeviceObservation observation(String unitId, String equipmentId, Instant observedAt) {
        DaikinDeviceKey key = new DaikinDeviceKey("source-A", "site-1", "controller-1",
                DaikinDeviceKey.Kind.INDOOR, unitId);
        return new DaikinDeviceObservation(key, equipmentId, "厂家项目", "设备" + unitId,
                observedAt, null, Map.of());
    }

    private void insertBuilding(String buildingId) {
        jdbc.update("INSERT INTO building(building_id,del_flag) VALUES (?,0)", buildingId);
    }

    private void createSchema() {
        jdbc.execute("CREATE TABLE building(building_id VARCHAR(32) PRIMARY KEY,del_flag TINYINT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE biz_pending_device(
                  pending_id VARCHAR(32) PRIMARY KEY,identity_type VARCHAR(20) NOT NULL,
                  identity_value VARCHAR(100) NOT NULL,profile_code VARCHAR(50) NOT NULL,
                  last_profile_version INT NOT NULL,first_seen_time TIMESTAMP NOT NULL,
                  last_seen_time TIMESTAMP NOT NULL,report_count BIGINT NOT NULL,
                  latest_event_time TIMESTAMP NOT NULL,latest_time_source VARCHAR(20) NOT NULL,
                  latest_metrics_json CLOB NOT NULL,sample_truncated TINYINT NOT NULL,
                  status VARCHAR(20) NOT NULL,bound_identity_id VARCHAR(32),
                  create_time TIMESTAMP NOT NULL,update_time TIMESTAMP NOT NULL,
                  UNIQUE(identity_type,identity_value))
                """);
    }
}
