package com.platform.iot.roomsensor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.TdengineProperties;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.temporal.impl.TdengineHvacRawEventRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** 过渡期按已冻结的切换主键拼接原生历史与标准测点历史。 */
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
    private final String rawStable;
    private final TdengineHvacRawEventRepository raw;
    private boolean legacyHistoryEnabled = true;

    public RoomSensorHistoryStore(JdbcTemplate td, TdengineProperties properties, ObjectMapper mapper) {
        this(td, properties, mapper, null);
    }

    @Autowired
    public RoomSensorHistoryStore(@Qualifier("taosJdbcTemplate") JdbcTemplate td,
            TdengineProperties properties, ObjectMapper mapper, ObjectProvider<TdengineHvacRawEventRepository> raw) {
        this.td = td;
        this.database = identifier(properties.getDatabase());
        this.rawStable = identifier(properties.getStRawEvent());
        this.mapper = mapper;
        this.raw = raw == null ? null : raw.getIfAvailable();
    }

    @org.springframework.beans.factory.annotation.Value("${room-sensor.legacy-history-enabled:true}")
    void configureLegacyHistory(boolean enabled) {
        legacyHistoryEnabled = enabled;
    }

    public void write(RoomSensorObservation event) {
        if (!Boolean.TRUE.equals(event.unifiedOnly())) {
            if (!legacyHistoryEnabled) throw new IllegalStateException("Legacy room observation still pending");
            writeLegacy(event);
        }
        if (Boolean.TRUE.equals(event.unifiedOnly()) && event.unifiedEvent() == null) {
            throw new IllegalStateException("Unified room observation has no frozen point");
        }
        if (event.unifiedEvent() != null) {
            if (raw == null) throw new IllegalStateException("Standard raw storage unavailable");
            raw.writeRoomObservation(event.unifiedEvent(), event.scope(), storedSnapshot(event), event.doorOpen());
        }
    }

    private String storedSnapshot(RoomSensorObservation event) {
        try {
            // 可逆编码保留厂家原始时钟和整条归属快照，也避免字符串转义引入差异。
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(event));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Room observation snapshot unavailable", failure);
        }
    }

    private void writeLegacy(RoomSensorObservation event) {
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

    /** 清理旧观测与标准超级表中的房间专用子表，保留其它测点的历史。 */
    public void deleteBefore(long cutoff) {
        if (legacyHistoryEnabled) for (String stable : List.of("st_radar_observation", "st_door_observation")) {
            td.execute("DELETE FROM " + database + "." + stable + " WHERE ts < " + cutoff);
        }
        if (raw != null) {
            var children = td.queryForList("SELECT table_name FROM information_schema.ins_tables WHERE db_name="
                    + quote(database) + " AND stable_name=" + quote(rawStable) + " AND table_name LIKE 'rsraw_%'", String.class);
            for (String child : children) {
                if (child.matches("rsraw_[a-z0-9_]{1,32}"))
                    td.execute("DELETE FROM " + database + "." + child + " WHERE ts < " + cutoff);
            }
        }
    }

    record DoorReading(long receivedAt, boolean open) { }
    record RadarReading(long receivedAt, int targetCount) { }

    Optional<DoorReading> latestDoor(RoomSensorSource source, long asOf) {
        String sql = "SELECT received_at,door_open FROM " + database + ".st_door_observation"
                + observationScope(source, asOf) + " AND door_open IS NOT NULL" + LATEST_BY_RECEIVED_AT;
        var old = legacy(source, sql, DOOR_READING).stream().findFirst();
        var current = unified(source, asOf, "received_at,door_open", " AND door_open IS NOT NULL",
                LATEST_BY_RECEIVED_AT, DOOR_READING).stream().findFirst();
        return latest(old, current, DoorReading::receivedAt);
    }

    Map<String, DoorReading> recentDoors(List<RoomSensorStateBinding> bindings, long asOf) {
        return doorReadings(bindings, asOf, true);
    }

    Map<String, DoorReading> lastDoors(List<RoomSensorStateBinding> bindings, long asOf) {
        return doorReadings(bindings, asOf, false);
    }

    private Map<String, DoorReading> doorReadings(List<RoomSensorStateBinding> bindings, long asOf, boolean freshOnly) {
        var readings = new HashMap<String, DoorReading>();
        for (var binding : bindings) {
            var source = binding.source();
            long cutoff = doorCutoff(binding, freshOnly, asOf);
            unified(source, asOf, "received_at,door_open", " AND ts>=" + cutoff + " AND received_at>=" + cutoff
                    + " AND door_open IS NOT NULL", LATEST_BY_RECEIVED_AT, DOOR_READING).stream().findFirst()
                    .ifPresent(reading -> readings.put(source.sourceId(), reading));
        }
        bindings = bindings.stream().filter(binding -> readsLegacy(binding.source())).toList();
        for (int first = 0; first < bindings.size(); first += 32) {
            var batch = bindings.subList(first, Math.min(first + 32, bindings.size()));
            // 每个来源独立按业务时间取一行，保留技术键顺延时与房间详情相同的判定。
            String sql = IntStream.range(0, batch.size()).mapToObj(index -> {
                var binding = batch.get(index);
                long cutoff = doorCutoff(binding, freshOnly, asOf);
                return "SELECT source_id,received_at,door_open FROM (SELECT source_id,received_at,door_open FROM "
                        + database + ".st_door_observation" + observationScope(binding.source(), asOf)
                        + " AND ts>=" + cutoff + " AND received_at>=" + cutoff + " AND door_open IS NOT NULL"
                        + LATEST_BY_RECEIVED_AT + ") state" + index;
            }).collect(Collectors.joining(" UNION ALL "));
            td.query(sql, rs -> {
                var historical = DOOR_READING.mapRow(rs, 0);
                readings.merge(rs.getString("source_id"), historical,
                        (current, old) -> current.receivedAt() >= old.receivedAt() ? current : old);
            });
        }
        return readings;
    }

    private static long doorCutoff(RoomSensorStateBinding binding, boolean freshOnly, long asOf) {
        return freshOnly ? asOf - binding.freshnessSeconds() * 1000L + 1 : asOf - RoomSensorWorker.RETENTION_MILLIS;
    }

    Optional<RadarReading> latestRadar(RoomSensorSource source, long asOf) {
        String sql = "SELECT received_at,target_count FROM " + database + ".st_radar_observation"
                + observationScope(source, asOf) + " AND target_count IS NOT NULL" + LATEST_BY_RECEIVED_AT;
        var old = legacy(source, sql, RADAR_READING).stream().findFirst();
        var current = unified(source, asOf, "received_at,target_count", " AND target_count IS NOT NULL",
                LATEST_BY_RECEIVED_AT, RADAR_READING).stream().findFirst();
        return latest(old, current, RadarReading::receivedAt);
    }

    record Observation(long receivedAt, Integer count, Boolean open) { }

    List<Observation> range(RoomSensorSource source, long start, long end) {
        boolean radar = "MSR01_RADAR".equals(source.protocol());
        String column = radar ? "target_count" : "door_open";
        String sql = "SELECT received_at," + column + " FROM " + database + "." + stable(source.protocol())
                + observationScope(source, end - 1) + " AND received_at>=" + start + " AND " + column + " IS NOT NULL"
                + " ORDER BY received_at DESC,ts DESC LIMIT " + (RANGE_SCAN_LIMIT + 1);
        RowMapper<Observation> reading = (rs, row) -> new Observation(rs.getLong("received_at"),
                radar ? rs.getInt("target_count") : null, radar ? null : rs.getBoolean("door_open"));
        var old = legacy(source, sql, reading);
        var current = unified(source, end - 1, "received_at," + column,
                " AND received_at>=" + start + " AND " + column + " IS NOT NULL",
                " ORDER BY received_at DESC,ts DESC LIMIT " + (RANGE_SCAN_LIMIT + 1), reading);
        return Stream.concat(current.stream(), old.stream())
                .sorted(Comparator.comparingLong(Observation::receivedAt).reversed())
                .limit(RANGE_SCAN_LIMIT + 1L).toList();
    }

    List<RadarReading> radarHistory(RoomSensorSource source, long from, long asOf) {
        String sql = "SELECT received_at,target_count FROM " + database + ".st_radar_observation"
                + observationScope(source, asOf) + " AND target_count>=0";
        return mergedRange(source, from, asOf, sql, "target_count", ">=0", RADAR_READING, RadarReading::receivedAt);
    }

    List<DoorReading> doorHistory(RoomSensorSource source, long from, long asOf) {
        String sql = "SELECT received_at,door_open FROM " + database + ".st_door_observation"
                + observationScope(source, asOf) + " AND door_open IS NOT NULL";
        return mergedRange(source, from, asOf, sql, "door_open", " IS NOT NULL", DOOR_READING, DoorReading::receivedAt);
    }

    private <T> List<T> mergedRange(RoomSensorSource source, long from, long asOf, String legacySql,
            String column, String condition, RowMapper<T> mapper, ToLongFunction<T> time) {
        // ts 只能作为扫描下界。顺延后的技术键可能晚于查询截止点。
        String bounds = " AND ts>=" + from + " AND received_at>=" + from;
        String order = " ORDER BY received_at,ts LIMIT " + (MAX_RANGE_ROWS + 1);
        var old = legacy(source, legacySql + bounds + order, mapper);
        var current = unified(source, asOf, "received_at," + column, " AND " + column + condition + bounds, order, mapper);
        if (old.size() + current.size() > MAX_RANGE_ROWS) {
            throw new BusinessException(409, "ROOM_SENSOR_HISTORY_LIMIT", "房间传感器历史超过查询上限");
        }
        return Stream.concat(old.stream(), current.stream()).sorted(Comparator.comparingLong(time)).toList();
    }

    Optional<DoorReading> lastClosedDoor(RoomSensorSource source, long asOf) {
        String sql = "SELECT received_at,door_open FROM " + database + ".st_door_observation"
                + observationScope(source, asOf) + " AND door_open=false" + LATEST_BY_RECEIVED_AT;
        var old = legacy(source, sql, DOOR_READING).stream().findFirst();
        var current = unified(source, asOf, "received_at,door_open", " AND door_open=false",
                LATEST_BY_RECEIVED_AT, DOOR_READING).stream().findFirst();
        return latest(old, current, DoorReading::receivedAt);
    }

    Optional<DoorReading> openingAfterClose(RoomSensorSource source, long closedAt, long asOf) {
        // 同毫秒出现相反状态时，不推断开门起点；后续明确的开启观测才进入计时。
        String sql = "SELECT received_at,door_open FROM " + database + ".st_door_observation"
                + observationScope(source, asOf) + " AND door_open=true AND ts>=" + closedAt
                + " AND received_at>" + closedAt + " ORDER BY received_at,ts LIMIT 1";
        var old = legacy(source, sql, DOOR_READING).stream().findFirst();
        var current = unified(source, asOf, "received_at,door_open",
                " AND door_open=true AND ts>=" + closedAt + " AND received_at>" + closedAt,
                " ORDER BY received_at,ts LIMIT 1", DOOR_READING).stream().findFirst();
        return latest(current, old, reading -> -reading.receivedAt());
    }

    private static <T> Optional<T> latest(Optional<T> old, Optional<T> current, ToLongFunction<T> time) {
        if (old.isEmpty()) return current;
        if (current.isEmpty() || time.applyAsLong(old.get()) > time.applyAsLong(current.get())) return old;
        return current;
    }

    private boolean readsLegacy(RoomSensorSource source) {
        return legacyHistoryEnabled && !Long.valueOf(0L).equals(source.firstUnifiedStorageTs());
    }

    private <T> List<T> legacy(RoomSensorSource source, String sql, RowMapper<T> mapper) {
        return readsLegacy(source) ? td.query(sql, mapper) : List.of();
    }

    private <T> List<T> unified(RoomSensorSource source, long asOf, String columns, String conditions,
            String order, RowMapper<T> mapper) {
        Long first = source.firstUnifiedStorageTs();
        if (first == null || source.unifiedPointId() == null) return List.of();
        String scope = scope(source.sourceId(), source.identityId(), source.equipId(), source.buildingId(),
                source.spaceId(), source.gatewayMac(), source.deviceMac());
        // TDengine REST 驱动的 getInt 不接受 DOUBLE 返回的 "5.0"，人数在查询端还原为整数。
        String selected = columns.replace("received_at", "room_received_at AS received_at")
                .replace("door_open", "room_door_open AS door_open")
                .replace("target_count", "CAST(val AS INT) AS target_count");
        String filter = conditions.replace("received_at", "room_received_at").replace("door_open", "room_door_open")
                .replace("target_count", "val");
        return td.query("SELECT " + selected + " FROM " + database + "." + rawStable
                + " WHERE point_id=" + quote(source.unifiedPointId()) + " AND ts>=" + first
                + " AND room_scope=" + quote(scope) + " AND room_received_at<=" + asOf + filter
                + order.replace("received_at", "room_received_at"), mapper);
    }

    static String scope(String sourceId, String identityId, String equipId, String buildingId,
            String spaceId, String gatewayMac, String deviceMac) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\0", sourceId, identityId, equipId, buildingId, spaceId, gatewayMac, deviceMac)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Room observation scope digest unavailable", failure);
        }
    }

    private static String observationScope(RoomSensorSource source, long asOf) {
        return " WHERE source_id=" + quote(source.sourceId()) + " AND building_id=" + quote(source.buildingId())
                + " AND identity_id=" + quote(source.identityId()) + " AND equip_id=" + quote(source.equipId())
                + " AND space_id=" + quote(source.spaceId()) + " AND gateway_mac=" + quote(source.gatewayMac())
                + " AND device_mac=" + quote(source.deviceMac()) + " AND received_at<=" + asOf
                + (source.firstUnifiedStorageTs() == null ? "" : " AND ts<" + source.firstUnifiedStorageTs());
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
