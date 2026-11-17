package com.platform.iot.roomsensor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 本地事务/恢复测试只用 H2 与 TDengine 替身，真实引擎联调另行执行。 */
class RoomSensorIngestionTest {
    private JdbcTemplate mysql;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private RoomSensorIngestor ingestor;
    private RoomSensorHistoryStore history;
    private RoomSensorWorker worker;
    private static final String TOPIC = "/MSR01/ac233fc2b716/radar";
    private static final byte[] RADAR = """
            {"mac":"AC233FC2B716","timestamp":"2026-11-16T17:00:00Z","sn":"65535",
             "radar":{"type":"coord","num":"2","coord":{"no.1":"discarded"}}}
            """.getBytes(StandardCharsets.UTF_8);

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:room-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "sa", "");
        mysql = new JdbcTemplate(ds);
        mysql.execute("CREATE TABLE biz_space(space_id VARCHAR(32) PRIMARY KEY,building_id VARCHAR(32),del_flag INT)");
        mysql.execute("CREATE TABLE biz_equipment(equip_id VARCHAR(32) PRIMARY KEY,building_id VARCHAR(32),space_id VARCHAR(32),del_flag INT)");
        mysql.execute("CREATE TABLE biz_device_identity(identity_id VARCHAR(32) PRIMARY KEY,identity_type VARCHAR(20),identity_value VARCHAR(50),equip_id VARCHAR(32),building_id VARCHAR(32),expected_profile_code VARCHAR(50),status INT)");
        // H2 对参数化 JSON 字符串的存取语义不同；真实 MySQL JSON 类型由独立引擎验证覆盖。
        String ddl = new ClassPathResource("db/migration/mysql/V121__mysql_room_sensor_ingestion.sql")
                .getContentAsString(StandardCharsets.UTF_8).replace("observation_json JSON", "observation_json LONGTEXT")
                .replace(" COLLATE=utf8mb4_unicode_ci", "").split("-- 身份表历史安装")[0];
        new ResourceDatabasePopulator(new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8))).execute(ds);
        mysql.execute("ALTER TABLE biz_room_sensor_source ADD CONSTRAINT fk_room_sensor_identity FOREIGN KEY(identity_id) REFERENCES biz_device_identity(identity_id)");
        mysql.update("INSERT INTO biz_space VALUES ('room','building',0)");
        mysql.update("INSERT INTO biz_equipment VALUES ('radar','building','room',0)");
        mysql.update("INSERT INTO biz_device_identity VALUES ('identity','MAC','AC233FC2B716','radar','building','MSR01_RADAR',1)");
        mysql.update("INSERT INTO biz_room_sensor_source(source_id,identity_id,gateway_mac,topic,protocol,enabled) VALUES ('radarsource','identity','AC233FC2B716',?,'MSR01_RADAR',1)", TOPIC);
        ingestor = new RoomSensorIngestor(mysql, new RoomSensorParser(mapper), mapper, meters);
        history = mock(RoomSensorHistoryStore.class);
        worker = new RoomSensorWorker(mysql, mapper, history, meters);
    }

    @Test void everyValidObservationCommitsOnlyProjectionAndAllocatesDistinctKeysAcrossRestart() throws Exception {
        ingestor.accept(TOPIC, RADAR, 1000, false);
        new RoomSensorIngestor(mysql, new RoomSensorParser(mapper), mapper, meters).accept(TOPIC, RADAR, 1000, false);
        var rows = mysql.queryForList("SELECT * FROM biz_room_sensor_ingest_pending ORDER BY storage_ts");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("storage_ts")).isEqualTo(1000L);
        assertThat(rows.get(1).get("storage_ts")).isEqualTo(1001L);
        for (var row : rows) {
            var saved = mapper.readValue(row.get("observation_json").toString(), RoomSensorObservation.class);
            assertThat(saved.receivedAt()).isEqualTo(1000);
            assertThat(saved.deviceTime()).isEqualTo("2026-11-16T17:00:00Z");
            assertThat(saved.spaceId()).isEqualTo("room");
            assertThat(saved.targetCount()).isEqualTo(2);
            assertThat(row.get("observation_json").toString()).doesNotContain("coord", "discarded");
        }
    }

    @Test void rejectsDisabledCrossBuildingDeletedOrUnregisteredSourcesAndRetainedMessages() {
        ingestor.accept(TOPIC, RADAR, 1000, true);
        ingestor.accept("unregistered", RADAR, 1000, false);
        mysql.update("UPDATE biz_device_identity SET expected_profile_code='S4_DOOR'");
        ingestor.accept(TOPIC, RADAR, 1000, false);
        mysql.update("UPDATE biz_device_identity SET expected_profile_code='MSR01_RADAR'");
        mysql.update("UPDATE biz_device_identity SET status=0");
        ingestor.accept(TOPIC, RADAR, 1000, false);
        mysql.update("UPDATE biz_device_identity SET status=1,building_id='other'");
        ingestor.accept(TOPIC, RADAR, 1000, false);
        mysql.update("UPDATE biz_device_identity SET building_id='building'");
        mysql.update("UPDATE biz_space SET del_flag=1");
        ingestor.accept(TOPIC, RADAR, 1000, false);
        assertThat(pendingCount()).isZero();
    }

    @Test void failedProjectionInsertRollsBackSourceClockAndCanRetry() {
        mysql.execute("ALTER TABLE biz_room_sensor_ingest_pending ADD CONSTRAINT injected_failure CHECK(received_at<>1000)");
        assertThatThrownBy(() -> ingestor.accept(TOPIC, RADAR, 1000, false)).isInstanceOf(RuntimeException.class);
        assertThat(pendingCount()).isZero();
        assertThat(mysql.queryForObject("SELECT last_storage_ts FROM biz_room_sensor_source", Long.class)).isZero();
        ingestor.accept(TOPIC, RADAR, 1001, false);
        assertThat(pendingCount()).isEqualTo(1);
    }

    @Test void topicCaseMustMatchEvenWhenDatabaseCollationIgnoresCase() {
        mysql.execute("ALTER TABLE biz_room_sensor_source ALTER COLUMN topic VARCHAR_IGNORECASE(200)");
        mysql.update("UPDATE biz_room_sensor_source SET topic=?", TOPIC.toUpperCase(java.util.Locale.ROOT));
        ingestor.accept(TOPIC, RADAR, 1000, false);
        assertThat(pendingCount()).isZero();
    }

    @Test void retrySurvivesWorkerRestartAndReplaysFrozenOwnershipAndKey() {
        ingestor.accept(TOPIC, RADAR, 1000, false);
        doThrow(new IllegalStateException("TD unavailable")).doNothing().when(history).write(any());
        assertThat(worker.flushOne(1000)).isFalse();
        assertThat(pendingCount()).isEqualTo(1);
        assertThat(worker.flushOne(1999)).isFalse();
        mysql.update("UPDATE biz_equipment SET space_id='new-room'");
        worker = new RoomSensorWorker(mysql, mapper, history, meters);
        assertThat(worker.flushOne(2000)).isTrue();
        var records = org.mockito.ArgumentCaptor.forClass(RoomSensorObservation.class);
        verify(history, times(2)).write(records.capture());
        assertThat(records.getAllValues().get(0)).isEqualTo(records.getAllValues().get(1));
        assertThat(records.getValue().spaceId()).isEqualTo("room");
        assertThat(records.getValue().storageTs()).isEqualTo(1000);
        assertThat(pendingCount()).isZero();
    }

    @Test void expiredPendingNeverRecreatesExpiredHistoryAndBoundaryIsKept() {
        ingestor.accept(TOPIC, RADAR, 1000, false);
        assertThat(worker.flushOne(1000 + RoomSensorWorker.RETENTION_MILLIS + 1)).isTrue();
        verifyNoInteractions(history);
        assertThat(pendingCount()).isZero();
        ingestor.accept(TOPIC, RADAR, 2000, false);
        worker.flushOne(2000 + RoomSensorWorker.RETENTION_MILLIS);
        verify(history).write(any());
    }

    @Test void concurrentCallbacksCannotReuseSameMillisecondKey() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var jobs = java.util.stream.IntStream.range(0, 12).mapToObj(i -> pool.submit(
                    () -> ingestor.accept(TOPIC, RADAR, 1000, false))).toList();
            for (var job : jobs) job.get(10, TimeUnit.SECONDS);
        }
        assertThat(pendingCount()).isEqualTo(12);
        assertThat(mysql.queryForObject("SELECT COUNT(DISTINCT storage_ts) FROM biz_room_sensor_ingest_pending", Integer.class)).isEqualTo(12);
    }

    @Test void cleanupRemovesOnlyExpiredPendingAndCallsScopedHistoryCleanup() {
        long now = System.currentTimeMillis();
        ingestor.accept(TOPIC, RADAR, now - RoomSensorWorker.RETENTION_MILLIS - 1000, false);
        ingestor.accept(TOPIC, RADAR, now, false);
        worker.cleanup();
        assertThat(pendingCount()).isEqualTo(1);
        verify(history).deleteBefore(longThat(c -> c >= now - RoomSensorWorker.RETENTION_MILLIS));
    }

    private int pendingCount() { return mysql.queryForObject("SELECT COUNT(*) FROM biz_room_sensor_ingest_pending", Integer.class); }
}
