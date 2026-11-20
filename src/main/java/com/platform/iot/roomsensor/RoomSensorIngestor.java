package com.platform.iot.roomsensor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.communication.CommunicationEvidenceRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.ArrayList;
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

    @Autowired
    public RoomSensorIngestor(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql,
            RoomSensorParser parser, ObjectMapper mapper, MeterRegistry meters, CommunicationEvidenceRecorder communication) {
        this(mysql, parser, mapper, meters, communication::roomSensor);
    }

    RoomSensorIngestor(JdbcTemplate mysql, RoomSensorParser parser, ObjectMapper mapper, MeterRegistry meters,
            BiConsumer<RoomSensorSource, Long> recordCommunication) {
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
        List<RoomSensorSource> communicatingSources = new ArrayList<>();
        Integer written = tx.execute(status -> {
            List<RoomSensorSource> sources = mysql.query("""
                    SELECT s.source_id,s.protocol,s.topic,s.gateway_mac,s.last_storage_ts,
                           i.identity_value,i.identity_id,i.expected_profile_code,e.equip_id,e.building_id,e.space_id
                    FROM biz_room_sensor_source s
                    JOIN biz_device_identity i ON i.identity_id=s.identity_id AND i.status=1
                      AND i.identity_type='MAC'
                    JOIN biz_equipment e ON e.equip_id=i.equip_id AND e.building_id=i.building_id AND e.del_flag=0
                    JOIN biz_space p ON p.space_id=e.space_id AND p.building_id=e.building_id AND p.del_flag=0
                    WHERE s.topic=? AND s.enabled=1 ORDER BY s.source_id FOR UPDATE
                    """, (rs, row) -> {
                        // 存量身份列与新表可能使用不同排序规则，在 Java 做协议精确比较，避免 SQL 隐式转换。
                        if (!rs.getString("protocol").equals(rs.getString("expected_profile_code"))) return null;
                        return new RoomSensorSource(rs.getString("source_id"), rs.getString("protocol"),
                                rs.getString("topic"), rs.getString("gateway_mac").toUpperCase(Locale.ROOT),
                                rs.getString("identity_value").toUpperCase(Locale.ROOT),
                                rs.getString("identity_id"), rs.getString("equip_id"), rs.getString("building_id"),
                                rs.getString("space_id"), rs.getLong("last_storage_ts"));
                    }, topic).stream().filter(Objects::nonNull).toList();
            int total = 0;
            for (RoomSensorSource source : sources) {
                // MySQL 默认排序规则不区分大小写，MQTT Topic 却区分；必须在接受观测前精确复核。
                if (!source.topic().equals(topic)) continue;
                RoomSensorParser.Parsed parsed;
                try { parsed = parser.parse(source, payload); }
                catch (IOException invalid) { count("invalid_json"); return 0; }
                if (parsed.communicationEvidence()) communicatingSources.add(source);
                var values = parsed.values();
                long storageTs = source.lastStorageTs();
                for (var value : values) {
                    // TDengine 毫秒主键需唯一。技术键在 MySQL 行锁下分配并持久化；业务时间始终用 receivedAt。
                    storageTs = Math.max(receivedAt, Math.addExact(storageTs, 1));
                    var observation = new RoomSensorObservation(UUID.randomUUID().toString(), source.sourceId(),
                            source.protocol(), storageTs, receivedAt, value.deviceTime(), source.identityId(),
                            source.equipId(), source.buildingId(), source.spaceId(), source.gatewayMac(),
                            source.deviceMac(), value.targetCount(), value.deviceSeq(), value.doorOpen());
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
        if (written != null && written > 0) meters.counter("iot.room_sensor.observations", "stage", "queued").increment(written);
        else count("no_valid_observation");
    }

    private String encode(RoomSensorObservation observation) {
        try { return mapper.writeValueAsString(observation); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Room sensor projection serialization failed", failure); }
    }

    private void count(String outcome) { meters.counter("iot.room_sensor.inbound", "outcome", outcome).increment(); }
}
