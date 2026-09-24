package com.platform.iot.daikin.retention;

import com.platform.iot.temporal.HvacRawEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DaikinRetentionJobTest {
    private static final long NOW = Instant.parse("2026-09-16T00:00:00Z").toEpochMilli();
    private JdbcTemplate jdbc;
    private HvacRawEventRepository rawEvents;
    private DaikinRetentionJob job;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:daikin-retention-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE biz_daikin_source(source_id VARCHAR(200) PRIMARY KEY)");
        new ResourceDatabasePopulator(
                new ClassPathResource("daikin-monitoring-state-test.sql"),
                new ClassPathResource("daikin-monitoring-checkpoint-test.sql"),
                new ClassPathResource("daikin-runtime-test.sql"),
                new ClassPathResource("daikin-retention-test.sql")).execute(source);
        rawEvents = mock(HvacRawEventRepository.class);
        when(rawEvents.deleteSourceBeforeInBoundedWindow(anyString(), anyLong(), anyLong(), nullable(String.class)))
                .thenReturn(new HvacRawEventRepository.SourceDeletionScan(false, true, null));
        DaikinRetentionProperties properties = new DaikinRetentionProperties();
        properties.setBatchSize(2);
        properties.setMaximumBatchesPerRun(20);
        properties.setLeaseSeconds(60);
        properties.setTdengineWindowDays(7);
        job = new DaikinRetentionJob(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(source)), rawEvents, properties,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    void deletesOnlyExpiredBusinessRowsAndKeepsOpenOrActiveEvidence() {
        long oldBusiness = NOW - Duration.ofDays(366).toMillis();
        long recentBusiness = NOW - Duration.ofDays(364).toMillis();
        long oldTemperature = NOW - Duration.ofDays(91).toMillis();
        source("active");
        source("finished");
        event(1, oldBusiness);
        event(2, recentBusiness);
        exception(1, oldBusiness);
        exception(2, null);
        runtime("old", oldBusiness, "SUCCEEDED");
        runtime("recent", recentBusiness, "RUNNING");
        runtime("active-old", oldBusiness, "RUNNING");
        runtime("queued-old", oldBusiness, "QUEUED");
        runtime("retry-old", oldBusiness, "RETRY_WAIT");
        runtime("failed-old", oldBusiness, "FAILED");
        runtime("stale-running", oldBusiness, "RUNNING");
        jdbc.update("INSERT INTO biz_daikin_observed_runtime_day VALUES ('old','building',1,?,1000,1000)", oldBusiness);
        jdbc.update("INSERT INTO biz_daikin_observed_runtime_day VALUES ('recent','building',1,?,1000,1000)", recentBusiness);
        round("active", 10, "RUNNING");
        round("finished", 20, "SUCCEEDED");
        inbox("pending", "finished", 21, oldTemperature, "PENDING");
        inbox("active-done", "active", 10, oldTemperature, "DONE");
        inbox("finished-done", "finished", 20, oldTemperature, "DONE");

        job.cleanup(NOW);

        assertThat(count("biz_daikin_state_event", "event_id=1")).isZero();
        assertThat(count("biz_daikin_state_event", "event_id=2")).isOne();
        assertThat(count("biz_daikin_exception_instance", "exception_id=1")).isZero();
        assertThat(count("biz_daikin_exception_instance", "exception_id=2")).isOne();
        assertThat(count("biz_daikin_runtime_value", "value_id='old'")).isZero();
        assertThat(count("biz_daikin_runtime_revision", "revision_id='old-r'")).isZero();
        assertThat(count("biz_daikin_runtime_job", "job_id='old-j'")).isZero();
        assertThat(count("biz_daikin_runtime_job", "job_id='recent-j'")).isOne();
        assertThat(count("biz_daikin_runtime_value", "value_id='active-old'")).isOne();
        assertThat(count("biz_daikin_runtime_revision", "revision_id='active-old-r'")).isOne();
        assertThat(count("biz_daikin_runtime_job", "job_id='active-old-j'")).isOne();
        assertThat(count("biz_daikin_runtime_job", "job_id='queued-old-j'")).isZero();
        assertThat(count("biz_daikin_runtime_job", "job_id='retry-old-j'")).isZero();
        assertThat(count("biz_daikin_runtime_job", "job_id='failed-old-j'")).isZero();
        assertThat(count("biz_daikin_runtime_job", "job_id='stale-running-j'")).isZero();
        assertThat(count("biz_daikin_observed_runtime_day", "identity_id='old'")).isZero();
        assertThat(count("biz_daikin_observed_runtime_day", "identity_id='recent'")).isOne();
        assertThat(count("biz_daikin_monitor_inbox", "observation_id='active-done'")).isOne();
        assertThat(count("biz_daikin_monitor_inbox", "observation_id='finished-done'")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM biz_daikin_monitor_inbox WHERE observation_id='pending'",
                String.class)).isEqualTo("DROPPED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM biz_daikin_monitor_inbox WHERE observation_id='pending'",
                String.class)).isEqualTo("DAIKIN_RETENTION_EXPIRED");
        verify(rawEvents).deleteSourceBeforeInBoundedWindow("DAIKIN_V2",
                NOW - Duration.ofDays(90).toMillis(), Duration.ofDays(7).toMillis(), null);
        assertThat(jdbc.queryForObject("SELECT status FROM biz_daikin_retention_job", String.class)).isEqualTo("IDLE");
    }

    @Test
    void activeLeasePreventsConcurrentCleanup() {
        jdbc.update("UPDATE biz_daikin_retention_job SET status='RUNNING',lease_token='other',lease_until_ms=?", NOW + 1);

        job.cleanup(NOW);

        verifyNoInteractions(rawEvents);
        assertThat(jdbc.queryForObject("SELECT lease_token FROM biz_daikin_retention_job", String.class))
                .isEqualTo("other");
    }

    @Test
    void failureKeepsLastPhaseAndTdengineCursorForNextLeaseOwner() {
        jdbc.update("""
                UPDATE biz_daikin_retention_job SET phase='TEMPERATURE',td_point_cursor='raw_before_failure'
                WHERE job_name='DAIKIN_RETENTION'
                """);
        when(rawEvents.deleteSourceBeforeInBoundedWindow(anyString(), anyLong(), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("isolated TDengine failure"));

        assertThatThrownBy(() -> job.cleanup(NOW)).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT phase FROM biz_daikin_retention_job", String.class))
                .isEqualTo("TEMPERATURE");
        assertThat(jdbc.queryForObject("SELECT td_point_cursor FROM biz_daikin_retention_job", String.class))
                .isEqualTo("raw_before_failure");
        assertThat(jdbc.queryForObject("SELECT status FROM biz_daikin_retention_job", String.class))
                .isEqualTo("FAILED");
    }

    private void source(String id) {
        jdbc.update("INSERT INTO biz_daikin_source(source_id) VALUES (?)", id);
    }

    private void event(long id, long observedAt) {
        jdbc.update("""
                INSERT INTO biz_daikin_state_event(event_id,identity_id,field_name,round_id,source_id,equipment_id,
                  building_id,mapping_version,after_normalized_value,previous_observed_at_ms,observed_at_ms,after_gap)
                VALUES (?,'identity','onOff',?,'source','equipment','building',1,'on',?,?,0)
                """, id, id, observedAt - 1, observedAt);
    }

    private void exception(long id, Long recoveredAt) {
        jdbc.update("""
                INSERT INTO biz_daikin_exception_instance(exception_id,exception_type,scope_type,source_id,
                  first_detected_at_ms,last_detected_at_ms,recovered_at_ms)
                VALUES (?,'FAULT','SOURCE','source',1,2,?)
                """, id, recoveredAt);
    }

    private void runtime(String id, long periodEnd, String status) {
        long periodStart = periodEnd - Duration.ofDays(1).toMillis();
        String sourceId = id.equals("active-old") ? "active" : "finished";
        jdbc.update("""
                INSERT INTO biz_daikin_runtime_value
                  (value_id,identity_id,source_id,equipment_id,building_id,mapping_version,granularity,
                   period_start_ms,period_end_ms,statistics_zone,unit,last_attempt_at_ms,last_attempt_status)
                VALUES (?,'identity',?,'equipment','building',1,'DAY',?,?,'Asia/Shanghai','h',?,'SUCCESS')
                """, id, sourceId, periodStart, periodEnd, periodEnd);
        jdbc.update("""
                INSERT INTO biz_daikin_runtime_revision
                  (revision_id,value_id,revision_no,identity_id,source_id,equipment_id,building_id,mapping_version,
                   granularity,period_start_ms,period_end_ms,statistics_zone,unit,metrics_json,period_complete,
                   ownership_verified,observed_at_ms)
                VALUES (?,?,1,'identity',?,'equipment','building',1,'DAY',?,?,'Asia/Shanghai','h','{}',1,1,?)
                """, id + "-r", id, sourceId, periodStart, periodEnd, periodEnd);
        jdbc.update("""
                INSERT INTO biz_daikin_runtime_job
                  (job_id,source_id,device_kind,granularity,period_start_ms,period_end_ms,statistics_zone,unit,
                   semantics_version,status,next_attempt_at_ms,planned_at_ms)
                VALUES (?,?,'INDOOR','DAY',?,?,'Asia/Shanghai','h','v1',?,0,?)
                """, id + "-j", sourceId, periodStart, periodEnd, status, periodStart);
        if (id.equals("active-old")) {
            jdbc.update("UPDATE biz_daikin_runtime_job SET lease_until_ms=? WHERE job_id=?",
                    NOW + Duration.ofDays(1).toMillis(), id + "-j");
        }
    }

    private void round(String source, long round, String status) {
        jdbc.update("""
                INSERT INTO biz_daikin_monitor_round(source_id,round_id,status,attempts,lease_until,next_attempt_at)
                VALUES (?,?,?,0,0,0)
                """, source, round, status);
    }

    private void inbox(String id, String source, long round, long observedAt, String status) {
        jdbc.update("""
                INSERT INTO biz_daikin_monitor_inbox(observation_id,source_id,pending_id,round_id,observed_at,
                  target_json,observation_json,status,attempts,next_attempt_at,lease_until)
                VALUES (?,?,?, ?,?,'{}','{}',?,0,0,0)
                """, id, source, "pending-" + id, round, observedAt, status);
    }

    private int count(String table, String condition) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + condition, Integer.class);
    }
}
