package com.platform.iot.qualityusage;

import com.platform.config.DataQualityProperties;
import com.platform.iot.qualityusage.QualityUsageModels.RuntimeApplyStatus;
import com.platform.iot.qualityusage.QualityUsageModels.RuntimeSnapshot;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Service
/**
 * 以全局修订号驱动单实例不可变质量使用快照。
 *
 * <p>刷新失败保留旧快照；首次没有可用快照时消费端失败关闭。完整快照在后台构建
 * 完成后通过一个原子引用切换，实时查询线程不会持有数据库锁或等待配置加载。</p>
 */
public class QualityUsageRuntimeStateService {
    private static final Logger log = LoggerFactory.getLogger(QualityUsageRuntimeStateService.class);

    private final QualityUsagePolicyRepository repository;
    private final DataQualityProperties dataQualityProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final AtomicReference<RuntimeSnapshot> snapshot = new AtomicReference<>();
    private volatile RuntimeApplyStatus applyStatus = RuntimeApplyStatus.PENDING_REFRESH;
    private volatile String failureReason;
    private Counter refreshFailureCounter;

    public QualityUsageRuntimeStateService(
            QualityUsagePolicyRepository repository,
            DataQualityProperties dataQualityProperties,
            ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.dataQualityProperties = dataQualityProperties;
        this.eventPublisher = eventPublisher;
    }

    @Autowired(required = false)
    void configureMetrics(MeterRegistry registry) {
        refreshFailureCounter = registry.counter("quality.usage.snapshot.refresh.failures");
        Gauge.builder("quality.usage.snapshot.runtime.revision", this,
                        QualityUsageRuntimeStateService::runtimeRevision)
                .register(registry);
    }

    @PostConstruct
    public void initialize() {
        refreshIfChanged(true);
    }

    @Scheduled(fixedDelayString = "${quality-usage.revision-check-ms:60000}")
    public void periodicRevisionCheck() {
        refreshIfChanged(false);
    }

    public synchronized boolean refreshIfChanged(boolean force) {
        RuntimeSnapshot previous = snapshot.get();
        try {
            long databaseRevision = repository.currentRevision();
            if (!force && previous != null && previous.revision() == databaseRevision) {
                return false;
            }
            long recoveryWindowMs = Duration.ofHours(
                    dataQualityProperties.getLateRealCorrectionHours()).toMillis();
            RuntimeSnapshot loaded = repository.loadRuntimeSnapshot(
                    System.currentTimeMillis() - recoveryWindowMs);
            if (loaded.revision() != databaseRevision) {
                throw new IllegalStateException("Quality usage revision changed during load");
            }
            long previousRevision = previous == null ? 0 : previous.revision();
            Set<QualityUsageModels.PolicyKey> affected = previous == null
                    ? Set.of()
                    : affectedPolicies(previous, loaded);
            Long earliestAffectedMinute = previous == null || affected.isEmpty()
                    ? null
                    : repository.earliestAffectedMinute(
                            previousRevision, loaded.revision());
            if (previous != null && !affected.isEmpty() && earliestAffectedMinute == null) {
                // 场景启停没有策略版本生效分钟，按全历史变化处理并交给有界恢复标记超窗。
                earliestAffectedMinute = 0L;
            }
            // 先完成全部数据库读取和差异计算，再原子切换；刷新失败绝不暴露半应用状态。
            snapshot.set(loaded);
            applyStatus = RuntimeApplyStatus.APPLIED;
            failureReason = null;
            if (loaded.revision() != previousRevision) {
                eventPublisher.publishEvent(new QualityUsageRuntimeRefreshedEvent(
                        previousRevision, loaded.revision(), affected,
                        earliestAffectedMinute));
            }
            return true;
        } catch (RuntimeException exception) {
            applyStatus = RuntimeApplyStatus.REFRESH_FAILED;
            failureReason = "QUALITY_POLICY_REFRESH_FAILED";
            if (refreshFailureCounter != null) {
                refreshFailureCounter.increment();
            }
            log.warn("Quality usage runtime snapshot refresh failed; previous snapshot is retained",
                    exception);
            return false;
        }
    }

    /** 在治理事务提交后立即比较修订；回滚事务不会触发运行态变化。 */
    public void refreshAfterCommit() {
        Runnable refresh = () -> refreshIfChanged(false);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            refresh.run();
                        }
                    });
        } else {
            refresh.run();
        }
    }

    RuntimeSnapshot requireSnapshot() {
        RuntimeSnapshot current = snapshot.get();
        if (current == null) {
            throw new QualityUsageSnapshotUnavailableException(
                    "质量使用策略快照暂不可用");
        }
        return current;
    }

    RuntimeSnapshot loadRange(
            Set<String> pointIds, String scenarioCode, long from, long to) {
        try {
            return repository.loadRange(pointIds, scenarioCode, from, to);
        } catch (RuntimeException exception) {
            throw new QualityUsageSnapshotUnavailableException(
                    "历史质量使用策略暂不可用", exception);
        }
    }

    public long runtimeRevision() {
        RuntimeSnapshot current = snapshot.get();
        return current == null ? 0 : current.revision();
    }

    public RuntimeApplyStatus applyStatus() {
        return applyStatus;
    }

    public String failureReason() {
        return failureReason;
    }

    private Set<QualityUsageModels.PolicyKey> affectedPolicies(
            RuntimeSnapshot previous, RuntimeSnapshot loaded) {
        Set<QualityUsageModels.PolicyKey> affected = new LinkedHashSet<>(
                repository.affectedPolicies(previous.revision(), loaded.revision()));
        Set<QualityUsageModels.PolicyKey> allKeys = new LinkedHashSet<>(previous.policies().keySet());
        allKeys.addAll(loaded.policies().keySet());
        for (QualityUsageModels.PolicyKey key : allKeys) {
            boolean scenarioChanged = !Objects.equals(
                    previous.scenarios().get(key.scenarioCode()),
                    loaded.scenarios().get(key.scenarioCode()));
            if (scenarioChanged || !Objects.equals(
                    previous.policies().get(key), loaded.policies().get(key))) {
                affected.add(key);
            }
        }
        return Set.copyOf(affected);
    }
}
