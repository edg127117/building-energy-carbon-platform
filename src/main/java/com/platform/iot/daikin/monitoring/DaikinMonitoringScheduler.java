package com.platform.iot.daikin.monitoring;

import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.monitoring.state.DaikinMonitoringStateService;
import com.platform.iot.daikin.sync.DaikinCatalogClientProvider;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 分钟观测调度：来源行锁保护轮次领取，短事务外访问厂家，逐页检查租约并提交观测箱。
 * 重试沿用计划轮次，最终失败才计一次来源失败；部分有效页不会因后续分页失败被撤销。
 */
@Service
public class DaikinMonitoringScheduler {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final DaikinMonitoringProperties properties;
    private final Optional<DaikinCatalogClientProvider> provider;
    private final DaikinMonitoringTargets targets;
    private final DaikinObservationInbox inbox;
    private final DaikinMonitoringStateService state;
    private final Clock clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task ->
            Thread.ofPlatform().daemon().name("daikin-monitoring").unstarted(task));
    private final AtomicBoolean running = new AtomicBoolean();
    private String sourceCursor = "";
    private String targetCursor = "";

    @Autowired
    public DaikinMonitoringScheduler(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
            TransactionTemplate transaction, DaikinMonitoringProperties properties,
            Optional<DaikinCatalogClientProvider> provider, DaikinMonitoringTargets targets,
            DaikinObservationInbox inbox, DaikinMonitoringStateService state) {
        this(jdbc, transaction, properties, provider, targets, inbox, state, Clock.systemUTC());
    }

    DaikinMonitoringScheduler(JdbcTemplate jdbc, TransactionTemplate transaction,
            DaikinMonitoringProperties properties, Optional<DaikinCatalogClientProvider> provider,
            DaikinMonitoringTargets targets, DaikinObservationInbox inbox,
            DaikinMonitoringStateService state, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.properties = properties;
        this.provider = provider;
        this.targets = targets;
        this.inbox = inbox;
        this.state = state;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${daikin.monitoring.schedule-delay-ms:5000}")
    public void schedule() {
        if (!properties.isEnabled() || provider.isEmpty() || !running.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try { tick(); }
                finally { running.set(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { running.set(false); }
    }

    void tick() {
        if (!properties.isEnabled() || provider.isEmpty()) return;
        inbox.replay();
        var sources = jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id>? ORDER BY source_id LIMIT ?",
                String.class, sourceCursor, properties.getSourceBatchSize());
        if (sources.isEmpty()) { sourceCursor = ""; return; }
        sourceCursor = sources.getLast();
        for (String source : sources) runSource(source);
    }

    void runSource(String source) {
        if (!properties.isEnabled() || provider.isEmpty()) return;
        var plannedTargets = targets.forSource(source);
        if (plannedTargets.isEmpty()) return;
        Lease lease = transaction.execute(status -> claim(source));
        if (lease == null) return;
        try {
            var byKey = new HashMap<DaikinDeviceKey, DaikinMonitoringTargets.Target>();
            transaction.executeWithoutResult(status -> {
                requireLease(lease);
                for (var target : plannedTargets) {
                    if (targets.find(target.pendingId()).filter(target::equals).isPresent()) {
                        state.registerTarget(DaikinObservationInbox.stateTarget(target), lease.round());
                        byKey.put(target.key(), target);
                    }
                }
            });
            var client = provider.flatMap(value -> value.clientFor(source))
                    .orElseThrow(() -> new IllegalStateException("DAIKIN_PROVIDER_UNAVAILABLE"));
            var covered = new HashSet<DaikinDeviceKey>();
            for (var kind : DaikinDeviceKey.Kind.values()) {
                if (byKey.keySet().stream().noneMatch(key -> key.kind() == kind)) continue;
                client.visitPages(source, kind, () -> renew(lease), page -> {
                    for (var observation : page.devices()) {
                        var target = byKey.get(observation.key());
                        if (target == null) continue;
                        String id = transaction.execute(status -> {
                            requireLease(lease);
                            if (targets.find(target.pendingId()).filter(target::equals).isEmpty()) return null;
                            return inbox.stage(target, lease.round(), observation);
                        });
                        if (id != null) {
                            inbox.process(id);
                            if (DaikinMonitoringStateService.hasValidRuntimeFields(observation)) covered.add(observation.key());
                        }
                    }
                });
            }
            if (covered.size() != byKey.size() || byKey.isEmpty()) throw new IllegalStateException("DAIKIN_INCOMPLETE_OBSERVATION");
            complete(lease, true, covered.size());
        } catch (RuntimeException failure) {
            complete(lease, false, 0);
        }
    }

    private Lease claim(String source) {
        jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE", String.class, source);
        long now = clock.millis();
        long interval = properties.getIntervalSeconds() * 1000L;
        long planned = now / interval * interval;
        var previous = jdbc.query("SELECT round_id,status,attempts,lease_until,next_attempt_at FROM biz_daikin_monitor_round WHERE source_id=?",
                (rs, row) -> new Previous(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4), rs.getLong(5)), source);
        int attempt = 1;
        if (!previous.isEmpty()) {
            Previous old = previous.getFirst();
            if (old.status().equals("RUNNING") && old.leaseUntil() > now || old.nextAttempt() > now) return null;
            if (old.status().equals("RUNNING") || old.status().equals("RETRY_WAIT")) {
                if (old.attempts() >= properties.getMaxAttempts()) {
                    state.recordFailure(source, old.round());
                    jdbc.update("UPDATE biz_daikin_monitor_round SET status='FAILED',completed_at=?,lease_token=NULL,lease_until=0 WHERE source_id=?", now, source);
                    return null;
                }
                planned = old.round();
                attempt = old.attempts() + 1;
            } else if (old.round() >= planned) return null;
            // 只清理已完成旧轮次的幂等墓碑；未写入TDengine的载荷继续保留并重放。
            jdbc.update("DELETE FROM biz_daikin_monitor_inbox WHERE source_id=? AND round_id<? AND status='DONE'", source, planned);
        }
        Lease lease = new Lease(source, planned, UUID.randomUUID().toString(), attempt);
        if (previous.isEmpty()) {
            jdbc.update("INSERT INTO biz_daikin_monitor_round(source_id,round_id,status,attempts,lease_token,lease_until,next_attempt_at) VALUES (?,?,'RUNNING',?,?,?,0)",
                    source, planned, attempt, lease.token(), now + properties.getLeaseSeconds() * 1000L);
        } else {
            jdbc.update("UPDATE biz_daikin_monitor_round SET round_id=?,status='RUNNING',attempts=?,lease_token=?,lease_until=?,next_attempt_at=0,completed_at=NULL,error_code=NULL,covered_devices=0 WHERE source_id=?",
                    planned, attempt, lease.token(), now + properties.getLeaseSeconds() * 1000L, source);
        }
        return lease;
    }

    private void renew(Lease lease) {
        transaction.executeWithoutResult(status -> {
            requireLease(lease);
            jdbc.update("UPDATE biz_daikin_monitor_round SET lease_until=? WHERE source_id=?", clock.millis() + properties.getLeaseSeconds() * 1000L, lease.source());
        });
    }

    private void requireLease(Lease lease) {
        jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE", String.class, lease.source());
        int owned = jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_monitor_round WHERE source_id=? AND round_id=? AND lease_token=? AND status='RUNNING' AND lease_until>?",
                Integer.class, lease.source(), lease.round(), lease.token(), clock.millis());
        if (owned != 1) throw new LostLease();
    }

    private void complete(Lease lease, boolean success, int covered) {
        try {
            transaction.executeWithoutResult(status -> {
                requireLease(lease);
                boolean terminal = success || lease.attempt() >= properties.getMaxAttempts();
                if (terminal) {
                    if (success) state.recordSuccess(lease.source(), lease.round());
                    else state.recordFailure(lease.source(), lease.round());
                }
                jdbc.update("UPDATE biz_daikin_monitor_round SET status=?,lease_token=NULL,lease_until=0,next_attempt_at=?,completed_at=?,error_code=?,covered_devices=? WHERE source_id=?",
                        success ? "SUCCEEDED" : terminal ? "FAILED" : "RETRY_WAIT",
                        clock.millis() + properties.getRetrySeconds() * 1000L, terminal ? clock.millis() : null,
                        success ? null : "DAIKIN_MONITORING_FETCH_FAILED", covered, lease.source());
            });
        } catch (LostLease ignored) { /* 过期worker无权结束已被其他实例领取的轮次。 */ }
    }

    /** 独立于厂家请求开关和worker运行；采集停止后仍需发现已有正式监测目标过期。 */
    @Scheduled(fixedDelayString = "${daikin.monitoring.stale-scan-delay-ms:30000}")
    public synchronized void scanStale() {
        var registered = jdbc.query("SELECT identity_id,pending_id FROM biz_daikin_monitoring_target WHERE active=1 AND identity_id>? ORDER BY identity_id LIMIT 500",
                (rs, row) -> new String[] {rs.getString(1), rs.getString(2)}, targetCursor);
        if (registered.isEmpty()) targetCursor = "";
        else targetCursor = registered.getLast()[0];
        for (String[] target : registered) if (targets.find(target[1]).isEmpty()) state.deactivateTarget(target[0]);
        state.scanStale(clock.millis(), 500, target -> targets.find(target.pendingId())
                .filter(current -> DaikinObservationInbox.stateTarget(current).equals(target)).isPresent());
    }

    @PreDestroy
    void stop() { worker.shutdownNow(); }

    private record Lease(String source, long round, String token, int attempt) { }
    private record Previous(long round, String status, int attempts, long leaseUntil, long nextAttempt) { }
    private static final class LostLease extends RuntimeException { }
}
