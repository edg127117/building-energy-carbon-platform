package com.platform.iot.roomsensor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.communication.CommunicationEvidenceRecorder;
import com.platform.iot.identity.DeviceIdentityKey;
import com.platform.iot.ingest.standard.StandardMetric;
import com.platform.iot.ingest.standard.StandardTelemetryMessage;
import com.platform.iot.onboarding.PendingDeviceDiscoveryService;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

/** MQTT 厂家适配入口；只在可信绑定下将裁剪后的观测提交 MySQL，随后由后台投递 TDengine。 */
@Service
@ConditionalOnProperty(name = "room-sensor.enabled", havingValue = "true")
public class RoomSensorIngestor {
    private final JdbcTemplate mysql;
    private final TransactionTemplate tx;
    private final RoomSensorParser parser;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final BiConsumer<RoomSensorSource, Long> recordCommunication;
    private final PendingDeviceDiscoveryService discovery;

    @Autowired
    public RoomSensorIngestor(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql,
            RoomSensorParser parser, ObjectMapper mapper, MeterRegistry meters, CommunicationEvidenceRecorder communication,
            ObjectProvider<PendingDeviceDiscoveryService> discovery) {
        this(mysql, parser, mapper, meters, communication::roomSensor, discovery.getIfAvailable());
    }

    RoomSensorIngestor(JdbcTemplate mysql, RoomSensorParser parser, ObjectMapper mapper, MeterRegistry meters,
            BiConsumer<RoomSensorSource, Long> recordCommunication) {
        this(mysql, parser, mapper, meters, recordCommunication, null);
    }

    RoomSensorIngestor(JdbcTemplate mysql, RoomSensorParser parser, ObjectMapper mapper, MeterRegistry meters,
            BiConsumer<RoomSensorSource, Long> recordCommunication, PendingDeviceDiscoveryService discovery) {
        this.discovery = discovery;
        this.mysql = mysql;
        this.parser = parser;
        this.mapper = mapper;
        this.meters = meters;
        this.recordCommunication = recordCommunication;
        tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(mysql.getDataSource())));
    }

    public void accept(String topic, byte[] payload, long receivedAt, boolean retained) {
        // 保留消息不能充当本次观测或通讯证据。
        if (retained) { count("retained_ignored"); return; }
        RoomSensorParser.Frame frame;
        try { frame = parser.decode(payload); }
        catch (IOException invalid) { count("invalid_json"); return; }
        if (frame.gatewayMac().isEmpty() || !topic.equals(RoomSensorParser.topic(frame.protocol(), frame.gatewayMac()))) {
            count("invalid_gateway_or_topic");
            return;
        }
        List<RoomSensorSource> communicatingSources = new ArrayList<>();
        var matched = new HashSet<String>();
        Integer written = tx.execute(status -> {
            List<RoomSensorSource> sources = mysql.query("""
                    SELECT s.source_id,s.protocol,s.topic,s.gateway_mac,s.last_storage_ts,
                           s.unified_point_id,s.first_unified_storage_ts,s.shadow_write,
                           i.identity_value,i.identity_id,i.expected_profile_code,e.equip_id,e.building_id,e.space_id
                    FROM biz_room_sensor_source s
                    JOIN biz_device_identity i ON i.identity_id=s.identity_id AND i.status=1
                      AND i.identity_type='MAC'
                    JOIN biz_equipment e ON e.equip_id=i.equip_id AND e.building_id=i.building_id AND e.del_flag=0
                    JOIN biz_space p ON p.space_id=e.space_id AND p.building_id=e.building_id AND p.del_flag=0
                       AND (s.first_unified_storage_ts IS NULL OR p.space_type='ROOM')
                    WHERE s.topic=? AND s.enabled=1 ORDER BY s.source_id FOR UPDATE
                    """, (rs, row) -> {
                        // 存量身份列与新表可能使用不同排序规则，在 Java 做协议精确比较，避免 SQL 隐式转换。
                        if (!rs.getString("protocol").equals(rs.getString("expected_profile_code"))) return null;
                        return new RoomSensorSource(rs.getString("source_id"), rs.getString("protocol"),
                                rs.getString("topic"), rs.getString("gateway_mac").toUpperCase(Locale.ROOT),
                                rs.getString("identity_value").toUpperCase(Locale.ROOT),
                                rs.getString("identity_id"), rs.getString("equip_id"), rs.getString("building_id"),
                                rs.getString("space_id"), rs.getLong("last_storage_ts"),
                                rs.getString("unified_point_id"), rs.getObject("first_unified_storage_ts", Long.class),
                                rs.getBoolean("shadow_write"));
                    }, topic).stream().filter(Objects::nonNull).toList();
            int total = 0;
            for (RoomSensorSource source : sources) {
                // MySQL 默认排序规则不区分大小写，MQTT Topic 却区分；必须在接受观测前精确复核。
                if (!source.topic().equals(topic) || !source.protocol().equals(frame.protocol())
                        || !source.gatewayMac().equals(frame.gatewayMac())) continue;
                var parsed = frame.devices().get(source.deviceMac());
                if (parsed == null) continue;
                matched.add(source.deviceMac());
                boolean unified = source.firstUnifiedStorageTs() != null;
                if (unified && "S4_DOOR".equals(source.protocol()) && !gatewayStillInRoom(source)) {
                    count("gateway_binding_mismatch");
                    continue;
                }
                if (parsed.communicationEvidence()) communicatingSources.add(source);
                if (parsed.values().isEmpty()) continue;
                var point = unified || source.shadowWrite() ? unifiedPoint(source) : null;
                if ((unified || source.shadowWrite()) && point == null) {
                    count("unified_point_unavailable");
                    if (unified) continue;
                }
                var values = parsed.values();
                long storageTs = source.lastStorageTs();
                for (var value : values) {
                    // TDengine 毫秒主键需唯一。技术键在 MySQL 行锁下分配并持久化；业务时间始终用 receivedAt。
                    storageTs = Math.max(receivedAt, Math.addExact(storageTs, 1));
                    var observation = new RoomSensorObservation(UUID.randomUUID().toString(), source.sourceId(),
                            source.protocol(), storageTs, receivedAt, value.deviceTime(), source.identityId(),
                            source.equipId(), source.buildingId(), source.spaceId(), source.gatewayMac(),
                            source.deviceMac(), value.targetCount(), value.deviceSeq(), value.doorOpen(),
                            point == null ? null : point.event(value, storageTs, receivedAt), unified ? Boolean.TRUE : null);
                    mysql.update("""
                            INSERT INTO biz_room_sensor_ingest_pending
                            (event_id,source_id,storage_ts,received_at,observation_json) VALUES (?,?,?,?,?)
                            """, observation.eventId(), source.sourceId(), storageTs, receivedAt, encode(observation));
                    total++;
                }
                if (!values.isEmpty()) mysql.update(
                        "UPDATE biz_room_sensor_source SET last_storage_ts=? WHERE source_id=?", storageTs, source.sourceId());
            }
            return total;
        });
        // 先提交房间观测，再记录通讯证据，避免与通讯状态机的身份锁逆序。
        communicatingSources.forEach(source -> recordCommunication.accept(source, receivedAt));
        if (discovery != null) frame.devices().forEach((mac, parsed) -> {
            if (!matched.contains(mac) && !parsed.values().isEmpty()) discover(frame, mac, parsed.values().getLast(), receivedAt, topic);
        });
        if (written != null && written > 0) meters.counter("iot.room_sensor.observations", "stage", "queued").increment(written);
        else count("no_valid_observation");
    }

    private void discover(RoomSensorParser.Frame frame, String mac, RoomSensorParser.Value value,
            long receivedAt, String topic) {
        boolean radar = "MSR01_RADAR".equals(frame.protocol());
        var metric = radar ? new StandardMetric("targetCount", BigDecimal.valueOf(value.targetCount()), "人", "radar.num")
                : new StandardMetric("doorOpen", BigDecimal.valueOf(Boolean.TRUE.equals(value.doorOpen()) ? 1 : 0), "-", "CA03");
        var message = new StandardTelemetryMessage("1.0", frame.protocol(), 1, new DeviceIdentityKey("MAC", mac),
                receivedAt, receivedAt, "SERVER_RECEIVED", radar ? value.deviceSeq().longValue() : null, List.of(metric));
        try {
            Integer known = mysql.queryForObject("SELECT COUNT(*) FROM biz_device_identity WHERE identity_type='MAC' AND identity_value=?",
                    Integer.class, mac);
            if (known != null && known > 0) return;
            discovery.discoverRoomSensor(message, receivedAt, frame.gatewayMac(), topic);
        } catch (RuntimeException unavailable) {
            // 已有设备可能已提交待投递项，不因发现区写失败而重投整条网关报文。
            count("discovery_retry_exhausted");
        }
    }

    private boolean gatewayStillInRoom(RoomSensorSource source) {
        return !mysql.queryForList("""
                SELECT g.source_id FROM biz_room_sensor_source g
                JOIN biz_device_identity i ON i.identity_id=g.identity_id AND i.status=1
                    AND i.identity_type='MAC' AND i.expected_profile_code='MSR01_RADAR'
                JOIN biz_equipment e ON e.equip_id=i.equip_id AND e.building_id=i.building_id AND e.del_flag=0
                JOIN biz_space r ON r.space_id=e.space_id AND r.building_id=e.building_id
                    AND r.space_type='ROOM' AND r.del_flag=0
                WHERE g.gateway_mac=? AND i.identity_value=? AND g.protocol='MSR01_RADAR' AND g.enabled=1
                    AND e.building_id=? AND e.space_id=? FOR UPDATE
                """, String.class, source.gatewayMac(), source.gatewayMac(), source.buildingId(), source.spaceId()).isEmpty();
    }

    private BoundPoint unifiedPoint(RoomSensorSource source) {
        boolean radar = "MSR01_RADAR".equals(source.protocol());
        return mysql.query("""
                SELECT p.point_code,p.system_group_id,e.equip_code,p.family_code,p.component_code,p.suffix_code,
                       a.source_point_code
                FROM biz_data_point p
                JOIN biz_equipment e ON e.equip_id=p.equip_id AND e.building_id=p.building_id AND e.del_flag=0
                JOIN biz_point_alias a ON a.point_id=p.point_id AND a.building_id=p.building_id AND a.status=1
                JOIN biz_data_source d ON d.source_id=a.source_id AND d.building_id=a.building_id AND d.status='ENABLED'
                WHERE p.point_id=? AND p.equip_id=? AND p.building_id=? AND p.del_flag=0
                    AND p.is_for_calc=0 AND p.status='ONLINE' AND p.data_type=? AND p.unit=?
                    AND a.source_point_code=? AND a.source_system=? FOR UPDATE
                """, (r, n) -> new BoundPoint(source, r.getString("point_code"), r.getString("system_group_id"),
                r.getString("equip_code"), r.getString("family_code"), r.getString("component_code"),
                r.getString("suffix_code"), r.getString("source_point_code")),
                source.unifiedPointId(), source.equipId(), source.buildingId(), radar ? "ANALOG" : "DIGITAL",
                radar ? "人" : "-", "MAC:" + source.deviceMac() + (radar ? ":targetCount" : ":doorOpen"),
                sourceSystem).stream().findFirst().orElse(null);
    }

    @org.springframework.beans.factory.annotation.Value("${ingestion.standard-source-system:MQTT_STANDARD_V1}")
    private String sourceSystem = "MQTT_STANDARD_V1";

    private record BoundPoint(RoomSensorSource source, String pointCode, String groupId, String equipmentCode,
            String family, String component, String suffix, String sourcePointCode) {
        RawTelemetryEvent event(RoomSensorParser.Value value, long storageTs, long receivedAt) {
            double val = value.targetCount() != null ? value.targetCount() : Boolean.TRUE.equals(value.doorOpen()) ? 1 : 0;
            return new RawTelemetryEvent(source.unifiedPointId(), pointCode, "MQTT_ROOM_SENSOR", sourcePointCode,
                    source.deviceMac(), source.buildingId(), groupId, source.equipId(), equipmentCode,
                    family, component, suffix, val, storageTs, receivedAt, 0, 0, false);
        }
    }

    private String encode(RoomSensorObservation observation) {
        try { return mapper.writeValueAsString(observation); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Room sensor projection serialization failed", failure); }
    }

    private void count(String outcome) { meters.counter("iot.room_sensor.inbound", "outcome", outcome).increment(); }
}
