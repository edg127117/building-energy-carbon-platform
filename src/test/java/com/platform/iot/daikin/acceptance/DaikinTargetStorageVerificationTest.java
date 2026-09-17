package com.platform.iot.daikin.acceptance;

import com.platform.PlatformApplication;
import com.platform.config.TestRedisConfiguration;
import com.platform.iot.daikin.monitoring.DaikinTemperatureIngestion;
import com.platform.iot.daikin.retention.DaikinRetentionJob;
import com.platform.iot.daikin.runtime.DaikinRuntimeScheduler;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 显式 opt-in 的专用目标库验证。普通 test 不会创建上下文，也不会连接外部数据库。
 */
@EnabledIfEnvironmentVariable(named = "DAIKIN_TARGET_VERIFY", matches = "true")
@SpringBootTest(classes = {PlatformApplication.class, TestRedisConfiguration.class,
        DaikinTargetHarnessConfiguration.class}, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles({"test", "daikin-target"})
class DaikinTargetStorageVerificationTest {
    @DynamicPropertySource
    static void targetProperties(DynamicPropertyRegistry registry) throws Exception {
        var target = DaikinTargetHarnessApplication.TargetEnvironment.read(
                DaikinTargetHarnessApplication.DEFAULT_ENV);
        target.properties().forEach((name, value) -> registry.add(name, () -> value));
    }

    @Autowired DaikinTargetFixture fixture;
    @Autowired DaikinTargetVendor vendor;
    @Autowired DaikinRetentionJob retention;
    @Autowired DaikinRuntimeScheduler runtime;
    @Autowired HvacRawEventRepository rawEvents;
    @Autowired DataPointConfigProvider points;
    @Autowired @Qualifier("mysqlJdbcTemplate") JdbcTemplate mysql;
    @Autowired @Qualifier("taosJdbcTemplate") JdbcTemplate taos;
    @org.springframework.beans.factory.annotation.Value("${tdengine.database}") String tdDatabase;

    @BeforeEach
    void prepareBoundTarget() {
        assertThat(mysql.queryForObject("SELECT @@session.time_zone", String.class))
                .isIn("+08:00", "Asia/Shanghai");
        fixture.initialize();
        fixture.syncDirectory();
        fixture.approveAndActivate();
        fixture.resetObservations();
    }

    @Test
    void realStoresEnforceRetentionAndKeepUnrecoveredException() {
        fixture.collect();
        Map<String, Object> state = fixture.state();
        String pointId = state.get("pointId").toString();
        String equipmentId = state.get("equipmentId").toString();
        String identityId = state.get("identityId").toString();
        String pendingId = state.get("pendingId").toString();
        var point = points.findByPointId(pointId).orElseThrow();
        String sourcePoint = mysql.queryForObject("""
                SELECT source_point_code FROM biz_point_alias
                WHERE point_id=? AND source_system='DAIKIN_V2'
                """, String.class, pointId);
        long now = System.currentTimeMillis();
        long oldTemperature = now - Duration.ofDays(91).toMillis();
        rawEvents.insertImmutable(new RawTelemetryEvent(point.pointId(), point.pointCode(),
                DaikinTemperatureIngestion.SOURCE_SYSTEM, sourcePoint, point.equipCode(),
                point.buildingId(), point.systemGroupId(), point.equipId(), point.equipCode(),
                point.familyCode(), point.componentCode(), point.suffixCode(), 19.5,
                oldTemperature, oldTemperature, 0, point.isForCalc(), false));
        Long recentTemperatureBefore = taos.queryForObject("SELECT COUNT(*) FROM " + tdDatabase
                + ".st_raw_event WHERE point_id=? AND source_system='DAIKIN_V2' AND ts>=?",
                Long.class, pointId, now - Duration.ofDays(90).toMillis());
        assertThat(recentTemperatureBefore).isPositive();

        long oldBusiness = now - Duration.ofDays(366).toMillis();
        long recentBusiness = now - Duration.ofDays(2).toMillis();
        insertRuntime("a", identityId, equipmentId,
                oldBusiness - Duration.ofDays(1).toMillis(), oldBusiness, oldBusiness);
        insertRuntime("b", identityId, equipmentId,
                recentBusiness - Duration.ofDays(1).toMillis(), recentBusiness, recentBusiness);
        mysql.update("""
                INSERT INTO biz_daikin_state_event
                  (identity_id,field_name,round_id,source_id,pending_id,equipment_id,building_id,space_id,
                   system_group_id,mapping_version,before_raw_json,before_normalized_value,after_raw_json,
                   after_normalized_value,previous_observed_at_ms,observed_at_ms,after_gap)
                VALUES (?,'onOff',?,?,?,?,?,?,?,1,'\"off\"','off','\"on\"','on',?,?,0)
                """, identityId, oldBusiness, DaikinTargetFixture.SOURCE_ID, pendingId, equipmentId,
                DaikinTargetFixture.BUILDING_ID, DaikinTargetFixture.SPACE_ID,
                DaikinTargetFixture.GROUP_ID, oldBusiness - 60_000, oldBusiness);
        mysql.update("""
                INSERT INTO biz_daikin_exception_instance
                  (active_key,exception_type,scope_type,source_id,identity_id,pending_id,equipment_id,building_id,
                   first_detected_at_ms,last_detected_at_ms,recovered_at_ms,last_round_id)
                VALUES (NULL,'VENDOR_EQUIPMENT','DEVICE',?,?,?,?,?,?,?,?,?)
                """, DaikinTargetFixture.SOURCE_ID, identityId, pendingId, equipmentId,
                DaikinTargetFixture.BUILDING_ID, oldBusiness, oldBusiness, oldBusiness, oldBusiness);
        mysql.update("""
                INSERT INTO biz_daikin_exception_instance
                  (active_key,exception_type,scope_type,source_id,identity_id,pending_id,equipment_id,building_id,
                   first_detected_at_ms,last_detected_at_ms,recovered_at_ms,last_round_id)
                VALUES ('DAIKIN_TARGET_RETENTION_ACTIVE','VENDOR_EQUIPMENT','DEVICE',?,?,?,?,?,?,?,NULL,?)
                """, DaikinTargetFixture.SOURCE_ID, identityId, pendingId, equipmentId,
                DaikinTargetFixture.BUILDING_ID, oldBusiness, oldBusiness, oldBusiness);
        mysql.update("""
                UPDATE biz_daikin_retention_job SET phase='STATE_EVENT',status='IDLE',lease_token=NULL,
                  lease_until_ms=0,td_point_cursor=NULL WHERE job_name='DAIKIN_RETENTION'
                """);

        retention.cleanup(now);

        Long oldTemperatureRows = taos.queryForObject("SELECT COUNT(*) FROM " + tdDatabase
                + ".st_raw_event WHERE point_id=? AND source_system='DAIKIN_V2' AND ts<?",
                Long.class, pointId, oldTemperature + 1);
        assertThat(oldTemperatureRows).isZero();
        assertThat(taos.queryForObject("SELECT COUNT(*) FROM " + tdDatabase
                + ".st_raw_event WHERE point_id=? AND source_system='DAIKIN_V2' AND ts>=?",
                Long.class, pointId, now - Duration.ofDays(90).toMillis())).isPositive();
        assertThat(mysql.queryForObject(
                "SELECT COUNT(*) FROM biz_daikin_state_event WHERE source_id=? AND observed_at_ms<?",
                Long.class, DaikinTargetFixture.SOURCE_ID, now - Duration.ofDays(365).toMillis())).isZero();
        assertThat(mysql.queryForObject("""
                SELECT COUNT(*) FROM biz_daikin_exception_instance
                WHERE source_id=? AND recovered_at_ms IS NOT NULL AND recovered_at_ms<?
                """, Long.class, DaikinTargetFixture.SOURCE_ID,
                now - Duration.ofDays(365).toMillis())).isZero();
        assertThat(mysql.queryForObject("""
                SELECT COUNT(*) FROM biz_daikin_exception_instance
                WHERE active_key='DAIKIN_TARGET_RETENTION_ACTIVE' AND recovered_at_ms IS NULL
                """, Long.class)).isEqualTo(1L);
        assertRuntimeRetention("a", 0L);
        assertRuntimeRetention("b", 1L);
    }

    @Test
    void runtimeRetryIsPersistedBeforeRecoverySucceeds() {
        vendor.runtimeFailure(true);
        try {
            await(() -> {
                runtime.schedule();
                return mysql.queryForObject("""
                        SELECT COUNT(*) FROM biz_daikin_runtime_job
                        WHERE source_id=? AND status='RETRY_WAIT' AND attempts>=1
                        """, Long.class, DaikinTargetFixture.SOURCE_ID) > 0;
            });
            RuntimeRetryJob retry = mysql.queryForObject("""
                    SELECT job_id,period_start_ms,granularity FROM biz_daikin_runtime_job
                    WHERE source_id=? AND status='RETRY_WAIT' AND attempts>=1
                    ORDER BY planned_at_ms DESC,job_id LIMIT 1
                    """, (rs, row) -> new RuntimeRetryJob(rs.getString("job_id"),
                    rs.getLong("period_start_ms"), rs.getString("granularity")),
                    DaikinTargetFixture.SOURCE_ID);
            assertThat(retry).isNotNull();

            vendor.runtimeFailure(false);
            mysql.update("UPDATE biz_daikin_runtime_job SET next_attempt_at_ms=0 WHERE job_id=?", retry.jobId());
            await(() -> {
                runtime.schedule();
                return mysql.queryForObject("""
                        SELECT COUNT(*) FROM biz_daikin_runtime_job
                        WHERE job_id=? AND status='SUCCEEDED' AND attempts>=2
                        """, Long.class, retry.jobId()) == 1L;
            });
            assertThat(mysql.queryForObject("""
                    SELECT COUNT(*) FROM biz_daikin_runtime_value
                    WHERE source_id=? AND period_start_ms=? AND granularity=? AND last_attempt_status='PRESENT'
                    """, Long.class, DaikinTargetFixture.SOURCE_ID,
                    retry.periodStart(), retry.granularity())).isPositive();
        } finally {
            vendor.runtimeFailure(false);
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        throw new AssertionError("target storage condition timed out");
    }

    private void insertRuntime(String marker, String identityId, String equipmentId,
            long periodStart, long periodEnd, long observedAt) {
        String jobId = marker.repeat(64);
        String valueId = marker.toUpperCase(java.util.Locale.ROOT).repeat(64);
        String revisionId = (marker.equals("a") ? "c" : "d").repeat(64);
        mysql.update("""
                INSERT INTO biz_daikin_runtime_job
                  (job_id,source_id,device_kind,granularity,period_start_ms,period_end_ms,statistics_zone,
                   unit,semantics_version,status,attempts,next_attempt_at_ms,planned_at_ms,lease_until_ms)
                VALUES (?,?,'INDOOR','DAY',?,?,'Asia/Shanghai','minute','target-retention-v1',
                  'SUCCEEDED',1,0,?,0)
                """, jobId, DaikinTargetFixture.SOURCE_ID, periodStart, periodEnd, observedAt);
        mysql.update("""
                INSERT INTO biz_daikin_runtime_value
                  (value_id,identity_id,source_id,equipment_id,building_id,space_id,system_group_id,mapping_version,
                   granularity,period_start_ms,period_end_ms,statistics_zone,unit,metrics_json,revision_no,
                   last_success_at_ms,last_attempt_at_ms,last_attempt_status,period_complete,ownership_verified)
                VALUES (?,?,?,?,?,?,?,1,'DAY',?,?,'Asia/Shanghai','minute','{\"totalRuntime\":120}',1,
                  ?,?,'PRESENT',1,1)
                """, valueId, identityId, DaikinTargetFixture.SOURCE_ID, equipmentId,
                DaikinTargetFixture.BUILDING_ID, DaikinTargetFixture.SPACE_ID, DaikinTargetFixture.GROUP_ID,
                periodStart, periodEnd, observedAt, observedAt);
        mysql.update("""
                INSERT INTO biz_daikin_runtime_revision
                  (revision_id,value_id,revision_no,identity_id,source_id,equipment_id,building_id,space_id,
                   system_group_id,mapping_version,granularity,period_start_ms,period_end_ms,statistics_zone,
                   unit,metrics_json,period_complete,ownership_verified,observed_at_ms)
                VALUES (?,?,1,?,?,?,?,?,?,1,'DAY',?,?,'Asia/Shanghai','minute',
                  '{\"totalRuntime\":120}',1,1,?)
                """, revisionId, valueId, identityId, DaikinTargetFixture.SOURCE_ID, equipmentId,
                DaikinTargetFixture.BUILDING_ID, DaikinTargetFixture.SPACE_ID, DaikinTargetFixture.GROUP_ID,
                periodStart, periodEnd, observedAt);
    }

    private void assertRuntimeRetention(String marker, long expected) {
        String jobId = marker.repeat(64);
        String valueId = marker.toUpperCase(java.util.Locale.ROOT).repeat(64);
        String revisionId = (marker.equals("a") ? "c" : "d").repeat(64);
        assertThat(mysql.queryForObject("SELECT COUNT(*) FROM biz_daikin_runtime_job WHERE job_id=?",
                Long.class, jobId)).isEqualTo(expected);
        assertThat(mysql.queryForObject("SELECT COUNT(*) FROM biz_daikin_runtime_value WHERE value_id=?",
                Long.class, valueId)).isEqualTo(expected);
        assertThat(mysql.queryForObject("SELECT COUNT(*) FROM biz_daikin_runtime_revision WHERE revision_id=?",
                Long.class, revisionId)).isEqualTo(expected);
    }

    private record RuntimeRetryJob(String jobId, long periodStart, String granularity) { }
}
