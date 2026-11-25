package com.platform.iot.roomsensor;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** 读取当前启用的设备与空间归属，供门磁和雷达按建筑查询最近观测。 */
record RoomSensorStateBinding(RoomSensorSource source, int freshnessSeconds, String equipmentName,
                              RoomSensorReadings.ContactKind contactKind, String contactLocation) {
    private static final String ACTIVE_BINDINGS = """
                SELECT s.source_id,s.protocol,s.topic,s.gateway_mac,s.freshness_seconds,s.contact_kind,s.contact_location,
                       s.unified_point_id,s.first_unified_storage_ts,s.shadow_write,
                       i.identity_value,i.identity_id,i.expected_profile_code,e.equip_id,e.equip_name,e.building_id,e.space_id
                FROM biz_room_sensor_source s
                JOIN biz_device_identity i ON i.identity_id=s.identity_id AND i.status=1 AND i.identity_type='MAC'
                JOIN biz_equipment e ON e.equip_id=i.equip_id AND e.building_id=i.building_id AND e.del_flag=0
                JOIN biz_space p ON p.space_id=e.space_id AND p.building_id=e.building_id AND p.del_flag=0
                WHERE e.building_id=? AND s.enabled=1
                """;

    static Optional<RoomSensorStateBinding> find(JdbcTemplate mysql, String buildingId, String sourceId, String protocol) {
        return query(mysql, ACTIVE_BINDINGS + " AND s.source_id=? AND s.protocol=?", buildingId, sourceId, protocol)
                .stream().filter(binding -> protocol.equals(binding.source().protocol())).findFirst();
    }

    static List<RoomSensorStateBinding> forEquipment(JdbcTemplate mysql, String buildingId, String equipmentId) {
        return query(mysql, ACTIVE_BINDINGS + " AND e.equip_id=? AND s.protocol IN ('S4_DOOR','MSR01_RADAR') ORDER BY s.source_id",
                buildingId, equipmentId);
    }

    static List<RoomSensorStateBinding> forEquipmentContact(JdbcTemplate mysql, String buildingId, String equipmentId) {
        return query(mysql, ACTIVE_BINDINGS + " AND e.equip_id=? AND p.space_type='ROOM' AND s.protocol='S4_DOOR'",
                buildingId, equipmentId);
    }

    static List<RoomSensorStateBinding> findRoom(JdbcTemplate mysql, String buildingId, String spaceId, String protocol) {
        return query(mysql, ACTIVE_BINDINGS + " AND p.space_type='ROOM' AND e.space_id=? AND s.protocol=? ORDER BY s.source_id",
                buildingId, spaceId, protocol).stream().filter(binding -> protocol.equals(binding.source().protocol())).toList();
    }

    static List<RoomSensorStateBinding> findBuildingDoors(JdbcTemplate mysql, String buildingId) {
        return query(mysql, ACTIVE_BINDINGS + " AND p.space_type='ROOM' AND s.protocol='S4_DOOR' ORDER BY s.source_id", buildingId)
                .stream().filter(binding -> "S4_DOOR".equals(binding.source().protocol())).toList();
    }

    String displayName() {
        return contactLocation == null ? equipmentName : contactLocation;
    }

    private static List<RoomSensorStateBinding> query(JdbcTemplate mysql, String sql, Object... args) {
        return mysql.query(sql, (rs, row) -> {
            String protocol = rs.getString("protocol");
            if (!protocol.equals(rs.getString("expected_profile_code"))) return null;
            var source = new RoomSensorSource(rs.getString("source_id"), protocol, rs.getString("topic"),
                    rs.getString("gateway_mac").toUpperCase(Locale.ROOT),
                    rs.getString("identity_value").toUpperCase(Locale.ROOT), rs.getString("identity_id"),
                    rs.getString("equip_id"), rs.getString("building_id"), rs.getString("space_id"), 0,
                    rs.getString("unified_point_id"), rs.getObject("first_unified_storage_ts", Long.class),
                    rs.getBoolean("shadow_write"));
            String kind = rs.getString("contact_kind");
            return new RoomSensorStateBinding(source, rs.getInt("freshness_seconds"), rs.getString("equip_name"),
                    kind == null ? null : RoomSensorReadings.ContactKind.valueOf(kind), rs.getString("contact_location"));
        }, args).stream().filter(Objects::nonNull).toList();
    }
}
