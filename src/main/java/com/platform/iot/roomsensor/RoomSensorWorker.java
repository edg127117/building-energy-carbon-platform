package com.platform.iot.roomsensor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** MySQL 持久待投递队列到 TDengine 的有界重试；进程重启不改变归属、接收时间或存储主键。 */
@Component
@ConditionalOnProperty(name = "room-sensor.enabled", havingValue = "true")
public class RoomSensorWorker {
    private static final Logger log = LoggerFactory.getLogger(RoomSensorWorker.class);
    static final long RETENTION_MILLIS = TimeUnit.DAYS.toMillis(7);
    private final JdbcTemplate mysql;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final RoomSensorHistoryStore history;
    private final MeterRegistry meters;

    public RoomSensorWorker(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql, ObjectMapper mapper,
            RoomSensorHistoryStore history, MeterRegistry meters) {
        this.mysql = mysql;
        this.mapper = mapper;
        this.history = history;
        this.meters = meters;
        tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(mysql.getDataSource())));
    }

    @Scheduled(fixedDelayString = "${room-sensor.flush-delay-ms:1000}", scheduler = "roomSensorTaskScheduler")
    public void flush() {
        try {
            for (int i = 0; i < 100; i++) {
                if (!flushOne(System.currentTimeMillis())) break;
            }
        } catch (RuntimeException failure) {
            meters.counter("iot.room_sensor.worker", "outcome", "queue_unavailable").increment();
            log.warn("房间传感器队列暂不可用，等待下轮重试: {}", failure.getClass().getSimpleName());
        }
    }

    boolean flushOne(long now) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var rows = mysql.queryForList("""
                    SELECT event_id,observation_json,received_at,attempts FROM biz_room_sensor_ingest_pending
                    WHERE next_attempt_at<=? ORDER BY next_attempt_at,received_at LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, now);
            if (rows.isEmpty()) return false;
            var row = rows.getFirst();
            String id = row.get("event_id").toString();
            if (((Number) row.get("received_at")).longValue() < now - RETENTION_MILLIS) {
                mysql.update("DELETE FROM biz_room_sensor_ingest_pending WHERE event_id=?", id);
                meters.counter("iot.room_sensor.worker", "outcome", "expired").increment();
                return true;
            }
            try {
                var observation = mapper.readValue(row.get("observation_json").toString(), RoomSensorObservation.class);
                if (!id.equals(observation.eventId())) throw new IllegalStateException("Room sensor queue identity mismatch");
                // MySQL 行锁覆盖写入与读回，防止多个工作进程同时修改同一 TDengine 主键。
                history.write(observation);
            } catch (RuntimeException | JsonProcessingException failure) {
                int attempts = ((Number) row.get("attempts")).intValue();
                long delay = Math.min(60_000L, 1000L << Math.min(attempts, 6));
                mysql.update("UPDATE biz_room_sensor_ingest_pending SET attempts=LEAST(attempts+1,1000000),next_attempt_at=? WHERE event_id=?", now + delay, id);
                meters.counter("iot.room_sensor.worker", "outcome", "retry").increment();
                // 不记录载荷或数据库原始错误；待投递行保留用于重启恢复及定位。
                log.warn("房间传感器历史写入未确认: eventId={}, failure={}", id, failure.getClass().getSimpleName());
                // TDengine 故障时结束本轮，避免对每条积压都等待网络超时并长期阻塞清理。
                return false;
            }
            mysql.update("DELETE FROM biz_room_sensor_ingest_pending WHERE event_id=?", id);
            meters.counter("iot.room_sensor.observations", "stage", "stored").increment();
            return true;
        }));
    }

    @Scheduled(fixedDelayString = "${room-sensor.cleanup-delay-ms:60000}", scheduler = "roomSensorTaskScheduler")
    public void cleanup() {
        long cutoff = System.currentTimeMillis() - RETENTION_MILLIS;
        try {
            int expired = mysql.update("DELETE FROM biz_room_sensor_ingest_pending WHERE received_at<? LIMIT 1000", cutoff);
            if (expired > 0) meters.counter("iot.room_sensor.worker", "outcome", "expired").increment(expired);
            history.deleteBefore(cutoff);
        } catch (RuntimeException failure) {
            meters.counter("iot.room_sensor.worker", "outcome", "cleanup_failed").increment();
            log.warn("房间传感器7天清理未完成，等待下轮重试: {}", failure.getClass().getSimpleName());
        }
    }
}
