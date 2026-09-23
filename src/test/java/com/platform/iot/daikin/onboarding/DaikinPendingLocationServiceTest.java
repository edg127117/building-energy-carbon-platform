package com.platform.iot.daikin.onboarding;

import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DaikinPendingLocationServiceTest {
    private static final Set<String> ADMIN = Set.of("PLATFORM_ADMIN");
    private JdbcTemplate jdbc;
    private DaikinPendingLocationService service;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:daikin-location-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE biz_daikin_source (source_id VARCHAR(200) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE biz_daikin_project_mapping (source_id VARCHAR(200),site_id VARCHAR(200),building_id VARCHAR(32))");
        jdbc.execute("CREATE TABLE biz_pending_device (pending_id VARCHAR(32) PRIMARY KEY,status VARCHAR(20))");
        jdbc.execute("CREATE TABLE biz_daikin_directory (pending_id VARCHAR(32) PRIMARY KEY,source_id VARCHAR(200),site_id VARCHAR(200),device_kind VARCHAR(10),unit_id VARCHAR(200),equipment_id VARCHAR(200),missing TINYINT)");
        jdbc.execute("CREATE TABLE biz_space (space_id VARCHAR(32) PRIMARY KEY,building_id VARCHAR(32),space_code VARCHAR(50),space_type VARCHAR(50),del_flag TINYINT)");
        jdbc.execute("CREATE TABLE biz_daikin_pending_location (pending_id VARCHAR(32) PRIMARY KEY,room_space_id VARCHAR(32),monitor_address VARCHAR(50),asset_reference_code VARCHAR(50),mapped_by BIGINT,mapped_at TIMESTAMP(3))");
        jdbc.update("INSERT INTO biz_daikin_source VALUES ('SOURCE-A')");
        jdbc.update("INSERT INTO biz_daikin_project_mapping VALUES ('SOURCE-A','SITE-A','BLD-A')");
        jdbc.update("INSERT INTO biz_pending_device VALUES ('P-1','DISCOVERED'),('P-2','DISCOVERED')");
        jdbc.update("INSERT INTO biz_daikin_directory VALUES ('P-1','SOURCE-A','SITE-A','INDOOR','UNIT-1','API-1',0),('P-2','SOURCE-A','SITE-A','INDOOR','UNIT-2','API-2',0)");
        jdbc.update("INSERT INTO biz_space VALUES ('ROOM-A','BLD-A','R301','ROOM',0),('ROOM-B','BLD-B','R302','ROOM',0)");
        service = new DaikinPendingLocationService(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void verifiedMappingIsVisibleAndReplayIsIdempotent() {
        var item = input("UNIT-1", "API-1", "R301", "1-01", "ASSET-1");
        service.mapIndoor("SOURCE-A", "SITE-A", List.of(item), 7L, ADMIN);
        service.mapIndoor("SOURCE-A", "SITE-A", List.of(item), 7L, ADMIN);
        assertThat(service.forPending("P-1")).isEqualTo(
                new DaikinPendingLocationView("ROOM-A", "R301", "1-01", "ASSET-1"));
        assertThat(service.forPendingIds(List.of("P-1", "P-2"))).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_pending_location", Integer.class))
                .isEqualTo(1);
        jdbc.update("UPDATE biz_daikin_project_mapping SET building_id='BLD-B' WHERE source_id='SOURCE-A'");
        assertThat(service.forPending("P-1")).isNull();
    }

    @Test
    void batchRejectsMismatchedIdentityAndRollsBackEarlierRows() {
        assertThatThrownBy(() -> service.mapIndoor("SOURCE-A", "SITE-A", List.of(
                input("UNIT-1", "API-1", "R301", "1-01", "ASSET-1"),
                input("UNIT-2", "WRONG", "R301", "1-02", "ASSET-2")), 7L, ADMIN))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_pending_location", Integer.class))
                .isZero();
    }

    @Test
    void otherBuildingRoomAndExistingDifferentMappingAreRejected() {
        assertThatThrownBy(() -> service.mapIndoor("SOURCE-A", "SITE-A", List.of(
                input("UNIT-1", "API-1", "R302", "1-01", "ASSET-1")), 7L, ADMIN))
                .isInstanceOf(BusinessException.class);
        service.mapIndoor("SOURCE-A", "SITE-A", List.of(
                input("UNIT-1", "API-1", "R301", "1-01", "ASSET-1")), 7L, ADMIN);
        assertThatThrownBy(() -> service.mapIndoor("SOURCE-A", "SITE-A", List.of(
                input("UNIT-1", "API-1", "R301", "1-99", "ASSET-1")), 7L, ADMIN))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void nonAdminAndBoundDevicesCannotBeMapped() {
        assertThatThrownBy(() -> service.mapIndoor("SOURCE-A", "SITE-A", List.of(
                input("UNIT-1", "API-1", "R301", "1-01", "ASSET-1")), 7L, Set.of("BUILDING_OWNER")))
                .isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE biz_pending_device SET status='BOUND' WHERE pending_id='P-1'");
        assertThatThrownBy(() -> service.mapIndoor("SOURCE-A", "SITE-A", List.of(
                input("UNIT-1", "API-1", "R301", "1-01", "ASSET-1")), 7L, ADMIN))
                .isInstanceOf(BusinessException.class);
    }

    private static DaikinPendingLocationService.LocationInput input(String unit, String api,
            String room, String address, String asset) {
        return new DaikinPendingLocationService.LocationInput(unit, api, room, address, asset);
    }
}
