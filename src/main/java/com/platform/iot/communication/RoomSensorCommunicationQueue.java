package com.platform.iot.communication;

import com.platform.iot.roomsensor.RoomSensorQueueScheduler.DrainResult;
import com.platform.iot.roomsensor.RoomSensorSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.UUID;

import static com.platform.iot.communication.CommunicationModels.Signal;

/** 随观测持久化房间传感器的通讯证据，独立于 TDengine 投递批量更新通讯状态。 */
@Component
@ConditionalOnProperty(name = "room-sensor.enabled", havingValue = "true")
public class RoomSensorCommunicationQueue {
    private static final Logger log = LoggerFactory.getLogger(RoomSensorCommunicationQueue.class);
    private static final String SOURCE = "MQTT";
    private static final int BATCH_SIZE = 32;
    private final JdbcTemplate mysql;
    private final TransactionTemplate transaction;
    private final DeviceCommunicationService communication;
    private final CommunicationEvidenceRecorder recorder;

    public RoomSensorCommunicationQueue(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql,
            PlatformTransactionManager manager, DeviceCommunicationService communication,
            CommunicationEvidenceRecorder recorder) {
        this.mysql = mysql;
        this.communication = communication;
        this.recorder = recorder;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 由接入事务调用。使用回调接收时间计算通讯超时，设备时间只供观测归档。 */
    public boolean enqueue(RoomSensorSource source, long receivedAt) {
        if (!recorder.isFreshMqttDelivery()) return false;
        mysql.update("""
                INSERT INTO biz_room_sensor_comm_pending
                  (received_at,evidence_id,identity_id,equip_id,building_id,profile_code)
                VALUES (?,?,?,?,?,?)
                """, receivedAt, UUID.randomUUID().toString(), source.identityId(),
                source.equipId(), source.buildingId(), source.protocol());
        return true;
    }

    public DrainResult flush() {
        try {
            if (!Boolean.TRUE.equals(mysql.queryForObject("SELECT EXISTS(SELECT 1 FROM biz_room_sensor_comm_pending)", Boolean.class))) {
                return DrainResult.IDLE;
            }
            AppliedBatch completed = transaction.execute(status -> applyPending());
            if (completed.accepted()) {
                recorder.evidenceWriteSucceeded(SOURCE);
            }
            return completed.size() == BATCH_SIZE ? DrainResult.MORE_WORK : DrainResult.IDLE;
        } catch (RuntimeException failure) {
            recorder.evidenceWriteFailed(SOURCE);
            log.warn("房间传感器通讯证据暂未应用，保留待办重试: {}", failure.getClass().getSimpleName());
            return DrainResult.RETRY_LATER;
        }
    }

    private AppliedBatch applyPending() {
        // 等待前一批释放队首锁，避免并发投递让同一设备较新的证据先应用。
        var batch = mysql.query("""
                SELECT received_at,evidence_id,identity_id,equip_id,building_id,profile_code
                FROM biz_room_sensor_comm_pending ORDER BY received_at,evidence_id LIMIT ? FOR UPDATE
                """, (rs, row) -> {
            long at = rs.getLong("received_at");
            String profile = rs.getString("profile_code");
            return new Pending(rs.getString("evidence_id"),
                    new Signal(rs.getString("identity_id"), rs.getString("equip_id"), rs.getString("building_id"),
                            SOURCE, profile + ":" + at, at, at, false, "LIVE_TELEMETRY", null, profile));
        }, BATCH_SIZE);
        boolean accepted = false;
        var applied = new ArrayList<Object[]>(batch.size());
        for (Pending pending : batch) {
            // 状态更新和待办删除同事务；已失效或重复的证据也可出队，异常则整批保留。
            accepted |= communication.observe(pending.signal());
            applied.add(new Object[]{pending.signal().receivedAt(), pending.id()});
        }
        if (!applied.isEmpty()) {
            mysql.batchUpdate("DELETE FROM biz_room_sensor_comm_pending WHERE received_at=? AND evidence_id=?", applied);
        }
        return new AppliedBatch(batch.size(), accepted);
    }

    private record Pending(String id, Signal signal) { }
    private record AppliedBatch(int size, boolean accepted) { }
}
