package com.platform.iot.dataquality;

import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.FillSourceType;
import com.platform.iot.dataquality.model.FillTaskEvidence;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import com.platform.iot.dataquality.model.entity.BizPointTypicalValueConfig;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.MinuteQualityWriteResult;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将已批准典型值即时应用为质量 2 正式分钟。
 *
 * <p>本服务位于典型值内存快照、MySQL 补全任务和 TDengine 质量优先写入口之间。
 * 同点、同配置版本、同自然小时只取得一次任务；成功分钟不逐分钟更新 MySQL 计数，
 * {@link FillTaskReconciliationService} 再按 TDengine 正式事实重建小时实际区间。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(
        prefix = "data-quality", name = "enabled", havingValue = "true")
public class TypicalValueFillService {

    public static final String ALGORITHM_VERSION = "TYPICAL_V1";
    private static final long HOUR_MILLIS = 3_600_000L;
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    private final TypicalValueConfigProvider configProvider;
    private final FillTaskRepository fillTaskRepository;
    private final FillTaskEvidenceCodec evidenceCodec;
    private final HvacMinuteRepository minuteRepository;
    private final ConcurrentHashMap<TaskCacheKey, CachedTask> taskCache =
            new ConcurrentHashMap<>();

    public TypicalValueFillService(
            TypicalValueConfigProvider configProvider,
            FillTaskRepository fillTaskRepository,
            FillTaskEvidenceCodec evidenceCodec,
            HvacMinuteRepository minuteRepository) {
        this.configProvider = Objects.requireNonNull(configProvider, "configProvider");
        this.fillTaskRepository =
                Objects.requireNonNull(fillTaskRepository, "fillTaskRepository");
        this.evidenceCodec = Objects.requireNonNull(evidenceCodec, "evidenceCodec");
        this.minuteRepository = Objects.requireNonNull(minuteRepository, "minuteRepository");
    }

    /**
     * 尝试补齐一个缺失分钟。
     *
     * <p>没有批准配置时返回空；若写入被并发到达的正式数据拒绝，则回读当前分钟，
     * 避免下游使用陈旧缺失快照。技术失败会立即写入任务失败证据，但不会阻断质量
     * 完成事件，公式仍可明确记录该输入缺失。</p>
     */
    public Optional<RawMinuteAggregate> fillMissing(
            PointRuntimeConfig point,
            long minuteStart,
            long finalizedAt) {
        Objects.requireNonNull(point, "point 不能为空");
        Optional<BizPointTypicalValueConfig> approved =
                configProvider.findApproved(point.pointId(), minuteStart);
        if (approved.isEmpty()) {
            return Optional.empty();
        }

        CachedTask cachedTask;
        try {
            cachedTask = taskFor(point, approved.orElseThrow(), minuteStart, finalizedAt);
        } catch (RuntimeException exception) {
            log.error("质量2任务创建失败，保留分钟缺失: pointId={}, minute={}",
                    point.pointId(), minuteStart, exception);
            return Optional.empty();
        }

        try {
            RawMinuteAggregate generated = generatedMinute(
                    point,
                    approved.orElseThrow().getTypicalValue().doubleValue(),
                    minuteStart,
                    finalizedAt,
                    cachedTask.task().getTaskId());
            List<MinuteQualityWriteResult> results =
                    minuteRepository.saveAllWithQualityPriority(
                            List.of(generated), null);
            if (results.size() != 1) {
                throw new IllegalStateException(
                        "质量2分钟写入结果数量与请求不一致");
            }
            MinuteQualityWriteResult.Outcome outcome =
                    results.getFirst().outcome();
            if (!accepted(outcome)) {
                // 缺失判断与写入之间可能已有 Q0/Q1 或另一合法 Q2 到达。
                // 必须回读正式行交给 READY，不能用陈旧缺失快照覆盖公式结果。
                return minuteRepository.findPointMinute(
                        point.pointId(), minuteStart);
            }
            markFirstAppliedOnce(cachedTask);
            return Optional.of(generated);
        } catch (RuntimeException exception) {
            recordFailure(cachedTask.task().getTaskId(), minuteStart, exception);
            return Optional.empty();
        }
    }

    /**
     * 以“测点 + 批准配置版本 + 自然小时”复用 MySQL 补全任务。
     *
     * <p>本地缓存仅减少热路径查询，最终幂等性仍由仓储的确定性任务键保证；缓存只保留
     * 当前和上一小时，防止常驻进程随历史分钟持续增长。</p>
     */
    private CachedTask taskFor(
            PointRuntimeConfig point,
            BizPointTypicalValueConfig config,
            long minuteStart,
            long finalizedAt) {
        int version = Objects.requireNonNull(
                config.getVersion(), "批准配置 version 不能为空");
        long hourStart = minuteStart - Math.floorMod(minuteStart, HOUR_MILLIS);
        TaskCacheKey cacheKey = new TaskCacheKey(
                point.pointId(),
                Objects.requireNonNull(config.getConfigId(), "批准配置 configId 不能为空"),
                version,
                hourStart);
        // 仅保留当前和上一自然小时，防止常驻进程按历史小时无限累积本地缓存。
        taskCache.keySet().removeIf(
                key -> key.hourStart() < hourStart - HOUR_MILLIS);
        return taskCache.computeIfAbsent(cacheKey, ignored -> {
            BizDataQualityFillTask task = fillTaskRepository.getOrCreate(
                    candidate(point, config, version, hourStart, finalizedAt));
            boolean alreadyApplied = task.getApplyStatus() == FillApplyStatus.APPLIED;
            return new CachedTask(task, new AtomicBoolean(alreadyApplied));
        });
    }

    private BizDataQualityFillTask candidate(
            PointRuntimeConfig point,
            BizPointTypicalValueConfig config,
            int version,
            long hourStart,
            long finalizedAt) {
        long validFrom = toEpoch(config.getValidFrom(), "validFrom");
        Long validTo = config.getValidTo() == null
                ? null : toEpoch(config.getValidTo(), "validTo");
        FillTaskEvidence.Typical evidence = new FillTaskEvidence.Typical(
                config.getConfigId(),
                version,
                Objects.requireNonNull(config.getTypicalValue(), "typicalValue 不能为空"),
                requireText(config.getUnit(), "unit"),
                validFrom,
                validTo,
                hourStart,
                ALGORITHM_VERSION,
                List.of());

        BizDataQualityFillTask task = new BizDataQualityFillTask();
        task.setIdempotencyKey(FillTaskIdempotency.q2(
                point.pointId(), config.getConfigId(), version, hourStart));
        task.setBuildingId(point.buildingId());
        task.setPointId(point.pointId());
        task.setStartMinute(toLocalDateTime(hourStart));
        task.setEndMinute(toLocalDateTime(hourStart + HOUR_MILLIS));
        // 热路径先建立小时审计边界，不猜测实际应用数量；跨库收口再按 TDengine 事实重建。
        task.setMinuteCount(0);
        task.setDataQuality(2);
        task.setSourceType(FillSourceType.TYPICAL_VALUE);
        task.setAlgorithmVersion(ALGORITHM_VERSION);
        task.setEvidenceJson(evidenceCodec.encode(
                FillSourceType.TYPICAL_VALUE, evidence));
        task.setTypicalConfigId(config.getConfigId());
        task.setTypicalConfigVersion(version);
        task.setApplyStatus(FillApplyStatus.WAITING);
        task.setGeneratedAt(toLocalDateTime(finalizedAt));
        return task;
    }

    private RawMinuteAggregate generatedMinute(
            PointRuntimeConfig point,
            double value,
            long minuteStart,
            long finalizedAt,
            String taskId) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("典型值必须是有限数值");
        }
        return new RawMinuteAggregate(
                point.pointId(),
                point.pointCode(),
                point.buildingId(),
                point.systemGroupId(),
                point.equipId(),
                point.equipCode(),
                point.familyCode(),
                point.componentCode(),
                point.suffixCode(),
                point.isForCalc(),
                minuteStart,
                value,
                value,
                value,
                0,
                2,
                null,
                null,
                finalizedAt,
                taskId);
    }

    private boolean accepted(MinuteQualityWriteResult.Outcome outcome) {
        return switch (outcome) {
            case INSERTED, UPGRADED, IDEMPOTENT -> true;
            case UPDATED_REAL, REJECTED_HIGHER_QUALITY,
                    REJECTED_SAME_QUALITY -> false;
        };
    }

    /**
     * 首个 Q2 分钟写入 TDengine 后，尽力把 MySQL 任务推进到已应用。
     *
     * <p>两库没有分布式事务，状态更新失败不能撤销已落库分钟；重置本地标记后，下一次
     * 同小时写入会继续补记，小时收口也会处理遗留窗口。</p>
     */
    private void markFirstAppliedOnce(CachedTask cachedTask) {
        if (!cachedTask.appliedMarked().compareAndSet(false, true)) {
            return;
        }
        try {
            fillTaskRepository.markFirstApplied(cachedTask.task().getTaskId());
        } catch (RuntimeException exception) {
            // TDengine 分钟已经成功，不能因 MySQL 状态推进失败撤销结果；下一分钟继续补记。
            cachedTask.appliedMarked().set(false);
            log.error("质量2任务首次应用状态更新失败，等待后续补记: taskId={}",
                    cachedTask.task().getTaskId(), exception);
        }
    }

    private void recordFailure(
            String taskId, long minuteStart, RuntimeException cause) {
        String error = cause.getMessage();
        if (error == null || error.isBlank()) {
            error = cause.getClass().getSimpleName();
        }
        try {
            fillTaskRepository.recordFailure(taskId, minuteStart, error);
        } catch (RuntimeException recordException) {
            log.error("质量2写入及失败证据记录均失败: taskId={}, minute={}",
                    taskId, minuteStart, recordException);
        }
        log.error("质量2写入失败，当前分钟保留缺失: taskId={}, minute={}",
                taskId, minuteStart, cause);
    }

    private long toEpoch(LocalDateTime value, String field) {
        return Objects.requireNonNull(value, field + " 不能为空")
                .atZone(PROJECT_ZONE).toInstant().toEpochMilli();
    }

    private LocalDateTime toLocalDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMillis), PROJECT_ZONE);
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private record TaskCacheKey(
            String pointId, String configId, int version, long hourStart) {
    }

    private record CachedTask(
            BizDataQualityFillTask task, AtomicBoolean appliedMarked) {
    }
}
