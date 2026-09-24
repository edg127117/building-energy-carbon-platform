package com.platform.iot.daikin.retention;

import com.platform.iot.temporal.HvacRawEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 大金历史数据物理清理任务。MySQL 每批在独立事务中提交，TDengine 每批仅处理一个
 * 测点的一段时间窗；数据库租约和阶段游标允许多实例竞争及失败后继续。
 */
@Service
@ConditionalOnProperty(name = "daikin.retention.cleanup-enabled", havingValue = "true")
public class DaikinRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(DaikinRetentionJob.class);
    static final String JOB_NAME = "DAIKIN_RETENTION";
    static final String SOURCE_SYSTEM = "DAIKIN_V2";
    static final long TEMPERATURE_RETENTION_MILLIS = Duration.ofDays(90).toMillis();
    static final long BUSINESS_RETENTION_MILLIS = Duration.ofDays(365).toMillis();
    static final long DISCARDED_TOMBSTONE_MILLIS = Duration.ofDays(1).toMillis();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final HvacRawEventRepository rawEvents;
    private final DaikinRetentionProperties properties;
    private final Clock clock;

    @Autowired
    public DaikinRetentionJob(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
                              TransactionTemplate transaction,
                              HvacRawEventRepository rawEvents,
                              DaikinRetentionProperties properties) {
        this(jdbc, transaction, rawEvents, properties, Clock.systemUTC());
    }

    DaikinRetentionJob(JdbcTemplate jdbc, TransactionTemplate transaction,
                       HvacRawEventRepository rawEvents,
                       DaikinRetentionProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.rawEvents = rawEvents;
        this.properties = properties;
        this.clock = clock;
        validateProperties(properties);
    }

    @Scheduled(cron = "${daikin.retention.cleanup-cron:0 15 4 * * ?}", zone = "Asia/Shanghai")
    public void scheduledCleanup() {
        cleanup(clock.millis());
    }

    public void cleanup(long now) {
        String token = claim(now);
        if (token == null) return;
        try {
            for (int index = 0; index < properties.getMaximumBatchesPerRun(); index++) {
                StepResult result = runOneStep(token, now);
                if (result == StepResult.NOT_OWNED || result == StepResult.COMPLETE) return;
            }
            release(token);
        } catch (RuntimeException failure) {
            fail(token, failure);
            throw failure;
        }
    }

    private String claim(long now) {
        return transaction.execute(status -> {
            List<JobState> rows = jdbc.query("""
                    SELECT phase,status,lease_until_ms FROM biz_daikin_retention_job
                    WHERE job_name=? FOR UPDATE
                    """, (rs, row) -> new JobState(rs.getString(1), rs.getString(2), rs.getLong(3)), JOB_NAME);
            if (rows.isEmpty()) throw new IllegalStateException("DAIKIN_RETENTION_JOB_MISSING");
            JobState state = rows.getFirst();
            if ("RUNNING".equals(state.status()) && state.leaseUntil() > now) return null;
            String token = UUID.randomUUID().toString();
            jdbc.update("""
                    UPDATE biz_daikin_retention_job SET status='RUNNING',lease_token=?,lease_until_ms=?,
                      run_started_at_ms=?,last_error_code=NULL WHERE job_name=?
                    """, token, now + properties.getLeaseSeconds() * 1000L, now, JOB_NAME);
            return token;
        });
    }

    private StepResult runOneStep(String token, long now) {
        return transaction.execute(status -> {
            long leaseNow = clock.millis();
            List<JobProgress> progresses = jdbc.query("""
                    SELECT phase,td_point_cursor FROM biz_daikin_retention_job
                    WHERE job_name=? AND status='RUNNING' AND lease_token=? AND lease_until_ms>? FOR UPDATE
                    """, (rs, row) -> new JobProgress(Phase.valueOf(rs.getString(1)), rs.getString(2)),
                    JOB_NAME, token, leaseNow);
            if (progresses.isEmpty()) return StepResult.NOT_OWNED;
            JobProgress progress = progresses.getFirst();
            Phase phase = progress.phase();
            BatchOutcome outcome = switch (phase) {
                case STATE_EVENT -> deleteIds("biz_daikin_state_event", "event_id",
                        "observed_at_ms<?", now - BUSINESS_RETENTION_MILLIS, progress.tdCursor());
                case EXCEPTION -> deleteIds("biz_daikin_exception_instance", "exception_id",
                        "recovered_at_ms IS NOT NULL AND recovered_at_ms<?", now - BUSINESS_RETENTION_MILLIS,
                        progress.tdCursor());
                case INBOX_MARK -> new BatchOutcome(markExpiredInbox(now), progress.tdCursor());
                case INBOX_TOMBSTONE -> new BatchOutcome(deleteInboxTombstones(now), progress.tdCursor());
                case RUNTIME_EXPIRE -> new BatchOutcome(expireRuntimeJobs(now), progress.tdCursor());
                case RUNTIME_REVISION -> new BatchOutcome(deleteRuntimeIds("biz_daikin_runtime_revision", "revision_id",
                        runtimeHistoryCondition("biz_daikin_runtime_revision"), now - BUSINESS_RETENTION_MILLIS), progress.tdCursor());
                case RUNTIME_VALUE -> new BatchOutcome(deleteRuntimeIds("biz_daikin_runtime_value", "value_id",
                        runtimeHistoryCondition("biz_daikin_runtime_value"), now - BUSINESS_RETENTION_MILLIS), progress.tdCursor());
                case RUNTIME_JOB -> new BatchOutcome(deleteRuntimeIds("biz_daikin_runtime_job", "job_id",
                        "period_end_ms<? AND status IN ('FAILED','SUCCEEDED','UNSUPPORTED','EXPIRED')",
                        now - BUSINESS_RETENTION_MILLIS), progress.tdCursor());
                case OBSERVED_RUNTIME -> new BatchOutcome(deleteObservedRuntime(now), progress.tdCursor());
                case TEMPERATURE -> deleteTemperatureWindow(now, progress.tdCursor());
                case COMPLETE -> new BatchOutcome(0, null);
            };
            if (phase == Phase.COMPLETE) {
                jdbc.update("""
                        UPDATE biz_daikin_retention_job SET phase='STATE_EVENT',status='IDLE',lease_token=NULL,
                          lease_until_ms=0,last_completed_at_ms=? WHERE job_name=? AND lease_token=?
                        """, now, JOB_NAME, token);
                return StepResult.COMPLETE;
            }
            Phase next = outcome.affected() < properties.getBatchSize() ? phase.next() : phase;
            int recordedDeletes = phase == Phase.TEMPERATURE ? 0 : outcome.affected();
            jdbc.update("""
                    UPDATE biz_daikin_retention_job SET phase=?,td_point_cursor=?,lease_until_ms=?,deleted_rows=deleted_rows+?
                    WHERE job_name=? AND status='RUNNING' AND lease_token=?
                    """, next.name(), outcome.tdCursor(), leaseNow + properties.getLeaseSeconds() * 1000L,
                    recordedDeletes, JOB_NAME, token);
            return StepResult.CONTINUE;
        });
    }

    private BatchOutcome deleteIds(String table, String idColumn, String condition, long cutoff, String tdCursor) {
        List<Object> ids = jdbc.query("SELECT " + idColumn + " FROM " + table + " WHERE " + condition
                        + " ORDER BY " + idColumn + " LIMIT ?",
                (rs, row) -> rs.getObject(1), cutoff, properties.getBatchSize());
        int deleted = 0;
        for (Object id : ids) deleted += jdbc.update("DELETE FROM " + table + " WHERE " + idColumn + "=?", id);
        return new BatchOutcome(deleted, tdCursor);
    }

    private int deleteRuntimeIds(String table, String idColumn, String condition, long cutoff) {
        List<RuntimeRow> rows = jdbc.query("SELECT " + idColumn + ",source_id FROM " + table
                        + " WHERE " + condition + " ORDER BY source_id," + idColumn + " LIMIT ?",
                (rs, row) -> new RuntimeRow(rs.getString(1), rs.getString(2)),
                cutoff, properties.getBatchSize());
        // 运行统计写入遵循同一来源行锁；清理复用该锁序，避免删除与旧期间补取交错。
        for (String sourceId : new TreeSet<>(rows.stream().map(RuntimeRow::sourceId).toList())) {
            jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE",
                    String.class, sourceId);
        }
        int deleted = 0;
        for (RuntimeRow row : rows) {
            deleted += jdbc.update("DELETE FROM " + table + " WHERE " + idColumn + "=? AND " + condition,
                    row.id(), cutoff);
        }
        return deleted;
    }

    private int expireRuntimeJobs(long now) {
        long cutoff = now - BUSINESS_RETENTION_MILLIS;
        List<RuntimeRow> rows = jdbc.query("""
                SELECT job_id,source_id FROM biz_daikin_runtime_job
                WHERE period_end_ms<? AND (status IN ('QUEUED','RETRY_WAIT','FAILED')
                  OR (status='RUNNING' AND lease_until_ms<=?))
                ORDER BY source_id,job_id LIMIT ?
                """, (rs, row) -> new RuntimeRow(rs.getString(1), rs.getString(2)),
                cutoff, now, properties.getBatchSize());
        for (String sourceId : new TreeSet<>(rows.stream().map(RuntimeRow::sourceId).toList())) {
            jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE",
                    String.class, sourceId);
        }
        int expired = 0;
        for (RuntimeRow row : rows) {
            expired += jdbc.update("""
                    UPDATE biz_daikin_runtime_job SET status='EXPIRED',lease_token=NULL,lease_until_ms=0,
                      error_code='DAIKIN_RETENTION_EXPIRED'
                    WHERE job_id=? AND period_end_ms<? AND (status IN ('QUEUED','RETRY_WAIT','FAILED')
                      OR (status='RUNNING' AND lease_until_ms<=?))
                    """, row.id(), cutoff, now);
        }
        return expired;
    }

    private String runtimeHistoryCondition(String table) {
        return "period_end_ms<? AND NOT EXISTS (SELECT 1 FROM biz_daikin_runtime_job j"
                + " WHERE j.source_id=" + table + ".source_id"
                + " AND j.granularity=" + table + ".granularity"
                + " AND j.period_start_ms=" + table + ".period_start_ms"
                + " AND j.period_end_ms=" + table + ".period_end_ms"
                + " AND j.status IN ('RUNNING','RETRY_WAIT','QUEUED'))";
    }

    private int markExpiredInbox(long now) {
        List<String> ids = jdbc.queryForList("""
                SELECT observation_id FROM biz_daikin_monitor_inbox
                WHERE observed_at<? AND (status='PENDING' OR (status='WRITING' AND lease_until<=?))
                ORDER BY observation_id LIMIT ?
                """, String.class, now - TEMPERATURE_RETENTION_MILLIS, now, properties.getBatchSize());
        int marked = 0;
        for (String id : ids) {
            marked += jdbc.update("""
                    UPDATE biz_daikin_monitor_inbox SET status='DROPPED',observation_json=NULL,
                      lease_token=NULL,lease_until=0,error_code='DAIKIN_RETENTION_EXPIRED',retention_marked_at=?
                    WHERE observation_id=? AND (status='PENDING' OR (status='WRITING' AND lease_until<=?))
                    """, now, id, now);
        }
        return marked;
    }

    private int deleteInboxTombstones(long now) {
        List<InboxRow> rows = jdbc.query("""
                SELECT i.observation_id,i.source_id FROM biz_daikin_monitor_inbox i
                WHERE ((i.status='DONE' AND i.observed_at<?)
                    OR (i.status='DROPPED' AND i.retention_marked_at<?))
                  AND NOT EXISTS (SELECT 1 FROM biz_daikin_monitor_round r
                    WHERE r.source_id=i.source_id AND r.round_id=i.round_id
                      AND r.status IN ('RUNNING','RETRY_WAIT'))
                ORDER BY i.source_id,i.observation_id LIMIT ?
                """, (rs, row) -> new InboxRow(rs.getString(1), rs.getString(2)),
                now - TEMPERATURE_RETENTION_MILLIS, now - DISCARDED_TOMBSTONE_MILLIS,
                properties.getBatchSize());
        for (String sourceId : new TreeSet<>(rows.stream().map(InboxRow::sourceId).toList())) {
            jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE",
                    String.class, sourceId);
        }
        int deleted = 0;
        for (InboxRow row : rows) deleted += jdbc.update("""
                DELETE FROM biz_daikin_monitor_inbox WHERE observation_id=?
                  AND NOT EXISTS (SELECT 1 FROM biz_daikin_monitor_round r
                    WHERE r.source_id=biz_daikin_monitor_inbox.source_id
                      AND r.round_id=biz_daikin_monitor_inbox.round_id
                      AND r.status IN ('RUNNING','RETRY_WAIT'))
                """, row.id());
        return deleted;
    }

    private BatchOutcome deleteTemperatureWindow(long now, String cursor) {
        var result = rawEvents.deleteSourceBeforeInBoundedWindow(SOURCE_SYSTEM,
                now - TEMPERATURE_RETENTION_MILLIS,
                Duration.ofDays(properties.getTdengineWindowDays()).toMillis(), cursor);
        int affected = result.endOfScan() ? 0 : properties.getBatchSize();
        return new BatchOutcome(affected, result.nextPointCursor());
    }

    private int deleteObservedRuntime(long now) {
        List<ObservedRuntimeKey> keys = jdbc.query("""
                SELECT identity_id,building_id,mapping_version,day_start_ms
                FROM biz_daikin_observed_runtime_day WHERE day_start_ms<?
                ORDER BY day_start_ms LIMIT ?
                """, (rs, row) -> new ObservedRuntimeKey(rs.getString(1), rs.getString(2),
                rs.getInt(3), rs.getLong(4)), now - BUSINESS_RETENTION_MILLIS, properties.getBatchSize());
        int deleted = 0;
        for (ObservedRuntimeKey key : keys) deleted += jdbc.update("""
                DELETE FROM biz_daikin_observed_runtime_day
                WHERE identity_id=? AND building_id=? AND mapping_version=? AND day_start_ms=?
                """, key.identityId(), key.buildingId(), key.mappingVersion(), key.dayStart());
        return deleted;
    }

    private void fail(String token, RuntimeException failure) {
        String error = failure.getClass().getSimpleName();
        transaction.executeWithoutResult(status -> jdbc.update("""
                UPDATE biz_daikin_retention_job SET status='FAILED',lease_token=NULL,lease_until_ms=0,
                  last_error_code=? WHERE job_name=? AND lease_token=?
                """, error.length() > 64 ? error.substring(0, 64) : error, JOB_NAME, token));
        log.warn("大金保留期清理失败: error={}", error);
    }

    private void release(String token) {
        transaction.executeWithoutResult(status -> jdbc.update("""
                UPDATE biz_daikin_retention_job SET status='IDLE',lease_token=NULL,lease_until_ms=0
                WHERE job_name=? AND status='RUNNING' AND lease_token=?
                """, JOB_NAME, token));
    }

    private static void validateProperties(DaikinRetentionProperties properties) {
        if (properties.getBatchSize() < 1 || properties.getBatchSize() > 1000
                || properties.getMaximumBatchesPerRun() < 1 || properties.getMaximumBatchesPerRun() > 100
                || properties.getLeaseSeconds() < 1 || properties.getLeaseSeconds() > 3600
                || properties.getTdengineWindowDays() < 1 || properties.getTdengineWindowDays() > 30) {
            throw new IllegalArgumentException("大金保留期批次、租约或时间窗超出有界范围");
        }
    }

    private enum StepResult { CONTINUE, COMPLETE, NOT_OWNED }

    private enum Phase {
        STATE_EVENT, EXCEPTION, INBOX_MARK, INBOX_TOMBSTONE,
        RUNTIME_EXPIRE, RUNTIME_REVISION, RUNTIME_VALUE, RUNTIME_JOB, OBSERVED_RUNTIME, TEMPERATURE, COMPLETE;

        private Phase next() {
            return values()[ordinal() + 1];
        }
    }

    private record JobState(String phase, String status, long leaseUntil) { }
    private record JobProgress(Phase phase, String tdCursor) { }
    private record BatchOutcome(int affected, String tdCursor) { }
    private record RuntimeRow(String id, String sourceId) { }
    private record InboxRow(String id, String sourceId) { }
    private record ObservedRuntimeKey(String identityId, String buildingId, int mappingVersion, long dayStart) { }
}
