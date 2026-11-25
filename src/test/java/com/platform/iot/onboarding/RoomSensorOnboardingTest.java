package com.platform.iot.onboarding;

import com.platform.framework.exception.BusinessException;
import com.platform.iot.onboarding.api.DeviceOnboardingContracts.BindRequest;
import com.platform.iot.onboarding.model.entity.BizPendingDevice;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomSensorOnboardingTest {
    private static final String MAC = "AC233FC2B716";
    private static final String TOPIC = "/MSR01/ac233fc2b716/status";

    @Test
    void doorBindingRequiresAnActiveGatewayInExactlyTheSelectedRoom() {
        var mysql = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        mysql.execute("CREATE TABLE biz_space(space_id VARCHAR(32),building_id VARCHAR(32),space_type VARCHAR(12),del_flag INT)");
        mysql.execute("CREATE TABLE biz_equipment(equip_id VARCHAR(32),building_id VARCHAR(32),space_id VARCHAR(32),del_flag INT,equip_name VARCHAR(100))");
        mysql.execute("""
                CREATE TABLE biz_device_identity(identity_id VARCHAR(32),equip_id VARCHAR(32),building_id VARCHAR(32),
                    identity_type VARCHAR(16),identity_value VARCHAR(32),expected_profile_code VARCHAR(32),status INT)
                """);
        mysql.execute("""
                CREATE TABLE biz_room_sensor_source(source_id VARCHAR(32),identity_id VARCHAR(32),gateway_mac VARCHAR(12),
                    topic VARCHAR(200),protocol VARCHAR(24),enabled INT,unified_point_id VARCHAR(32),first_unified_storage_ts BIGINT)
                """);
        mysql.update("INSERT INTO biz_space VALUES ('room1','building','ROOM',0),('room2','building','ROOM',0)");
        mysql.update("INSERT INTO biz_equipment VALUES ('radar','building','room1',0,'网关雷达')");
        mysql.update("INSERT INTO biz_device_identity VALUES ('radar-id','radar','building','MAC',?,'MSR01_RADAR',1)", MAC);
        mysql.update("INSERT INTO biz_room_sensor_source VALUES ('radar-src','radar-id',?,'radar','MSR01_RADAR',1,NULL,NULL)", MAC);

        var service = new RoomSensorOnboarding(mysql, TOPIC);
        var pending = new BizPendingDevice();
        pending.setProfileCode("S4_DOOR");
        pending.setIdentityType("MAC");
        pending.setIdentityValue("C300008A3BAE");
        pending.setDiscoveryGatewayMac(MAC);
        pending.setDiscoveryTopic(TOPIC);
        assertThat(service.hint(pending)).isEqualTo(new RoomSensorOnboarding.GatewayHint(MAC, "网关雷达", "building", "room1"));
        assertThatThrownBy(() -> service.validate(pending, request("room2", MAC), false))
                .isInstanceOf(BusinessException.class).hasMessageContaining("同一房间");
        assertThatThrownBy(() -> service.validate(pending, request("room1", "AC233FC2B717"), true))
                .isInstanceOf(BusinessException.class).hasMessageContaining("来源不一致");
        service.validate(pending, request("room1", MAC), true);
        service.bind(pending, "door-id", "door-point");
        assertThat(mysql.queryForObject("SELECT first_unified_storage_ts FROM biz_room_sensor_source WHERE source_id='door-id'",
                Long.class)).isZero();

        mysql.update("UPDATE biz_device_identity SET status=0 WHERE identity_id='radar-id'");
        assertThat(service.hint(pending).spaceId()).isNull();
        assertThatThrownBy(() -> service.validate(pending, request("room1", MAC), true))
                .isInstanceOf(BusinessException.class).hasMessageContaining("启用");
    }

    private static BindRequest request(String room, String mac) {
        return new BindRequest("product", "building", room, "group", null, null, List.of(), true, true, mac);
    }
}
