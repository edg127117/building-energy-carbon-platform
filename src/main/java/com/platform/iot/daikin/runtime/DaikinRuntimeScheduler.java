package com.platform.iot.daikin.runtime;

import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.monitoring.DaikinMonitoringTargets;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.platform.iot.daikin.runtime.DaikinRuntimeClientProvider.*;
import static com.platform.iot.daikin.runtime.DaikinRuntimePeriod.Granularity.*;

/**
 * 北京时间 03:00 生成持久日计划，独立工作线程按最新期间优先补取。来源锁和租约令牌保护
 * 多实例/重启恢复；HTTP 在事务外执行，失效租约不得写值。永久不支持不重试，暂时失败有限重试后
 * 保留任务至下一日再补取，并以独立 RUNTIME_FETCH 异常暴露，不干扰分钟采集失败计数。
 */
@Service
public class DaikinRuntimeScheduler {
    static final long YEAR_MS = Duration.ofDays(365).toMillis();
    private static final ZoneId SCHEDULE_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final DaikinRuntimeProperties properties;
    private final Optional<DaikinRuntimeClientProvider> provider;
    private final DaikinMonitoringTargets targets;
    private final DaikinRuntimeStore store;
    private final Clock clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task ->
            Thread.ofPlatform().daemon().name("daikin-runtime").unstarted(task));
    private final AtomicBoolean running = new AtomicBoolean();
    private String sourceCursor = "";

    @Autowired
    public DaikinRuntimeScheduler(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, TransactionTemplate transaction,
            DaikinRuntimeProperties properties, Optional<DaikinRuntimeClientProvider> provider,
            DaikinMonitoringTargets targets, DaikinRuntimeStore store) {
        this(jdbc, transaction, properties, provider, targets, store, Clock.systemUTC());
    }

    DaikinRuntimeScheduler(JdbcTemplate jdbc, TransactionTemplate transaction, DaikinRuntimeProperties properties,
            Optional<DaikinRuntimeClientProvider> provider, DaikinMonitoringTargets targets,
            DaikinRuntimeStore store, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.properties = properties;
        this.provider = provider;
        this.targets = targets;
        this.store = store;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${daikin.runtime.schedule-delay-ms:10000}")
    public void schedule() {
        if (!properties.isEnabled() || provider.isEmpty() || !running.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try { tick(); }
                finally { running.set(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) { running.set(false); }
    }

    void tick() {
        if (!properties.isEnabled() || provider.isEmpty()) return;
        var sources = jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id>? ORDER BY source_id LIMIT ?",
                String.class, sourceCursor, properties.getSourceBatchSize());
        if (sources.isEmpty()) { sourceCursor = ""; return; }
        sourceCursor = sources.getLast();
        for (String source : sources) {
            try { runSource(source); }
            catch (RuntimeException failure) {
                // 不持久化外部异常文本，避免响应或凭据泄露；单来源异常不能阻塞后续来源。
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("大金运行统计任务未完成，等待下轮恢复");
            }
        }
    }

    void runSource(String source) {
        if (!properties.isEnabled() || provider.isEmpty()) return;
        var client = provider.flatMap(value -> value.clientFor(source));
        if (client.isEmpty()) return;
        Semantics semantics = client.get().semantics();
        var snapshot = targets.forSource(source);
        if (snapshot.isEmpty()) return;
        transaction.executeWithoutResult(status -> plan(source, semantics, snapshot));
        for (int n = 0; n < properties.getJobsPerSource(); n++) {
            Job job = transaction.execute(status -> claim(source));
            if (job == null) break;
            var selected = snapshot.stream().filter(t -> t.key().kind() == job.kind()).toList();
            try {
                if (!semantics.equals(client.get().semantics())) throw new IllegalStateException("DAIKIN_RUNTIME_SEMANTICS_CHANGED");
                Batch batch = client.get().read(source, job.kind(), job.period(), () -> renew(job));
                if (!semantics.equals(client.get().semantics())) throw new IllegalStateException("DAIKIN_RUNTIME_SEMANTICS_CHANGED");
                var readings = new HashMap<DaikinDeviceKey, Reading>();
                for (Reading reading : batch.readings()) {
                    if (!reading.key().sourceId().equals(source) || reading.key().kind() != job.kind()
                            || readings.putIfAbsent(reading.key(), reading) != null) {
                        throw new IllegalArgumentException("DAIKIN_RUNTIME_INVALID_BATCH");
                    }
                }
                transaction.executeWithoutResult(status -> {
                    requireLease(job);
                    boolean missing = false;
                    boolean allUnsupported = true;
                    int accepted = 0;
                    for (var target : selected) {
                        if (targets.find(target.pendingId()).filter(target::equals).isEmpty()) continue;
                        Reading reading = readings.get(target.key());
                        String attempt = batch.unsupported() ? "UNSUPPORTED" : reading == null ? "MISSING" : reading.status().name();
                        missing |= attempt.equals("MISSING");
                        allUnsupported &= attempt.equals("UNSUPPORTED");
                        store.save(target, job.period(), job.unit(), reading, attempt, clock.millis());
                        accepted++;
                    }
                    if (accepted == 0) finish(job, false, false);
                    else finish(job, !missing, allUnsupported);
                });
            } catch (RuntimeException failure) {
                transaction.executeWithoutResult(status -> {
                    if (!leaseAlive(job)) return;
                    for (var target : selected) {
                        if (targets.find(target.pendingId()).filter(target::equals).isPresent()) {
                            store.save(target, job.period(), job.unit(), null, "FAILED", clock.millis());
                        }
                    }
                    finish(job, false, false);
                });
            }
        }
    }

    static long latestSchedule(long now) {
        var local = Instant.ofEpochMilli(now).atZone(SCHEDULE_ZONE);
        var scheduled = local.toLocalDate().atTime(3, 0).atZone(SCHEDULE_ZONE);
        if (scheduled.toInstant().toEpochMilli() > now) scheduled = scheduled.minusDays(1);
        return scheduled.toInstant().toEpochMilli();
    }

    private void plan(String source, Semantics semantics, List<DaikinMonitoringTargets.Target> snapshot) {
        lockSource(source);
        long due = latestSchedule(clock.millis());
        String targetsHash = DaikinRuntimeStore.hash(snapshot.stream().map(Object::toString).sorted().toArray(String[]::new));
        var old = jdbc.query("SELECT semantics_version,unit,statistics_zone,planned_at_ms,targets_hash FROM biz_daikin_runtime_plan WHERE source_id=?",
                (rs, row) -> new Plan(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5)), source);
        boolean targetsChanged = old.isEmpty() || !old.getFirst().targetsHash().equals(targetsHash);
        if (!old.isEmpty()) {
            Plan p = old.getFirst();
            if (!p.version().equals(semantics.version()) || !p.unit().equals(semantics.unit())
                    || !p.zone().equals(semantics.statisticsZone().getId())) {
                throw new IllegalStateException("DAIKIN_RUNTIME_SEMANTICS_CHANGED_REQUIRES_MIGRATION");
            }
            if (p.plannedAt() >= due && !targetsChanged) return;
        }
        LocalDate today = Instant.ofEpochMilli(due).atZone(semantics.statisticsZone()).toLocalDate();
        List<DaikinRuntimePeriod> periods = new ArrayList<>();
        // 每来源最多约 800 个自然期间任务。检查点与入队同事务，重启不丢漏；任务本身是历史补取游标。
        for (int day = 1; day <= 366; day++) periods.add(new DaikinRuntimePeriod(DAY, today.minusDays(day), semantics.statisticsZone()));
        for (int month = 0; month <= 12; month++) periods.add(new DaikinRuntimePeriod(MONTH, today.withDayOfMonth(1).minusMonths(month), semantics.statisticsZone()));
        for (int year = 0; year <= 1; year++) periods.add(new DaikinRuntimePeriod(YEAR, today.withDayOfYear(1).minusYears(year), semantics.statisticsZone()));
        var kinds = snapshot.stream().map(t -> t.key().kind()).distinct().toList();
        for (var kind : kinds) {
            for (var period : periods) {
                if (period.endMillis() < clock.millis() - YEAR_MS) continue;
                String id = DaikinRuntimeStore.hash(source, kind.name(), period.granularity().name(),
                        String.valueOf(period.startMillis()), String.valueOf(period.endMillis()));
                var existing = jdbc.queryForList("SELECT status FROM biz_daikin_runtime_job WHERE job_id=?", String.class, id);
                if (existing.isEmpty()) {
                    jdbc.update("""
                            INSERT INTO biz_daikin_runtime_job(job_id,source_id,device_kind,granularity,period_start_ms,
                              period_end_ms,statistics_zone,unit,semantics_version,status,planned_at_ms)
                            VALUES (?,?,?,?,?,?,?,?,?,'QUEUED',?)
                            """, id, source, kind.name(), period.granularity().name(), period.startMillis(), period.endMillis(),
                            semantics.statisticsZone().getId(), semantics.unit(), semantics.version(), due);
                } else {
                    boolean recent = switch (period.granularity()) {
                        case DAY -> !period.start().isBefore(today.minusDays(7));
                        case MONTH -> period.start().equals(today.withDayOfMonth(1))
                                || today.getDayOfMonth() <= 7 && period.start().equals(today.withDayOfMonth(1).minusMonths(1));
                        case YEAR -> period.start().equals(today.withDayOfYear(1))
                                || today.getDayOfYear() <= 7 && period.start().equals(today.withDayOfYear(1).minusYears(1));
                    };
                    // 相同目标范围的不支持不重试；新增绑定后需重新确认其能力，失败至下一日再有限重试。
                    if (existing.getFirst().equals("FAILED") || (recent || targetsChanged) && existing.getFirst().equals("SUCCEEDED")
                            || targetsChanged && existing.getFirst().equals("UNSUPPORTED")) {
                        jdbc.update("UPDATE biz_daikin_runtime_job SET status='QUEUED',attempts=0,next_attempt_at_ms=0,planned_at_ms=? WHERE job_id=?",
                                due, id);
                    }
                }
            }
        }
        if (old.isEmpty()) jdbc.update("INSERT INTO biz_daikin_runtime_plan VALUES (?,?,?,?,?,?)", source,
                semantics.version(), semantics.statisticsZone().getId(), semantics.unit(), targetsHash, due);
        else jdbc.update("UPDATE biz_daikin_runtime_plan SET planned_at_ms=?,targets_hash=? WHERE source_id=?", due, targetsHash, source);
    }

    private Job claim(String source) {
        lockSource(source);
        long now = clock.millis();
        jdbc.update("""
                UPDATE biz_daikin_runtime_job SET status='EXPIRED',lease_token=NULL,lease_until_ms=0
                WHERE source_id=? AND period_end_ms<? AND (status<>'RUNNING' OR lease_until_ms<=?)
                """, source, now - YEAR_MS, now);
        var candidates = jdbc.query("""
                SELECT job_id,device_kind,granularity,period_start_ms,statistics_zone,unit,attempts
                FROM biz_daikin_runtime_job WHERE source_id=? AND period_end_ms>=?
                  AND ((status IN ('QUEUED','RETRY_WAIT') AND next_attempt_at_ms<=?)
                    OR (status='RUNNING' AND lease_until_ms<=?))
                ORDER BY period_start_ms DESC,granularity,job_id LIMIT 1
                """, (rs, row) -> new Job(rs.getString(1), source, DaikinDeviceKey.Kind.valueOf(rs.getString(2)),
                new DaikinRuntimePeriod(DaikinRuntimePeriod.Granularity.valueOf(rs.getString(3)),
                        Instant.ofEpochMilli(rs.getLong(4)).atZone(ZoneId.of(rs.getString(5))).toLocalDate(), ZoneId.of(rs.getString(5))),
                rs.getString(6), rs.getInt(7) + 1, UUID.randomUUID().toString()), source, now - YEAR_MS, now, now);
        if (candidates.isEmpty()) { recoverException(source, now); return null; }
        Job job = candidates.getFirst();
        if (job.attempt() > properties.getMaxAttempts()) {
            jdbc.update("UPDATE biz_daikin_runtime_job SET status='FAILED',error_code='RETRY_EXHAUSTED',lease_token=NULL,lease_until_ms=0 WHERE job_id=?", job.id());
            openException(source, now);
            return null;
        }
        jdbc.update("UPDATE biz_daikin_runtime_job SET status='RUNNING',attempts=?,lease_token=?,lease_until_ms=? WHERE job_id=?",
                job.attempt(), job.token(), now + properties.getLeaseSeconds() * 1000L, job.id());
        return job;
    }

    private void renew(Job job) {
        transaction.executeWithoutResult(status -> {
            requireLease(job);
            jdbc.update("UPDATE biz_daikin_runtime_job SET lease_until_ms=? WHERE job_id=?", clock.millis() + properties.getLeaseSeconds() * 1000L, job.id());
        });
    }

    private boolean leaseAlive(Job job) {
        lockSource(job.source());
        return !jdbc.queryForList("""
                SELECT job_id FROM biz_daikin_runtime_job WHERE job_id=? AND status='RUNNING'
                  AND lease_token=? AND lease_until_ms>? AND period_end_ms>=? FOR UPDATE
                """, String.class, job.id(), job.token(), clock.millis(), clock.millis() - YEAR_MS).isEmpty();
    }

    private void requireLease(Job job) {
        if (!leaseAlive(job)) throw new IllegalStateException("DAIKIN_RUNTIME_LEASE_EXPIRED");
    }

    private void finish(Job job, boolean success, boolean unsupported) {
        long now = clock.millis();
        String result = unsupported ? "UNSUPPORTED" : success ? "SUCCEEDED"
                : job.attempt() >= properties.getMaxAttempts() ? "FAILED" : "RETRY_WAIT";
        jdbc.update("""
                UPDATE biz_daikin_runtime_job SET status=?,next_attempt_at_ms=?,lease_token=NULL,lease_until_ms=0,error_code=? WHERE job_id=?
                """, result, now + properties.getRetrySeconds() * 1000L * job.attempt(), success ? null : "RUNTIME_FETCH_FAILED", job.id());
        if (result.equals("FAILED")) openException(job.source(), now);
        else if (success) recoverException(job.source(), now);
    }

    private void lockSource(String source) {
        if (jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE", String.class, source).isEmpty()) {
            throw new IllegalStateException("DAIKIN_RUNTIME_SOURCE_REMOVED");
        }
    }

    private void openException(String source, long now) {
        String key = "SOURCE:" + source + ":RUNTIME_FETCH";
        var existing = jdbc.queryForList("SELECT exception_id FROM biz_daikin_exception_instance WHERE active_key=? FOR UPDATE", Long.class, key);
        if (existing.isEmpty()) jdbc.update("""
                INSERT INTO biz_daikin_exception_instance(active_key,exception_type,scope_type,source_id,
                  first_detected_at_ms,last_detected_at_ms,last_round_id) VALUES (?,'RUNTIME_FETCH','SOURCE',?,?,?,?)
                """, key, source, now, now, now);
        else jdbc.update("UPDATE biz_daikin_exception_instance SET last_detected_at_ms=?,last_round_id=? WHERE active_key=?", now, now, key);
    }

    private void recoverException(String source, long now) {
        // 次日重排失败任务仍保留 error_code，直到所有未解决失败成功/不支持/过期才恢复异常。
        Long failures = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_daikin_runtime_job WHERE source_id=? AND period_end_ms>=?
                  AND error_code IS NOT NULL AND status NOT IN ('SUCCEEDED','UNSUPPORTED','EXPIRED')
                """, Long.class, source, now - YEAR_MS);
        if (failures != null && failures == 0) jdbc.update("""
                UPDATE biz_daikin_exception_instance SET active_key=NULL,recovered_at_ms=?,last_detected_at_ms=?
                WHERE active_key=?
                """, now, now, "SOURCE:" + source + ":RUNTIME_FETCH");
    }

    @PreDestroy
    public void close() { worker.shutdownNow(); }

    private record Plan(String version, String unit, String zone, long plannedAt, String targetsHash) { }
    private record Job(String id, String source, DaikinDeviceKey.Kind kind, DaikinRuntimePeriod period,
                       String unit, int attempt, String token) { }
}
