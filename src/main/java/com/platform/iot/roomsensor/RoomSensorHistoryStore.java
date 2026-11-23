package com.platform.iot.roomsensor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.TdengineProperties;
import com.platform.framework.exception.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** 仅访问房间传感器独立超级表；重试使用队列冻结主键，读回确认后才能删除 MySQL 待投递项。 */
@Repository
@ConditionalOnProperty(name = "room-sensor.enabled", havingValue = "true")
public class RoomSensorHistoryStore {
    // 技术主键可能在同毫秒时顺延；最近状态按实际接收时间选取。
    private static final String LATEST_BY_RECEIVED_AT = " ORDER BY received_at DESC,ts DESC LIMIT 1";
    static final int RANGE_SCAN_LIMIT = 25_000;

    private static final int MAX_RANGE_ROWS = 250_000;
    private static final RowMapper<DoorReading> DOOR_READING =
            (rs, row) -> new DoorReading(rs.getLong("received_at"), rs.getBoolean("door_open"));
    private static final RowMapper<RadarReading> RADAR_READING =
            (rs, row) -> new RadarReading(rs.getLong("received_at"), rs.getInt("target_count"));

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
                + observationScope(source, asOf) + " AND door_open IS NOT NULL" + LATEST_BY_RECEIVED_AT;
        return td.query(sql, (rs, row) -> new DoorReading(rs.getLong("received_at"), rs.getBoolean("door_open")))
                .stream().findFirst();
    }

    Map<String, DoorReading> recentDoors(List<RoomSensorStateBinding> bindings, long asOf) {
        return doorReadings(bindings, asOf, true);
    }

    Map<String, DoorReading> lastDoors(List<RoomSensorStateBinding> bindings, long asOf) {
        return doorReadings(bindings, asOf, false);
    }

    private Map<String, DoorReading> doorReadings(List<RoomSensorStateBinding> bindings, long asOf, boolean freshOnly) {
        var readings = new HashMap<String, DoorReading>();
        for (int first = 0; first < bindings.size(); first += 32) {
            var batch = bindings.subList(first, Math.min(first + 32, bindings.size()));
            // 每个来源独立按业务时间取一行，保留技术键顺延时与房间详情相同的判定。
            String sql = IntStream.range(0, batch.size()).mapToObj(index -> {
                var binding = batch.get(index);
                long cutoff = freshOnly ? asOf - binding.freshnessSeconds() * 1000L + 1
                        : asOf - RoomSensorWorker.RETENTION_MILLIS;
                return "SELECT source_id,received_at,door_open FROM (SELECT source_id,received_at,door_open FROM "
                        + database + ".st_door_observation" + observationScope(binding.source(), asOf)
                        + " AND ts>=" + cutoff + " AND received_at>=" + cutoff + " AND door_open IS NOT NULL"
                        + LATEST_BY_RECEIVED_AT + ") state" + index;
            }).collect(Collectors.joining(" UNION ALL "));
            td.query(sql, rs -> {
                readings.put(rs.getString("source_id"), DOOR_READING.mapRow(rs, 0));
            });
        }
        return readings;
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

    List<RadarReading> radarHistory(RoomSensorSource source, long from, long asOf) {
        String sql = "SELECT received_at,target_count FROM " + database + ".st_radar_observation"
                + observationScope(source, asOf) + " AND target_count>=0";
        return range(sql, RADAR_READING, from);
    }

    List<DoorReading> doorHistory(RoomSensorSource source, long from, long asOf) {
        String sql = "SELECT received_at,door_open FROM " + database + ".st_door_observation"
                + observationScope(source, asOf) + " AND door_open IS NOT NULL";
        return range(sql, DOOR_READING, from);
    }

    private <T> List<T> range(String sql, RowMapper<T> mapper, long from) {
        // ts 只能作为扫描下界。顺延后的技术键可能晚于查询截止点。
        var rows = td.query(sql + " AND ts>=" + from + " AND received_at>=" + from
                + " ORDER BY received_at,ts LIMIT " + (MAX_RANGE_ROWS + 1), mapper);
        if (rows.size() > MAX_RANGE_ROWS) {
            throw new BusinessException(409, "ROOM_SENSOR_HISTORY_LIMIT", "房间传感器历史超过查询上限");
        }
        return rows;
    }

    Optional<DoorReading> lastClosedDoor(RoomSensorSource source, long asOf) {
        String sql = "SELECT received_at,door_open FROM " + database + ".st_door_observation"
                + observationScope(source, asOf) + " AND door_open=false" + LATEST_BY_RECEIVED_AT;
        return td.query(sql, DOOR_READING).stream().findFirst();
    }

    Optional<DoorReading> openingAfterClose(RoomSensorSource source, long closedAt, long asOf) {
        // 同毫秒出现相反状态时，不推断开门起点；后续明确的开启观测才进入计时。
        String sql = "SELECT received_at,door_open FROM " + database + ".st_door_observation"
                + observationScope(source, asOf) + " AND door_open=true AND ts>=" + closedAt
                + " AND received_at>" + closedAt + " ORDER BY received_at,ts LIMIT 1";
        return td.query(sql, DOOR_READING).stream().findFirst();
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
