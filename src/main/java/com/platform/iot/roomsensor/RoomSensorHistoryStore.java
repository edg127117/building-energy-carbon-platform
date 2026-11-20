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
import java.util.Optional;

/** 仅访问房间传感器独立超级表；重试使用队列冻结主键，读回确认后才能删除 MySQL 待投递项。 */
@Repository
@ConditionalOnProperty(name = "room-sensor.enabled", havingValue = "true")
public class RoomSensorHistoryStore {
    // 技术主键可能在同毫秒时顺延；最近状态按实际接收时间选取。
    private static final String LATEST_BY_RECEIVED_AT = " ORDER BY received_at DESC,ts DESC LIMIT 1";
    static final int RANGE_SCAN_LIMIT = 25_000;

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

    record DoorReading(long receivedAt, boolean open) { }
    record RadarReading(long receivedAt, int targetCount) { }

    Optional<DoorReading> latestDoor(RoomSensorSource source, long asOf) {
        String sql = "SELECT received_at,door_open FROM " + database + ".st_door_observation"
                + observationScope(source, asOf) + LATEST_BY_RECEIVED_AT;
        return td.query(sql, (rs, row) -> new DoorReading(rs.getLong("received_at"), rs.getBoolean("door_open")))
                .stream().findFirst();
    }

    Optional<RadarReading> latestRadar(RoomSensorSource source, long asOf) {
        String sql = "SELECT received_at,target_count FROM " + database + ".st_radar_observation"
                + observationScope(source, asOf) + " AND target_count IS NOT NULL" + LATEST_BY_RECEIVED_AT;
        return td.query(sql, (rs, row) -> new RadarReading(rs.getLong("received_at"), rs.getInt("target_count")))
                .stream().findFirst();
    }

    record Observation(long receivedAt, Integer count, Boolean open) { }

    List<Observation> range(RoomSensorSource source, long start, long end) {
        boolean radar = "MSR01_RADAR".equals(source.protocol());
        String column = radar ? "target_count" : "door_open";
        String sql = "SELECT received_at," + column + " FROM " + database + "." + stable(source.protocol())
                + observationScope(source, end - 1) + " AND received_at>=" + start + " AND " + column + " IS NOT NULL"
                + " ORDER BY received_at DESC,ts DESC LIMIT " + (RANGE_SCAN_LIMIT + 1);
        return td.query(sql, (rs, row) -> new Observation(rs.getLong("received_at"),
                radar ? rs.getInt("target_count") : null, radar ? null : rs.getBoolean("door_open")));
    }

    private static String observationScope(RoomSensorSource source, long asOf) {
        return " WHERE source_id=" + quote(source.sourceId()) + " AND building_id=" + quote(source.buildingId())
                + " AND identity_id=" + quote(source.identityId()) + " AND equip_id=" + quote(source.equipId())
                + " AND space_id=" + quote(source.spaceId()) + " AND gateway_mac=" + quote(source.gatewayMac())
                + " AND device_mac=" + quote(source.deviceMac()) + " AND received_at<=" + asOf;
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
