package com.platform.iot.daikin.sync;

import com.platform.framework.exception.BusinessException;
import com.platform.iot.daikin.catalog.DaikinCatalogClient;
import com.platform.iot.daikin.client.DaikinClientException;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.model.DaikinDeviceObservation;
import com.platform.iot.daikin.onboarding.DaikinDirectoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PreDestroy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 持久化并执行单来源大金目录同步。外部 HTTP 始终在数据库事务之外；提交阶段重新锁定来源并
 * 校验租约与 fencing 号，只有当前 worker 能把两类完整目录作为同一事务交给待接入区。
 */
@Service
public class DaikinDirectorySyncService {
    private static final ZoneId MYSQL_ZONE = ZoneId.of("Asia/Shanghai");
    static final String QUEUED = "QUEUED";
    static final String RUNNING = "RUNNING";
    static final String RETRY_WAIT = "RETRY_WAIT";
    static final String SUCCEEDED = "SUCCEEDED";
    static final String FAILED = "FAILED";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final DaikinDirectoryService directory;
    private final DaikinDirectorySyncProperties properties;
    private final Optional<DaikinCatalogClientProvider> provider;
    private final Clock clock;
    private final ExecutorService worker;
    private final AtomicBoolean scheduledWork = new AtomicBoolean();
    private volatile String scheduledSourceCursor = "";

    @Autowired
    public DaikinDirectorySyncService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
            TransactionTemplate transaction, DaikinDirectoryService directory,
            DaikinDirectorySyncProperties properties,
            Optional<DaikinCatalogClientProvider> provider) {
        this(jdbc, transaction, directory, properties, provider, Clock.systemUTC());
    }

    DaikinDirectorySyncService(JdbcTemplate jdbc, TransactionTemplate transaction,
            DaikinDirectoryService directory, DaikinDirectorySyncProperties properties,
            Optional<DaikinCatalogClientProvider> provider, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
        this.directory = Objects.requireNonNull(directory);
        this.properties = Objects.requireNonNull(properties);
        this.provider = Objects.requireNonNull(provider);
        this.clock = Objects.requireNonNull(clock);
        this.worker = Executors.newSingleThreadExecutor(task ->
                Thread.ofPlatform().daemon().name("daikin-directory-sync").unstarted(task));
    }

    public JobView request(String sourceId, Long requestedBy) {
        String validSource = requireId(sourceId, 200, "来源身份无效");
        if (requestedBy == null || requestedBy <= 0) throw invalid();
        requireAvailable(validSource);
        return transaction.execute(status -> enqueueLocked(validSource, requestedBy, false));
    }

    public JobView get(String sourceId, String jobId) {
        String validSource = requireId(sourceId, 200, "来源身份无效");
        String validJob = requireId(jobId, 32, "任务身份无效");
        List<JobView> jobs = jdbc.query("""
                SELECT job_id,source_id,status,attempts,error_code
                FROM biz_daikin_directory_sync_job WHERE source_id=? AND job_id=?
                """, (rs, row) -> new JobView(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getInt(4), rs.getString(5)), validSource, validJob);
        if (jobs.size() != 1) {
            throw new BusinessException(404, "DAIKIN_SYNC_JOB_NOT_FOUND", "同步任务不存在");
        }
        return jobs.get(0);
    }

    /** 未启用或没有可信 provider 时安静返回，避免测试和默认部署产生外部访问。 */
    @Scheduled(fixedDelayString = "${daikin.directory-sync.schedule-delay-ms:10000}")
    public void scheduledSync() {
        if (!properties.isEnabled() || provider.isEmpty() || !scheduledWork.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try {
                    enqueueScheduledSources();
                    runDueJobs();
                } finally {
                    scheduledWork.set(false);
                }
            });
        } catch (RejectedExecutionException ignored) {
            scheduledWork.set(false);
        }
    }

    @PreDestroy
    void stopWorker() {
        worker.shutdownNow();
    }

    void runDueJobs() {
        if (!properties.isEnabled() || provider.isEmpty()) return;
        List<String> candidates = jdbc.queryForList("""
                SELECT job_id FROM biz_daikin_directory_sync_job
                WHERE (status IN ('QUEUED','RETRY_WAIT') AND next_attempt_at<=?)
                   OR (status='RUNNING' AND lease_until<=?)
                ORDER BY next_attempt_at,create_time LIMIT ?
                """, String.class, timestamp(now()), timestamp(now()), properties.getBatchSize());
        for (String jobId : candidates) runOne(jobId);
    }

    void runOne(String jobId) {
        Lease lease = transaction.execute(status -> claim(jobId));
        if (lease == null) return;
        try {
            DaikinCatalogClient client = provider.flatMap(value -> safeClient(value, lease.sourceId()))
                    .orElseThrow(ProviderUnavailableException::new);
            // 两类分页都完成并通过各自的完整性校验后，才进入同一个数据库提交事务。
            Runnable heartbeat = () -> renewLease(lease);
            List<DaikinDeviceObservation> indoor = client.read(
                    lease.sourceId(), DaikinDeviceKey.Kind.INDOOR, heartbeat);
            List<DaikinDeviceObservation> outdoor = client.read(
                    lease.sourceId(), DaikinDeviceKey.Kind.OUTDOOR, heartbeat);
            commit(lease, indoor, outdoor);
        } catch (RuntimeException exception) {
            failOrRetry(lease, safeErrorCode(exception));
        }
    }

    void enqueueScheduledSources() {
        Instant dueBefore = now().minusSeconds(properties.getSyncIntervalSeconds());
        List<String> sources = scheduledSourcesAfter(dueBefore, scheduledSourceCursor);
        if (sources.isEmpty() && !scheduledSourceCursor.isEmpty()) {
            scheduledSourceCursor = "";
            sources = scheduledSourcesAfter(dueBefore, scheduledSourceCursor);
        }
        if (!sources.isEmpty()) scheduledSourceCursor = sources.get(sources.size() - 1);
        for (String sourceId : sources) {
            if (provider.flatMap(value -> safeClient(value, sourceId)).isEmpty()) continue;
            transaction.executeWithoutResult(status -> enqueueLocked(sourceId, null, true, dueBefore));
        }
    }

    private List<String> scheduledSourcesAfter(Instant dueBefore, String cursor) {
        return jdbc.queryForList("""
                SELECT s.source_id FROM biz_daikin_source s
                WHERE s.source_id>? AND NOT EXISTS (
                    SELECT 1 FROM biz_daikin_directory_sync_job a
                    WHERE a.source_id=s.source_id AND a.status IN ('QUEUED','RUNNING','RETRY_WAIT'))
                  AND NOT EXISTS (
                    SELECT 1 FROM biz_daikin_directory_sync_job t
                    WHERE t.source_id=s.source_id AND t.status IN ('SUCCEEDED','FAILED')
                      AND t.completed_at>?)
                ORDER BY s.source_id LIMIT ?
                """, String.class, cursor, timestamp(dueBefore), properties.getBatchSize());
    }

    private JobView enqueueLocked(String sourceId, Long requestedBy, boolean scheduled) {
        return enqueueLocked(sourceId, requestedBy, scheduled,
                now().minusSeconds(properties.getSyncIntervalSeconds()));
    }

    private JobView enqueueLocked(String sourceId, Long requestedBy, boolean scheduled, Instant dueBefore) {
        lockSource(sourceId);
        List<JobView> active = jdbc.query("""
                SELECT job_id,source_id,status,attempts,error_code
                FROM biz_daikin_directory_sync_job
                WHERE source_id=? AND status IN ('QUEUED','RUNNING','RETRY_WAIT')
                ORDER BY create_time LIMIT 1
                """, (rs, row) -> view(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getInt(4), rs.getString(5)), sourceId);
        if (!active.isEmpty()) return active.get(0);
        if (scheduled) {
            Integer recent = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM biz_daikin_directory_sync_job
                    WHERE source_id=? AND status IN ('SUCCEEDED','FAILED') AND completed_at>?
                    """, Integer.class, sourceId, timestamp(dueBefore));
            if (recent != null && recent > 0) return null;
        }
        String jobId = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO biz_daikin_directory_sync_job
                  (job_id,source_id,status,attempts,fence_token,lease_token,lease_until,
                   next_attempt_at,error_code,requested_by,create_time,update_time,completed_at)
                VALUES (?,?,'QUEUED',0,0,NULL,NULL,?,NULL,?,?,?,NULL)
                """, jobId, sourceId, timestamp(now()), requestedBy, timestamp(now()), timestamp(now()));
        return new JobView(jobId, sourceId, QUEUED, 0, null);
    }

    private Lease claim(String jobId) {
        List<ClaimRow> rows = jdbc.query("""
                SELECT source_id,status,attempts,fence_token,next_attempt_at,lease_until
                FROM biz_daikin_directory_sync_job WHERE job_id=?
                """, (rs, row) -> new ClaimRow(rs.getString(1), rs.getString(2), rs.getInt(3),
                rs.getLong(4), instant(rs.getTimestamp(5)), instant(rs.getTimestamp(6))), jobId);
        if (rows.size() != 1) return null;
        ClaimRow unlocked = rows.get(0);
        lockSource(unlocked.sourceId());
        rows = jdbc.query("""
                SELECT source_id,status,attempts,fence_token,next_attempt_at,lease_until
                FROM biz_daikin_directory_sync_job WHERE job_id=? FOR UPDATE
                """, (rs, row) -> new ClaimRow(rs.getString(1), rs.getString(2), rs.getInt(3),
                rs.getLong(4), instant(rs.getTimestamp(5)), instant(rs.getTimestamp(6))), jobId);
        if (rows.size() != 1 || !claimable(rows.get(0))) return null;
        ClaimRow row = rows.get(0);
        if (RUNNING.equals(row.status()) && row.attempts() >= properties.getMaxAttempts()) {
            Instant current = now();
            jdbc.update("""
                    UPDATE biz_daikin_directory_sync_job
                    SET status='FAILED',lease_token=NULL,lease_until=NULL,
                        error_code='DAIKIN_SYNC_LEASE_EXPIRED',completed_at=?,update_time=?
                    WHERE job_id=?
                    """, timestamp(current), timestamp(current), jobId);
            return null;
        }
        String token = UUID.randomUUID().toString();
        long fence = row.fence() + 1;
        int attempts = row.attempts() + 1;
        Instant leaseUntil = now().plusSeconds(properties.getLeaseSeconds());
        jdbc.update("""
                UPDATE biz_daikin_directory_sync_job
                SET status='RUNNING',attempts=?,fence_token=?,lease_token=?,lease_until=?,
                    error_code=NULL,update_time=? WHERE job_id=?
                """, attempts, fence, token, timestamp(leaseUntil), timestamp(now()), jobId);
        return new Lease(jobId, row.sourceId(), token, fence, attempts, leaseUntil);
    }

    private boolean claimable(ClaimRow row) {
        Instant current = now();
        return (List.of(QUEUED, RETRY_WAIT).contains(row.status())
                && !row.nextAttemptAt().isAfter(current))
                || (RUNNING.equals(row.status()) && row.leaseUntil() != null
                && !row.leaseUntil().isAfter(current));
    }

    private void commit(Lease lease, List<DaikinDeviceObservation> indoor,
            List<DaikinDeviceObservation> outdoor) {
        transaction.executeWithoutResult(status -> {
            lockSource(lease.sourceId());
            if (!ownsLiveLease(lease)) return;
            Instant roundAt = now();
            directory.acceptCompleteCatalog(lease.sourceId(), DaikinDeviceKey.Kind.INDOOR, roundAt, indoor);
            directory.acceptCompleteCatalog(lease.sourceId(), DaikinDeviceKey.Kind.OUTDOOR, roundAt, outdoor);
            jdbc.update("""
                    UPDATE biz_daikin_directory_sync_job
                    SET status='SUCCEEDED',lease_token=NULL,lease_until=NULL,error_code=NULL,
                        completed_at=?,update_time=? WHERE job_id=?
                    """, timestamp(roundAt), timestamp(roundAt), lease.jobId());
        });
    }

    private void renewLease(Lease lease) {
        Boolean renewed = transaction.execute(status -> {
            lockSource(lease.sourceId());
            if (!ownsLiveLease(lease)) return false;
            jdbc.update("""
                    UPDATE biz_daikin_directory_sync_job
                    SET lease_until=?,update_time=? WHERE job_id=?
                    """, timestamp(now().plusSeconds(properties.getLeaseSeconds())),
                    timestamp(now()), lease.jobId());
            return true;
        });
        if (!Boolean.TRUE.equals(renewed)) throw new LeaseLostException();
    }

    private void failOrRetry(Lease lease, String errorCode) {
        transaction.executeWithoutResult(status -> {
            lockSource(lease.sourceId());
            if (!ownsLiveLease(lease)) return;
            Instant current = now();
            if (lease.attempts() >= properties.getMaxAttempts()) {
                jdbc.update("""
                        UPDATE biz_daikin_directory_sync_job
                        SET status='FAILED',lease_token=NULL,lease_until=NULL,error_code=?,
                            completed_at=?,update_time=? WHERE job_id=?
                        """, errorCode, timestamp(current), timestamp(current), lease.jobId());
            } else {
                long multiplier = 1L << Math.min(lease.attempts() - 1, 20);
                long delay = Math.min(86_400L, properties.getBaseBackoffSeconds() * multiplier);
                jdbc.update("""
                        UPDATE biz_daikin_directory_sync_job
                        SET status='RETRY_WAIT',lease_token=NULL,lease_until=NULL,error_code=?,
                            next_attempt_at=?,update_time=? WHERE job_id=?
                        """, errorCode, timestamp(current.plusSeconds(delay)), timestamp(current), lease.jobId());
            }
        });
    }

    private boolean ownsLiveLease(Lease lease) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_daikin_directory_sync_job
                WHERE job_id=? AND source_id=? AND status='RUNNING' AND lease_token=?
                  AND fence_token=? AND lease_until>?
                """, Integer.class, lease.jobId(), lease.sourceId(), lease.token(), lease.fence(), timestamp(now()));
        return count != null && count == 1;
    }

    private void requireAvailable(String sourceId) {
        if (!properties.isEnabled()) {
            throw new BusinessException(503, "DAIKIN_SYNC_DISABLED", "大金目录同步未启用");
        }
        if (provider.flatMap(value -> safeClient(value, sourceId)).isEmpty()) {
            throw new BusinessException(503, "DAIKIN_SYNC_PROVIDER_UNAVAILABLE", "大金目录同步来源未配置");
        }
    }

    private Optional<DaikinCatalogClient> safeClient(DaikinCatalogClientProvider value, String sourceId) {
        try {
            Optional<DaikinCatalogClient> client = value.clientFor(sourceId);
            return client == null ? Optional.empty() : client;
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private void lockSource(String sourceId) {
        List<String> rows = jdbc.queryForList(
                "SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE",
                String.class, sourceId);
        if (rows.size() != 1) {
            throw new BusinessException(404, "DAIKIN_SOURCE_NOT_FOUND", "大金来源不存在");
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static String safeErrorCode(RuntimeException exception) {
        if (exception instanceof ProviderUnavailableException) return "DAIKIN_SYNC_PROVIDER_UNAVAILABLE";
        if (exception instanceof LeaseLostException) return "DAIKIN_SYNC_LEASE_LOST";
        if (exception instanceof DaikinClientException clientException) {
            return "DAIKIN_SYNC_" + clientException.code().name();
        }
        if (exception instanceof DataAccessException) return "DAIKIN_SYNC_PERSISTENCE_FAILED";
        if (exception instanceof BusinessException) return "DAIKIN_SYNC_CATALOG_INVALID";
        if (exception instanceof IllegalArgumentException) return "DAIKIN_SYNC_CATALOG_INVALID";
        return "DAIKIN_SYNC_UPSTREAM_FAILED";
    }

    private static String requireId(String value, int max, String message) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new BusinessException(400, "DAIKIN_SYNC_INVALID_REQUEST", message);
        }
        return value;
    }

    private static BusinessException invalid() {
        return new BusinessException(400, "DAIKIN_SYNC_INVALID_REQUEST", "同步请求无效");
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, MYSQL_ZONE));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().atZone(MYSQL_ZONE).toInstant();
    }

    private static JobView view(String jobId, String sourceId, String status,
            int attempts, String errorCode) {
        return new JobView(jobId, sourceId, status, attempts, errorCode);
    }

    public record JobView(String jobId, String sourceId, String status,
                          int attempts, String errorCode) { }

    private record ClaimRow(String sourceId, String status, int attempts, long fence,
                            Instant nextAttemptAt, Instant leaseUntil) { }

    private record Lease(String jobId, String sourceId, String token, long fence,
                         int attempts, Instant leaseUntil) { }

    private static final class ProviderUnavailableException extends RuntimeException { }
    private static final class LeaseLostException extends RuntimeException { }
}
