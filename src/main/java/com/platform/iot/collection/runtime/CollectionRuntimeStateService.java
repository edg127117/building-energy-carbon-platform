package com.platform.iot.collection.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.hvac.mapper.BizPointAliasMapper;
import com.platform.hvac.model.entity.BizPointAlias;
import com.platform.iot.collection.mapper.BizCollectionPolicyMapper;
import com.platform.iot.collection.mapper.BizCollectionPolicyVersionMapper;
import com.platform.iot.collection.mapper.BizDataSourceMapper;
import com.platform.iot.collection.model.CollectionModels.PolicyVersionStatus;
import com.platform.iot.collection.model.CollectionModels.RuntimeApplyStatus;
import com.platform.iot.collection.model.CollectionModels.SourceStatus;
import com.platform.iot.collection.model.entity.BizCollectionPolicy;
import com.platform.iot.collection.model.entity.BizCollectionPolicyVersion;
import com.platform.iot.collection.model.entity.BizDataSource;
import com.platform.iot.quality.MySqlDataPointConfigProvider;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 当前单体实例的采集治理运行快照与应用状态。
 *
 * <p>MySQL 正式配置是权威事实；内存只表达本实例是否成功加载对应 runtimeRevision。
 * 刷新失败保留上一完整快照并标记失败，不能反向回滚已提交事务。</p>
 */
public class CollectionRuntimeStateService {
    private static final ZoneId MYSQL_ZONE = ZoneId.of("Asia/Shanghai");

    private final BizDataSourceMapper sourceMapper;
    private final BizPointAliasMapper aliasMapper;
    private final BizCollectionPolicyMapper policyMapper;
    private final BizCollectionPolicyVersionMapper versionMapper;
    private final MySqlDataPointConfigProvider pointConfigProvider;

    private volatile RuntimeSnapshot snapshot = RuntimeSnapshot.empty();
    private final Map<String, ApplyState> applyStates = new java.util.concurrent.ConcurrentHashMap<>();

    @PostConstruct
    public void initialize() {
        refreshAll();
    }

    /** 60 秒是配置收敛兜底周期，与测点采样周期和允许迟到时间无关。 */
    @Scheduled(fixedDelayString = "${collection.runtime-refresh-ms:60000}")
    public void periodicRefresh() {
        refreshAll();
    }

    public synchronized boolean refreshAll() {
        LocalDateTime attemptAt = LocalDateTime.now(MYSQL_ZONE);
        try {
            RuntimeSnapshot next = loadSnapshot();
            pointConfigProvider.refreshAllOrThrow();
            snapshot = next;
            next.sourceRevisions().forEach((sourceId, revision) -> applyStates.put(sourceId,
                    new ApplyState(revision, RuntimeApplyStatus.APPLIED, attemptAt, null, attemptAt)));
            sourceMapper.selectList(new LambdaQueryWrapper<>()).stream()
                    .filter(source -> !SourceStatus.ENABLED.name().equals(source.getStatus()))
                    .forEach(source -> applyStates.put(source.getSourceId(), new ApplyState(
                            source.getRuntimeRevision(), RuntimeApplyStatus.NOT_APPLICABLE,
                            null, null, attemptAt)));
            return true;
        } catch (RuntimeException exception) {
            applyStates.replaceAll((sourceId, state) -> new ApplyState(
                    state.appliedRuntimeRevision(), RuntimeApplyStatus.REFRESH_FAILED,
                    state.appliedAt(), "COLLECTION_RUNTIME_REFRESH_FAILED", attemptAt));
            log.warn("采集治理配置刷新失败，继续使用上一完整快照: {}", exception.getMessage());
            return false;
        }
    }

    /** 注册事务后刷新，保证内存永远不会先于 MySQL 提交观察到候选配置。 */
    public void refreshAfterCommit(String sourceId, long runtimeRevision) {
        Runnable refresh = () -> {
            applyStates.put(sourceId, new ApplyState(runtimeRevision,
                    RuntimeApplyStatus.PENDING_REFRESH, null, null, LocalDateTime.now(MYSQL_ZONE)));
            refreshAll();
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { refresh.run(); }
            });
        } else {
            refresh.run();
        }
    }

    public ApplyState state(String sourceId, String sourceStatus, long runtimeRevision) {
        if (!SourceStatus.ENABLED.name().equals(sourceStatus)) {
            return new ApplyState(runtimeRevision, RuntimeApplyStatus.NOT_APPLICABLE,
                    null, null, null);
        }
        return applyStates.getOrDefault(sourceId, new ApplyState(
                null, RuntimeApplyStatus.PENDING_REFRESH, null, null, null));
    }

    public RuntimeSnapshot snapshot() {
        return snapshot;
    }

    private RuntimeSnapshot loadSnapshot() {
        Map<String, BizDataSource> sources = new LinkedHashMap<>();
        sourceMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .filter(source -> SourceStatus.ENABLED.name().equals(source.getStatus()))
                .forEach(source -> sources.put(source.getSourceId(), source));
        Map<String, BizPointAlias> aliases = new LinkedHashMap<>();
        aliasMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .filter(alias -> Integer.valueOf(1).equals(alias.getStatus()))
                .filter(alias -> sources.containsKey(alias.getSourceId()))
                .forEach(alias -> aliases.put(alias.getAliasId(), alias));
        Map<String, BizCollectionPolicy> policies = new LinkedHashMap<>();
        policyMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .filter(policy -> aliases.containsKey(policy.getAliasId()))
                .forEach(policy -> policies.put(policy.getPolicyId(), policy));
        Map<String, BizCollectionPolicyVersion> activeVersions = new LinkedHashMap<>();
        versionMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .filter(version -> PolicyVersionStatus.ACTIVE.name().equals(version.getStatus()))
                .filter(version -> policies.containsKey(version.getPolicyId()))
                .forEach(version -> activeVersions.put(version.getVersionId(), version));
        Map<String, Long> revisions = new LinkedHashMap<>();
        sources.values().forEach(source -> revisions.put(source.getSourceId(), source.getRuntimeRevision()));
        return new RuntimeSnapshot(Map.copyOf(sources), Map.copyOf(aliases),
                Map.copyOf(policies), Map.copyOf(activeVersions), Map.copyOf(revisions));
    }

    public record ApplyState(Long appliedRuntimeRevision, RuntimeApplyStatus applyStatus,
                             LocalDateTime appliedAt, String lastErrorCode, LocalDateTime lastAttemptAt) {}

    public record RuntimeSnapshot(Map<String, BizDataSource> sources,
                                  Map<String, BizPointAlias> aliases,
                                  Map<String, BizCollectionPolicy> policies,
                                  Map<String, BizCollectionPolicyVersion> activeVersions,
                                  Map<String, Long> sourceRevisions) {
        static RuntimeSnapshot empty() {
            return new RuntimeSnapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
    }
}
