package com.platform.iot.onboarding;

import com.platform.framework.exception.BusinessException;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.iot.onboarding.api.DeviceOnboardingContracts.BindRequest;
import com.platform.iot.onboarding.model.entity.BizPendingDevice;
import com.platform.iot.onboarding.model.entity.BizProductPointTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 正式房间归属来自启用的雷达网关；审批参数中的 MAC 仅用于确认用户看到的来源。 */
@Service
class RoomSensorOnboarding {
    static final String RADAR = "MSR01_RADAR";
    static final String DOOR = "S4_DOOR";
    private final JdbcTemplate mysql;
    private final List<String> topics;

    RoomSensorOnboarding(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql,
            @Value("${mqtt.topics.room-sensor-upstream:}") String topics) {
        this.mysql = mysql;
        this.topics = Arrays.stream(topics.split(",")).map(String::trim).toList();
    }

    record GatewayHint(String mac, String equipmentName, String buildingId, String spaceId) {
        static final GatewayHint EMPTY = new GatewayHint(null, null, null, null);
    }

    static boolean isRoomSensor(String profile) {
        return RADAR.equals(profile) || DOOR.equals(profile);
    }

    GatewayHint hint(BizPendingDevice pending) {
        String mac = pending.getDiscoveryGatewayMac();
        if (!isRoomSensor(pending.getProfileCode()) || mac == null) return GatewayHint.EMPTY;
        GatewayHint registered = gateway(mac, false);
        if (registered == null) return new GatewayHint(mac, null, null, null);
        return DOOR.equals(pending.getProfileCode()) ? registered
                : new GatewayHint(mac, registered.equipmentName(), null, null);
    }

    void validate(BizPendingDevice pending, BindRequest request, boolean lock) {
        String profile = pending.getProfileCode();
        if (!DOOR.equals(profile) && request.gatewayMac() != null)
            throw conflict("只有门磁绑定需要填写网关 MAC");
        if (!isRoomSensor(profile)) return;
        String mac = pending.getDiscoveryGatewayMac();
        if (!"MAC".equals(pending.getIdentityType()) || mac == null
                || !topics.contains(pending.getDiscoveryTopic())) {
            throw conflict("设备尚未从已订阅的原生主题发现");
        }
        var room = mysql.queryForList("""
                SELECT space_id FROM biz_space
                WHERE space_id=? AND building_id=? AND space_type='ROOM' AND del_flag=0
                """ + (lock ? " FOR UPDATE" : ""), String.class, request.spaceId(), request.buildingId());
        if (room.isEmpty()) throw conflict("请选择启用建筑下的安装房间");
        if (DOOR.equals(profile)) {
            if (request.gatewayMac() == null || !mac.equals(request.gatewayMac().toUpperCase(Locale.ROOT)))
                throw conflict("门磁网关与平台发现的来源不一致，请刷新设备详情");
            GatewayHint gateway = gateway(mac, lock);
            if (gateway == null) throw conflict("请先将所属雷达网关绑定到房间并启用");
            if (!gateway.buildingId().equals(request.buildingId()) || !gateway.spaceId().equals(request.spaceId()))
                throw conflict("门磁与所属网关必须绑定在同一房间");
        } else if (!mac.equals(pending.getIdentityValue())) {
            throw conflict("雷达网关 MAC 与设备身份不一致");
        }
    }

    void validateTemplate(String profile, List<BizProductPointTemplate> templates) {
        if (!isRoomSensor(profile)) return;
        boolean radar = RADAR.equals(profile);
        if (templates.size() != 1) throw conflict("房间传感器产品必须有一个启用的观测测点");
        var template = templates.getFirst();
        if (!(radar ? "targetCount" : "doorOpen").equals(template.getMetricCode())
                || !(radar ? "ANALOG" : "DIGITAL").equals(template.getDataType())
                || !(radar ? "人" : "-").equals(template.getUnit())
                || !Integer.valueOf(0).equals(template.getForCalc())
                || !Integer.valueOf(1).equals(template.getRequiredFlag())) {
            throw conflict("产品测点类型、指标或单位与房间传感器协议不匹配");
        }
    }

    void validatePoint(String profile, BizDataPoint point) {
        if (point == null || !(RADAR.equals(profile) ? "ANALOG" : "DIGITAL").equals(point.getDataType())
                || !Integer.valueOf(0).equals(point.getIsForCalc())) {
            throw conflict("房间观测测点类型必须与协议一致，并且不可参与能源计算");
        }
    }

    void bind(BizPendingDevice pending, String identityId, String pointId) {
        mysql.update("""
                INSERT INTO biz_room_sensor_source
                (source_id,identity_id,gateway_mac,topic,protocol,enabled,unified_point_id,first_unified_storage_ts)
                VALUES (?,?,?,?,?,1,?,0)
                """, identityId, identityId, pending.getDiscoveryGatewayMac(), pending.getDiscoveryTopic(),
                pending.getProfileCode(), pointId);
    }

    private GatewayHint gateway(String mac, boolean lock) {
        return mysql.query("""
                SELECT e.equip_name,e.building_id,e.space_id
                FROM biz_room_sensor_source s
                JOIN biz_device_identity i ON i.identity_id=s.identity_id AND i.status=1
                JOIN biz_equipment e ON e.equip_id=i.equip_id AND e.building_id=i.building_id AND e.del_flag=0
                JOIN biz_space p ON p.space_id=e.space_id AND p.building_id=e.building_id
                    AND p.space_type='ROOM' AND p.del_flag=0
                WHERE i.identity_type='MAC' AND i.identity_value=? AND i.expected_profile_code='MSR01_RADAR'
                    AND s.protocol='MSR01_RADAR' AND s.gateway_mac=? AND s.enabled=1
                """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> new GatewayHint(mac,
                rs.getString("equip_name"), rs.getString("building_id"), rs.getString("space_id")), mac, mac)
                .stream().findFirst().orElse(null);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(409, "VALIDATION_FAILED", message);
    }
}
