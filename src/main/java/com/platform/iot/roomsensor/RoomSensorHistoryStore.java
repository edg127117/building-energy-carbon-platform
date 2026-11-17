package com.platform.iot.roomsensor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.TdengineProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 仅访问房间传感器独立超级表；重试使用队列冻结主键，读回确认后才能删除 MySQL 待投递项。 */
@Repository
@ConditionalOnProperty(name = "room-sensor.enabled", havingValue = "true")
public class RoomSensorHistoryStore {
    private final JdbcTemplate td;
    private final String database;
    private final ObjectMapper mapper;

    public RoomSensorHistoryStore(@Qualifier("taosJdbcTemplate") JdbcTemplate td,
            TdengineProperties properties, ObjectMapper mapper) {
        this.td = td;
        this.database = identifier(properties.getDatabase());
        this.mapper = mapper;
    }

    public void write(RoomSensorObservation event) {
        String stable = stable(event.protocol());
        String table = database + ".rs_" + identifier(event.sourceId());
        String hash = hash(event);
        // INSERT USING 只会创建本模块子表，超级表必须在受控迁移中预建；不做运行期超级表 DDL。
        String existsSql = "SELECT event_id,data_hash FROM " + database + "." + stable
                + " WHERE source_id=" + quote(event.sourceId()) + " AND ts=" + event.storageTs() + " LIMIT 2";
        List<Map<String, Object>> existing = td.queryForList(existsSql);
        if (!existing.isEmpty()) { verify(existing, event, hash); return; }
        String metricColumns = "MSR01_RADAR".equals(event.protocol()) ? "target_count,device_seq" : "door_open";
        String metricValues = "MSR01_RADAR".equals(event.protocol())
                ? event.targetCount() + "," + event.deviceSeq() : Boolean.TRUE.equals(event.doorOpen()) ? "true" : "false";
        td.execute("INSERT INTO " + table + " USING " + database + "." + stable + " TAGS (" + quote(event.sourceId())
                + ") (ts,received_at,device_time,event_id,data_hash,identity_id,equip_id,building_id,space_id,gateway_mac,device_mac,"
                + metricColumns + ") VALUES (" + event.storageTs() + "," + event.receivedAt() + ","
                + quote(event.deviceTime()) + "," + quote(event.eventId()) + "," + quote(hash) + ","
                + quote(event.identityId()) + "," + quote(event.equipId()) + "," + quote(event.buildingId()) + ","
                + quote(event.spaceId()) + "," + quote(event.gatewayMac()) + "," + quote(event.deviceMac()) + "," + metricValues + ")");
        verify(td.queryForList(existsSql), event, hash);
    }

    /** 只删除本模块两个超级表的过期行，不修改全库 KEEP 或其它遥测数据。 */
    public void deleteBefore(long cutoff) {
        for (String stable : List.of("st_radar_observation", "st_door_observation")) {
            td.execute("DELETE FROM " + database + "." + stable + " WHERE ts < " + cutoff);
        }
    }

    private void verify(List<Map<String, Object>> rows, RoomSensorObservation event, String hash) {
        if (rows.size() != 1 || !event.eventId().equals(rows.getFirst().get("event_id"))
                || !hash.equals(rows.getFirst().get("data_hash"))) {
            throw new IllegalStateException("Room sensor immutable write verification failed");
        }
    }

    private String hash(RoomSensorObservation event) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | JsonProcessingException failure) {
            throw new IllegalStateException("Room sensor projection digest failed", failure);
        }
    }

    private static String stable(String protocol) {
        return switch (protocol) {
            case "MSR01_RADAR" -> "st_radar_observation";
            case "S4_DOOR" -> "st_door_observation";
            default -> throw new IllegalArgumentException("Unsupported room sensor protocol");
        };
    }

    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{1,64}")) throw new IllegalArgumentException("Unsafe room sensor identifier");
        return value;
    }

    private static String quote(String value) {
        return value == null ? "NULL" : "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }
}
