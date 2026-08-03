package com.platform.iot.dataquality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.DataQualityProperties;
import com.platform.hvac.model.entity.BizIndicator;
import com.platform.iot.dataquality.event.HvacLateRealEventStoredEvent;
import com.platform.iot.dataquality.event.HvacMinuteQualityReadyEvent;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.FillSourceType;
import com.platform.iot.dataquality.model.FillTaskEvidence;
import com.platform.iot.dataquality.model.QualityEventSource;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import com.platform.iot.formula.HvacFormulaEngine;
import com.platform.iot.formula.IndicatorConfigProvider;
import com.platform.iot.formula.model.IndicatorMinuteKey;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.HvacRawEventRepository;
import com.platform.iot.temporal.IndicatorMinuteRepository;
import com.platform.iot.temporal.model.LateRawMinuteEvidence;
import com.platform.iot.temporal.model.MinuteQualityWriteResult;
import com.platform.iot.temporal.model.PointMinuteKey;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 低频恢复数据质量链路中遗漏的迟到 Q0、下游修正和补全任务。
 *
 * <p>{@link DataQualityRecoveryScheduler} 和管理端重试入口调用本服务。自动轮次依次
 * 重放 TDengine 迟到原始证据、修复已落盘 Q0 的公式/插值下游，再处理 MySQL 中
 * 卡住或失败的补全任务；任务恢复始终复用原 taskId 和冻结证据，不重新选择典型值
 * 或创建新任务。MySQL 与 TDengine 间没有分布式事务，是否完成以正式分钟回读、
 * READY 发布及最终任务状态共同确认。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(
        prefix = "data-quality", name = "enabled", havingValue = "true")
public class DataQualityRecoveryService {

    private static final int BATCH_LIMIT = 100;
    private static final int INVALID_SOURCE_LIMIT = 20;
    private static final int WAITING_Q1_LIMIT = 40;
    private static final long MINUTE_MILLIS = 60_000L;
    private static final long HOUR_MILLIS = 3_600_000L;
    private static final double VALUE_TOLERANCE = 1.0E-9;
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final TypeReference<List<FailedMinute>> FAILED_MINUTES =
            new TypeReference<>() {
            };

    private final DataQualityProperties properties;
    private final FillTaskRepository fillTaskRepository;
    private final FillTaskEvidenceCodec evidenceCodec;
    private final ObjectMapper objectMapper;
    private final DataPointConfigProvider pointConfigProvider;
    private final HvacRawEventRepository rawRepository;
    private final HvacMinuteRepository minuteRepository;
    private final IndicatorConfigProvider indicatorConfigProvider;
    private final IndicatorMinuteRepository indicatorRepository;
    private final Optional<HvacFormulaEngine> formulaEngine;
    private final LateRealMinuteCorrectionService lateCorrectionService;
    private final InterpolationFillService interpolationFillService;
    private final LinearMinuteInterpolator interpolator;
    private final ApplicationEventPublisher eventPublisher;
    private final int aggregationFinalizationDelaySeconds;
    private LateEvidenceCursor lateEvidenceCursor;
    private PointMinuteKey lateRealCursor;

    public DataQualityRecoveryService(
            DataQualityProperties properties,
            FillTaskRepository fillTaskRepository,
            FillTaskEvidenceCodec evidenceCodec,
            ObjectMapper objectMapper,
            DataPointConfigProvider pointConfigProvider,
            HvacRawEventRepository rawRepository,
            HvacMinuteRepository minuteRepository,
            IndicatorConfigProvider indicatorConfigProvider,
            IndicatorMinuteRepository indicatorRepository,
            Optional<HvacFormulaEngine> formulaEngine,
            LateRealMinuteCorrectionService lateCorrectionService,
            InterpolationFillService interpolationFillService,
            LinearMinuteInterpolator interpolator,
            ApplicationEventPublisher eventPublisher,
            @Value("${aggregation.finalization-delay-seconds:30}")
            int aggregationFinalizationDelaySeconds) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.fillTaskRepository =
                Objects.requireNonNull(fillTaskRepository, "fillTaskRepository");
        this.evidenceCodec = Objects.requireNonNull(evidenceCodec, "evidenceCodec");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.pointConfigProvider =
                Objects.requireNonNull(pointConfigProvider, "pointConfigProvider");
        this.rawRepository = Objects.requireNonNull(rawRepository, "rawRepository");
        this.minuteRepository =
                Objects.requireNonNull(minuteRepository, "minuteRepository");
        this.indicatorConfigProvider = Objects.requireNonNull(
                indicatorConfigProvider, "indicatorConfigProvider");
        this.indicatorRepository =
                Objects.requireNonNull(indicatorRepository, "indicatorRepository");
        this.formulaEngine = Objects.requireNonNull(formulaEngine, "formulaEngine");
        this.lateCorrectionService = Objects.requireNonNull(
                lateCorrectionService, "lateCorrectionService");
        this.interpolationFillService = Objects.requireNonNull(
                interpolationFillService, "interpolationFillService");
        this.interpolator = Objects.requireNonNull(interpolator, "interpolator");
        this.eventPublisher =
                Objects.requireNonNull(eventPublisher, "eventPublisher");
        if (aggregationFinalizationDelaySeconds < 0) {
            throw new IllegalArgumentException(
                    "aggregationFinalizationDelaySeconds 不能为负数");
        }
        this.aggregationFinalizationDelaySeconds =
                aggregationFinalizationDelaySeconds;
    }

    /**
     * 执行一轮最多 100 项的恢复，并按数据产生顺序推进各类补偿。
     *
     * <p>先重放迟到原始证据，再补发已存在 Q0 的下游，最后处理非法来源、陈旧
     * WAITING Q1 和 FAILED 任务。各段分别捕获故障，避免一次 TDengine 查询或一条
     * 损坏 MySQL 证据让其他可恢复项目饥饿。</p>
     */
    public synchronized void recover(long now) {
        long windowMillis = Math.multiplyExact(
                properties.getLateRealCorrectionHours(), HOUR_MILLIS);
        long fromInclusive = now - windowMillis;
        List<LateRawMinuteEvidence> lateEvidence;
        try {
            lateEvidence = findNextLateEvidence(fromInclusive, now);
        } catch (RuntimeException exception) {
            log.error("迟到原始证据批量扫描失败，本轮仅跳过原始事件重放，正式 Q0 补偿仍独立执行",
                    exception);
            lateEvidence = List.of();
        }

        replayLateEvidence(lateEvidence, now);
        recoverLateDownstream(findNextLateRealMinutes(
                fromInclusive, now));

        LocalDateTime retryBefore = toLocalDateTime(
                now - properties.getRetryDelayMs());
        int processed = recoverInvalidSourceTasks(retryBefore);
        processed += recoverWaitingInterpolationTasks(
                retryBefore,
                Math.min(WAITING_Q1_LIMIT, BATCH_LIMIT - processed),
                now);
        int retryLimit = BATCH_LIMIT - processed;
        if (retryLimit <= 0) {
            return;
        }
        List<BizDataQualityFillTask> retryable;
        try {
            retryable = fillTaskRepository.findRetryable(
                    retryBefore, retryLimit);
        } catch (RuntimeException exception) {
            log.error("补全失败任务批量读取失败，本轮结束", exception);
            return;
        }
        if (retryable == null) {
            return;
        }
        for (BizDataQualityFillTask task : retryable) {
            recoverTaskSafely(task, now);
        }
    }

    /**
     * 隔离 sourceType 已损坏的 MySQL 任务，只增加重试证据而不访问 TDengine。
     * 来源不明时无法判断应生成 Q1 还是 Q2，自动猜测会污染正式分钟。
     */
    private int recoverInvalidSourceTasks(
            LocalDateTime retryBefore) {
        List<String> taskIds;
        try {
            taskIds = fillTaskRepository.findInvalidSourceRetryableTaskIds(
                    retryBefore, INVALID_SOURCE_LIMIT);
        } catch (RuntimeException exception) {
            log.error("非法 sourceType 失败任务读取失败，继续恢复正常任务", exception);
            return 0;
        }
        if (taskIds == null) {
            return 0;
        }
        for (String taskId : taskIds) {
            try {
                fillTaskRepository.incrementRetry(taskId);
                fillTaskRepository.recordRetryError(
                        taskId,
                        "补全任务 sourceType 非法，禁止自动写入，需要人工修正任务证据");
            } catch (RuntimeException exception) {
                log.error("非法 sourceType 任务隔离标记失败: taskId={}",
                        taskId, exception);
            }
        }
        return taskIds.size();
    }

    /**
     * 恢复超过重试间隔仍停在 WAITING 的 Q1，并把单任务异常转为可重试 FAILED。
     * 返回已占用的批次配额，使后续 FAILED 扫描仍受本轮总上限约束。
     */
    private int recoverWaitingInterpolationTasks(
            LocalDateTime updatedBefore,
            int limit,
            long now) {
        if (limit <= 0) {
            return 0;
        }
        List<BizDataQualityFillTask> tasks;
        try {
            tasks = fillTaskRepository.findWaitingInterpolationTasks(
                    updatedBefore, limit);
        } catch (RuntimeException exception) {
            log.error("WAITING Q1 下游恢复任务读取失败，继续恢复 FAILED 任务", exception);
            return 0;
        }
        if (tasks == null) {
            return 0;
        }
        for (BizDataQualityFillTask task : tasks) {
            try {
                recoverWaitingInterpolationTask(task, now);
            } catch (RuntimeException exception) {
                markWaitingInterpolationFailed(task, exception);
            }
        }
        return tasks.size();
    }

    /**
     * 从冻结端点重放陈旧 WAITING Q1 的完整缺口。
     *
     * <p>该状态可能停在建任务、部分 TDengine 写入或 READY 发布之后任意位置；
     * 全段重放配合质量优先写可同时覆盖首次写、幂等命中和被更高质量替代的情况。</p>
     */
    private void recoverWaitingInterpolationTask(
            BizDataQualityFillTask task, long now) {
        FillTaskEvidence evidence = evidenceCodec.decode(
                Objects.requireNonNull(
                        task.getSourceType(), "补全任务 sourceType 缺失"),
                task.getEvidenceJson());
        if (!(evidence instanceof FillTaskEvidence.Interpolation interpolation)) {
            throw new IllegalStateException("WAITING Q1 任务证据类型不匹配");
        }
        PointRuntimeConfig point = requiredPoint(task.getPointId());
        validateTaskConsistency(task, interpolation, point, now);
        // 陈旧 WAITING 可能停在“任务已建但 TD 尚未写”、部分写入或 READY
        // 发布失败等任意跨库窗口。按冻结证据重放整个缺口后，质量优先写会
        // 区分 INSERTED、UPGRADED、IDEMPOTENT，既补齐部分写入又不降级数据。
        RecoveryBatch replayed = recoverInterpolation(
                task, interpolation, point, now);
        applyRecoveryOutcome(task, replayed, now);
    }

    private void markWaitingInterpolationFailed(
            BizDataQualityFillTask task, RuntimeException exception) {
        String taskId = task == null ? null : task.getTaskId();
        String error = errorMessage(exception);
        if (taskId != null && !taskId.isBlank()) {
            try {
                long firstMinute = toEpoch(
                        task.getStartMinute(), "startMinute");
                fillTaskRepository.recordFailure(
                        taskId, firstMinute, error);
            } catch (RuntimeException recordException) {
                log.error("WAITING Q1 失败状态记录失败: taskId={}",
                        taskId, recordException);
            }
        }
        log.error("WAITING Q1 恢复失败，已转为 FAILED 等待重试或人工重算: taskId={}",
                taskId, exception);
    }

    /**
     * 使用 point+minute seek 游标遍历窗口；扫描到末尾后回绕，既限制每轮
     * 100 个分组，也不会让固定首页候选永久饿死后续分钟。
     */
    private List<LateRawMinuteEvidence> findNextLateEvidence(
            long fromInclusive, long toExclusive) {
        Long afterMinute = lateEvidenceCursor == null
                ? null : lateEvidenceCursor.minuteStart();
        String afterPoint = lateEvidenceCursor == null
                ? null : lateEvidenceCursor.pointId();
        List<LateRawMinuteEvidence> evidence =
                rawRepository.findLateMinuteEvidence(
                        fromInclusive,
                        toExclusive,
                        afterMinute,
                        afterPoint,
                        BATCH_LIMIT);
        if (evidence.isEmpty() && lateEvidenceCursor != null) {
            lateEvidenceCursor = null;
            evidence = rawRepository.findLateMinuteEvidence(
                    fromInclusive, toExclusive, null, null, BATCH_LIMIT);
        }
        if (!evidence.isEmpty()) {
            LateRawMinuteEvidence last = evidence.getLast();
            lateEvidenceCursor = new LateEvidenceCursor(
                    last.minuteStart(), last.pointId());
        }
        return evidence;
    }

    /**
     * 分页扫描窗口内带迟到特征的正式 Q0；游标到达末尾后回绕，避免固定首页
     * 长期遮蔽后续测点。查询失败只跳过本轮下游补偿，不影响补全任务重试。
     */
    private List<RawMinuteAggregate> findNextLateRealMinutes(
            long fromInclusive, long toExclusive) {
        try {
            Long afterMinute = lateRealCursor == null
                    ? null : lateRealCursor.minuteStart();
            String afterPoint = lateRealCursor == null
                    ? null : lateRealCursor.pointId();
            List<RawMinuteAggregate> rows =
                    minuteRepository.findLateRealMinutes(
                            fromInclusive,
                            toExclusive,
                            afterMinute,
                            afterPoint,
                            aggregationFinalizationDelaySeconds,
                            BATCH_LIMIT);
            if (rows.isEmpty() && lateRealCursor != null) {
                lateRealCursor = null;
                rows = minuteRepository.findLateRealMinutes(
                        fromInclusive,
                        toExclusive,
                        null,
                        null,
                        aggregationFinalizationDelaySeconds,
                        BATCH_LIMIT);
            }
            if (!rows.isEmpty()) {
                RawMinuteAggregate last = rows.getLast();
                lateRealCursor = new PointMinuteKey(
                        last.pointId(), last.minuteStart());
            }
            return rows;
        } catch (RuntimeException exception) {
            log.error("迟到 Q0 正式分钟批量扫描失败，本轮稍后重试", exception);
            return List.of();
        }
    }

    /**
     * 管理 API 复用同一任务恢复入口，不创建新的审批或补全任务。
     */
    public synchronized void recoverTask(String taskId, long now) {
        BizDataQualityFillTask task = fillTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "补全任务不存在: " + taskId));
        if (task.getApplyStatus() != FillApplyStatus.FAILED) {
            throw new IllegalStateException("只有 FAILED 补全任务允许重试");
        }
        fillTaskRepository.incrementRetry(taskId);
        try {
            recoverTaskInternal(task, now);
        } catch (RuntimeException exception) {
            fillTaskRepository.recordRetryError(
                    taskId, errorMessage(exception));
            throw exception;
        }
    }

    /**
     * 将 TDengine 中的迟到原始证据逐条交给实时修正服务重放。
     * 每条证据独立失败，避免一个已删除测点阻断同批其他 Q0 重聚合。
     */
    private void replayLateEvidence(
            List<LateRawMinuteEvidence> lateEvidence,
            long now) {
        for (LateRawMinuteEvidence evidence : lateEvidence) {
            try {
                lateCorrectionService.recoverStoredEvent(
                        new HvacLateRealEventStoredEvent(
                                evidence.pointId(),
                                evidence.buildingId(),
                                evidence.minuteStart(),
                                now));
            } catch (RuntimeException exception) {
                log.error("迟到原始证据恢复失败，继续处理后续分钟: pointId={}, minute={}",
                        evidence.pointId(), evidence.minuteStart(), exception);
            }
        }
    }

    /**
     * 为确认具有迟到原始证据的 Q0 补齐公式 READY 与历史 Q1 回溯。
     *
     * <p>公式事件只在指标最近尝试时间早于 Q0 finalizedAt 时补发，避免每轮重复
     * 计算；插值判断始终幂等重跑，因为水位追平并不能证明右端点之前没有短缺口。</p>
     */
    private void recoverLateDownstream(
            List<RawMinuteAggregate> formalCandidates) {
        if (formalCandidates.isEmpty()) {
            return;
        }
        Set<PointMinuteKey> candidateKeys = formalCandidates.stream()
                .map(row -> new PointMinuteKey(
                        row.pointId(), row.minuteStart()))
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
        Set<PointMinuteKey> confirmedLate;
        try {
            confirmedLate = rawRepository.findLateEvidenceKeys(candidateKeys);
        } catch (RuntimeException exception) {
            log.error("迟到 Q0 原始证据核验失败，本轮不执行下游修正", exception);
            return;
        }
        List<RawMinuteAggregate> lateMinutes = formalCandidates.stream()
                .filter(row -> confirmedLate.contains(new PointMinuteKey(
                        row.pointId(), row.minuteStart())))
                .toList();

        Map<PointMinuteKey, Set<String>> dependencies;
        try {
            dependencies = resolveDependencies(lateMinutes);
        } catch (RuntimeException exception) {
            // 依赖配置不可用时只暂停公式补发，不能阻断同批插值和 FAILED 任务恢复。
            log.error("迟到 Q0 公式依赖解析失败，本轮不补发公式 READY", exception);
            dependencies = Map.of();
        }
        Set<IndicatorMinuteKey> indicatorKeys = new LinkedHashSet<>();
        dependencies.forEach((key, indicatorIds) -> indicatorIds.forEach(
                indicatorId -> indicatorKeys.add(
                        new IndicatorMinuteKey(indicatorId, key.minuteStart()))));
        Map<IndicatorMinuteKey, Long> calculatedAt;
        try {
            calculatedAt = indicatorKeys.isEmpty()
                    ? Map.of()
                    : indicatorRepository.findLatestAttemptAt(indicatorKeys);
        } catch (RuntimeException exception) {
            // 无法确认水位时不盲目重算公式；插值仍可独立幂等恢复。
            log.error("迟到 Q0 指标水位读取失败，本轮不补发公式 READY", exception);
            calculatedAt = null;
        }

        for (RawMinuteAggregate minute : lateMinutes) {
            PointMinuteKey key = new PointMinuteKey(
                    minute.pointId(), minute.minuteStart());
            Set<String> affectedIndicators =
                    dependencies.getOrDefault(key, Set.of());
            if (calculatedAt != null
                    && isDownstreamStale(
                    minute, affectedIndicators, calculatedAt)) {
                try {
                    eventPublisher.publishEvent(new HvacMinuteQualityReadyEvent(
                            minute.minuteStart(),
                            minute.finalizedAt(),
                            QualityEventSource.LATE_REAL_CORRECTION,
                            Set.of(minute.buildingId()),
                            List.of(minute),
                            Set.of(minute.pointId())));
                } catch (RuntimeException exception) {
                    log.error("迟到 Q0 公式 READY 补发失败，继续处理插值: pointId={}, minute={}",
                            minute.pointId(), minute.minuteStart(), exception);
                }
            }
            try {
                // 即使公式水位已追平也重跑短缺口判断；没有缺口时服务会幂等退出。
                interpolationFillService.fillFromRightEndpoints(
                        List.of(minute), minute.finalizedAt());
            } catch (RuntimeException exception) {
                log.error("迟到 Q0 历史插值恢复失败: pointId={}, minute={}",
                        minute.pointId(), minute.minuteStart(), exception);
            }
        }
    }

    private Map<PointMinuteKey, Set<String>> resolveDependencies(
            List<RawMinuteAggregate> lateMinutes) {
        if (lateMinutes.isEmpty() || formulaEngine.isEmpty()) {
            return Map.of();
        }
        Collection<BizIndicator> active =
                indicatorConfigProvider.findAllActive();
        Map<PointMinuteKey, Set<String>> result = new LinkedHashMap<>();
        for (RawMinuteAggregate minute : lateMinutes) {
            PointMinuteKey key = new PointMinuteKey(
                    minute.pointId(), minute.minuteStart());
            result.put(key, formulaEngine.orElseThrow()
                    .resolveAffectedIndicatorIds(
                            active, Set.of(minute.pointId())));
        }
        return Map.copyOf(result);
    }

    private boolean isDownstreamStale(
            RawMinuteAggregate minute,
            Set<String> indicatorIds,
            Map<IndicatorMinuteKey, Long> calculatedAt) {
        if (indicatorIds.isEmpty()) {
            return false;
        }
        return indicatorIds.stream().anyMatch(indicatorId -> {
            Long current = calculatedAt.get(new IndicatorMinuteKey(
                    indicatorId, minute.minuteStart()));
            return current == null || current < minute.finalizedAt();
        });
    }

    /**
     * 为定时批次隔离单个 FAILED 任务：先原子增加重试次数，再恢复；失败时只更新
     * 该任务的公开错误状态，下一任务继续执行。
     */
    private void recoverTaskSafely(
            BizDataQualityFillTask task, long now) {
        String taskId = task == null ? null : task.getTaskId();
        try {
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("补全任务 taskId 缺失");
            }
            fillTaskRepository.incrementRetry(taskId);
            recoverTaskInternal(task, now);
        } catch (RuntimeException exception) {
            String error = errorMessage(exception);
            if (taskId != null && !taskId.isBlank()) {
                try {
                    fillTaskRepository.recordRetryError(taskId, error);
                } catch (RuntimeException recordException) {
                    log.error("补全任务重试错误状态记录失败: taskId={}",
                            taskId, recordException);
                }
            }
            log.error("补全任务恢复失败，保留 FAILED 等待后续重试或人工重算: taskId={}",
                    taskId, exception);
        }
    }

    /**
     * 按任务冻结证据恢复 Q1 或 Q2，而不是重新读取当前业务配置来改写历史选择。
     * 写入前同时校验当前测点归属、任务字段和证据；Q1 还要求两个端点仍为原 Q0。
     */
    private void recoverTaskInternal(
            BizDataQualityFillTask task, long now) {
        FillSourceType sourceType = Objects.requireNonNull(
                task.getSourceType(), "补全任务 sourceType 缺失");
        FillTaskEvidence evidence = evidenceCodec.decode(
                sourceType, task.getEvidenceJson());
        PointRuntimeConfig point = requiredPoint(task.getPointId());
        validateTaskConsistency(task, evidence, point, now);
        RecoveryBatch batch = switch (evidence) {
            case FillTaskEvidence.Typical typical ->
                    recoverTypical(task, typical, point, now);
            case FillTaskEvidence.Interpolation interpolation ->
                    recoverInterpolation(task, interpolation, point, now);
        };
        applyRecoveryOutcome(task, batch, now);
    }

    /** 仅按失败分钟清单和冻结典型值生成 Q2，不扩展到小时内其他缺失分钟。 */
    private RecoveryBatch recoverTypical(
            BizDataQualityFillTask task,
            FillTaskEvidence.Typical evidence,
            PointRuntimeConfig point,
            long now) {
        List<Long> minutes = decodeFailedMinutes(task, evidence, now);
        List<RawMinuteAggregate> generated = minutes.stream()
                .map(minute -> generatedMinute(
                        point,
                        minute,
                        evidence.value().doubleValue(),
                        2,
                        now,
                        task.getTaskId()))
                .toList();
        return writeBatch(generated, null);
    }

    /** 重新核验两个 Q0 端点后重算完整 Q1 缺口，端点变化时转交人工重算。 */
    private RecoveryBatch recoverInterpolation(
            BizDataQualityFillTask task,
            FillTaskEvidence.Interpolation evidence,
            PointRuntimeConfig point,
            long now) {
        validateInterpolationEndpoints(evidence, point);
        int maxGap = properties.getInterpolation().getMaxGapMinutes();
        List<LinearMinuteInterpolator.InterpolatedMinute> interpolated =
                interpolator.interpolate(
                        evidence.leftMinute(),
                        evidence.leftValue(),
                        evidence.rightMinute(),
                        evidence.rightValue(),
                        maxGap);
        if (interpolated.isEmpty()
                || interpolated.stream().anyMatch(
                value -> !withinRange(point, value.value()))) {
            throw new IllegalStateException(
                    "质量1证据不再满足插值或量程规则，需要人工重算");
        }
        List<RawMinuteAggregate> generated = interpolated.stream()
                .map(value -> generatedMinute(
                        point,
                        value.minuteStart(),
                        value.value(),
                        1,
                        now,
                        task.getTaskId()))
                .toList();
        return writeBatch(generated, null);
    }

    /**
     * 通过 TDengine 质量优先入口批量写入，并验证结果与请求数量、顺序完全一致。
     * 结果错位时不能继续计算替换计数或发布 READY。
     */
    private RecoveryBatch writeBatch(
            List<RawMinuteAggregate> generated,
            String supersedesTaskId) {
        if (generated.isEmpty()) {
            throw new IllegalStateException("补全任务没有可恢复分钟");
        }
        List<MinuteQualityWriteResult> results =
                minuteRepository.saveAllWithQualityPriority(
                        generated, supersedesTaskId);
        if (results == null || results.size() != generated.size()) {
            throw new IllegalStateException("补全恢复写入结果数量与请求不一致");
        }
        for (int index = 0; index < results.size(); index++) {
            MinuteQualityWriteResult result =
                    Objects.requireNonNull(results.get(index), "writeResult");
            RawMinuteAggregate row = generated.get(index);
            if (!row.pointId().equals(result.pointId())
                    || row.minuteStart() != result.minuteStart()) {
                throw new IllegalStateException("补全恢复写入结果顺序与请求不一致");
            }
        }
        return new RecoveryBatch(generated, List.copyOf(results));
    }

    /**
     * 将 TDengine 批量写结果转换为旧任务替换计数、下游 READY 和任务终态。
     *
     * <p>只有 READY 已成功发布或指标水位证明无需发布后，才把 MySQL 的 FAILED
     * 清理为 APPLIED/REPLACED；否则保留失败态，让下轮用原 finalizedAt 幂等补发。</p>
     */
    private void applyRecoveryOutcome(
            BizDataQualityFillTask task,
            RecoveryBatch batch,
            long now) {
        Map<String, Integer> replacementCounts = new LinkedHashMap<>();
        int accepted = 0;
        int ownReplacementIncrement = 0;
        List<RawMinuteAggregate> actualWrites = new ArrayList<>();
        for (int index = 0; index < batch.results().size(); index++) {
            MinuteQualityWriteResult result = batch.results().get(index);
            if (isAccepted(result.outcome())) {
                accepted++;
            }
            if (isActualWrite(result.outcome())) {
                actualWrites.add(batch.generated().get(index));
                if (result.previousTaskId() != null
                        && !result.previousTaskId().isBlank()
                        && !result.previousTaskId().equals(task.getTaskId())) {
                    replacementCounts.merge(
                            result.previousTaskId(), 1, Integer::sum);
                }
            } else if (!isAccepted(result.outcome())) {
                ownReplacementIncrement++;
            }
        }
        if (!replacementCounts.isEmpty()) {
            fillTaskRepository.recordReplacements(replacementCounts);
        }
        FillApplyStatus status = accepted == 0
                ? FillApplyStatus.REPLACED : FillApplyStatus.APPLIED;
        publishRecoveredDownstream(task, batch, actualWrites);
        // READY 成功或已由最新 attempt 证明无需再发后，才能清理 FAILED。
        // 若发布中断，下一轮 IDEMPOTENT 会按 persisted finalizedAt 精确补发。
        fillTaskRepository.markRetryRecovered(
                task.getTaskId(), status, ownReplacementIncrement);
    }

    /**
     * 回读本 taskId 实际持有的分钟，并仅为仍过期的公式结果发布修正事件。
     *
     * <p>Q1 只计算依赖该测点的指标；Q2 需覆盖建筑全部活动指标，因为缺失输入恢复
     * 可能改变任一公式的完整性状态。写入已接受但正式行不可见会直接失败，防止
     * MySQL 先标记恢复完成。</p>
     */
    private void publishRecoveredDownstream(
            BizDataQualityFillTask task,
            RecoveryBatch batch,
            List<RawMinuteAggregate> actualWrites) {
        Set<MinuteKey> actualKeys = actualWrites.stream()
                .map(row -> new MinuteKey(row.pointId(), row.minuteStart()))
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
        List<RawMinuteAggregate> persisted = minuteRepository.findRange(
                Set.of(task.getPointId()),
                toEpoch(task.getStartMinute(), "startMinute"),
                toEpoch(task.getEndMinute(), "endMinute"));
        Map<MinuteKey, RawMinuteAggregate> persistedByKey =
                new LinkedHashMap<>();
        persisted.stream()
                .filter(row -> task.getTaskId().equals(row.qualityTaskId()))
                .forEach(row -> persistedByKey.put(
                        new MinuteKey(row.pointId(), row.minuteStart()), row));

        List<RawMinuteAggregate> acceptedRows = new ArrayList<>();
        for (int index = 0; index < batch.results().size(); index++) {
            if (!isAccepted(batch.results().get(index).outcome())) {
                continue;
            }
            RawMinuteAggregate requested = batch.generated().get(index);
            RawMinuteAggregate current = persistedByKey.get(new MinuteKey(
                    requested.pointId(), requested.minuteStart()));
            if (current == null) {
                throw new IllegalStateException(
                        "补全写入已接受但正式分钟回读缺失");
            }
            acceptedRows.add(current);
        }
        if (acceptedRows.isEmpty()) {
            return;
        }

        Collection<BizIndicator> active =
                indicatorConfigProvider.findAllActive();
        Map<MinuteKey, Set<String>> expectedByMinute = new LinkedHashMap<>();
        Set<IndicatorMinuteKey> attemptKeys = new LinkedHashSet<>();
        for (RawMinuteAggregate row : acceptedRows) {
            Set<String> expected;
            if (task.getSourceType() == FillSourceType.INTERPOLATION
                    && formulaEngine.isPresent()) {
                expected = formulaEngine.orElseThrow()
                        .resolveAffectedIndicatorIds(
                                active, Set.of(row.pointId()));
            } else {
                expected = active.stream()
                        .filter(indicator -> Objects.equals(
                                indicator.getBuildingId(), row.buildingId()))
                        .map(BizIndicator::getIndicatorId)
                        .filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toCollection(
                                LinkedHashSet::new));
            }
            MinuteKey minuteKey = new MinuteKey(
                    row.pointId(), row.minuteStart());
            expectedByMinute.put(minuteKey, Set.copyOf(expected));
            expected.forEach(indicatorId -> attemptKeys.add(
                    new IndicatorMinuteKey(indicatorId, row.minuteStart())));
        }
        Map<IndicatorMinuteKey, Long> attempts =
                indicatorRepository.findLatestAttemptAt(attemptKeys);

        for (RawMinuteAggregate row : acceptedRows) {
            MinuteKey key = new MinuteKey(
                    row.pointId(), row.minuteStart());
            Set<String> expected =
                    expectedByMinute.getOrDefault(key, Set.of());
            boolean stale = expected.stream().anyMatch(indicatorId -> {
                Long attempt = attempts.get(new IndicatorMinuteKey(
                        indicatorId, row.minuteStart()));
                return attempt == null || attempt < row.finalizedAt();
            });
            if (!actualKeys.contains(key) && !stale) {
                continue;
            }
            eventPublisher.publishEvent(new HvacMinuteQualityReadyEvent(
                    row.minuteStart(),
                    row.finalizedAt(),
                    row.dataQuality() == 1
                            ? QualityEventSource.INTERPOLATION_CORRECTION
                            : QualityEventSource.TYPICAL_FILL,
                    Set.of(row.buildingId()),
                    List.of(row),
                    Set.of(row.pointId())));
        }
    }

    /**
     * 从 MySQL 失败证据提取可重试分钟，并限制在任务、配置有效期和已完成时间内。
     * 任一越界或无错误说明的记录都会阻止自动历史写入。
     */
    private List<Long> decodeFailedMinutes(
            BizDataQualityFillTask task,
            FillTaskEvidence.Typical evidence,
            long now) {
        String json = task.getFailedMinutesJson();
        if (json == null || json.isBlank()) {
            throw new IllegalStateException(
                    "FAILED 典型值任务缺少失败分钟证据");
        }
        try {
            List<FailedMinute> failures =
                    objectMapper.readValue(json, FAILED_MINUTES);
            if (failures == null || failures.isEmpty()) {
                throw new IllegalStateException(
                        "FAILED 典型值任务没有可重试分钟");
            }
            long from = toEpoch(task.getStartMinute(), "startMinute");
            long to = toEpoch(task.getEndMinute(), "endMinute");
            long currentMinute = now - Math.floorMod(now, MINUTE_MILLIS);
            return failures.stream()
                    .peek(failure -> {
                        if (failure == null
                                || failure.error() == null
                                || failure.error().isBlank()) {
                            throw new IllegalStateException(
                                    "失败分钟证据缺少 error");
                        }
                    })
                    .map(FailedMinute::minuteStart)
                    .distinct()
                    .sorted()
                    .peek(minute -> {
                        if (minute < from || minute >= to
                                || minute < evidence.hourStart()
                                || minute >= evidence.hourStart() + HOUR_MILLIS
                                || minute < evidence.validFrom()
                                || (evidence.validTo() != null
                                && minute >= evidence.validTo())
                                || minute >= currentMinute
                                || Math.floorMod(minute, MINUTE_MILLIS) != 0L) {
                            throw new IllegalStateException(
                                    "失败分钟超出任务、配置有效期或已完成时间范围");
                        }
                    })
                    .toList();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "补全任务 failedMinutesJson 格式无效", exception);
        }
    }

    /**
     * 阻止过期或被篡改的 MySQL 任务向 TDengine 写入。
     *
     * <p>Q2 必须仍对应同一配置版本和自然小时；Q1 的任务区间必须与冻结端点严格
     * 对齐且不超过当前最大缺口。测点已迁移建筑或任务已越过当前完成分钟时均要求
     * 人工重算，不能自动猜测新的历史语义。</p>
     */
    private void validateTaskConsistency(
            BizDataQualityFillTask task,
            FillTaskEvidence evidence,
            PointRuntimeConfig point,
            long now) {
        Objects.requireNonNull(task, "task 不能为空");
        Objects.requireNonNull(evidence, "evidence 不能为空");
        Objects.requireNonNull(point, "point 不能为空");
        if (!Objects.equals(task.getPointId(), point.pointId())
                || !Objects.equals(task.getBuildingId(), point.buildingId())) {
            throw new IllegalStateException(
                    "补全任务测点或建筑与当前档案不一致");
        }
        long start = toEpoch(task.getStartMinute(), "startMinute");
        long end = toEpoch(task.getEndMinute(), "endMinute");
        if (Math.floorMod(start, MINUTE_MILLIS) != 0L
                || Math.floorMod(end, MINUTE_MILLIS) != 0L
                || end <= start) {
            throw new IllegalStateException("补全任务时间范围无效");
        }
        if (evidence instanceof FillTaskEvidence.Typical typical) {
            if (task.getSourceType() != FillSourceType.TYPICAL_VALUE
                    || !Integer.valueOf(2).equals(task.getDataQuality())
                    || !TypicalValueFillService.ALGORITHM_VERSION.equals(
                    task.getAlgorithmVersion())
                    || !task.getAlgorithmVersion().equals(
                    typical.algorithmVersion())
                    || !Objects.equals(
                    task.getTypicalConfigId(), typical.configId())
                    || !Objects.equals(
                    task.getTypicalConfigVersion(), typical.version())
                    || start != typical.hourStart()
                    || end != typical.hourStart() + HOUR_MILLIS
                    || Math.floorMod(
                    typical.hourStart(), HOUR_MILLIS) != 0L) {
                throw new IllegalStateException(
                        "质量2任务字段与冻结证据不一致");
            }
        } else if (evidence instanceof FillTaskEvidence.Interpolation interpolation) {
            long gapMinutes = (interpolation.rightMinute()
                    - interpolation.leftMinute()) / MINUTE_MILLIS - 1L;
            if (task.getSourceType() != FillSourceType.INTERPOLATION
                    || !Integer.valueOf(1).equals(task.getDataQuality())
                    || !InterpolationFillService.ALGORITHM_VERSION.equals(
                    task.getAlgorithmVersion())
                    || !task.getAlgorithmVersion().equals(
                    interpolation.algorithmVersion())
                    || start != interpolation.leftMinute() + MINUTE_MILLIS
                    || end != interpolation.rightMinute()
                    || gapMinutes < 1L
                    || gapMinutes > properties.getInterpolation()
                    .getMaxGapMinutes()
                    || interpolation.rightMinute()
                    >= now - Math.floorMod(now, MINUTE_MILLIS)) {
                throw new IllegalStateException(
                        "质量1任务字段与冻结证据不一致");
            }
        }
    }

    /**
     * 从 TDengine 回读冻结的左右端点，要求它们仍是数值一致且不带 taskId 的 Q0。
     * 端点已被修正时，沿用旧插值会产生不可审计的历史值，因此拒绝自动恢复。
     */
    private void validateInterpolationEndpoints(
            FillTaskEvidence.Interpolation evidence,
            PointRuntimeConfig point) {
        List<RawMinuteAggregate> evidenceRows = minuteRepository.findRange(
                Set.of(point.pointId()),
                evidence.leftMinute(),
                evidence.rightMinute() + MINUTE_MILLIS);
        Map<Long, RawMinuteAggregate> byMinute = new LinkedHashMap<>();
        evidenceRows.forEach(row -> byMinute.put(row.minuteStart(), row));
        RawMinuteAggregate left = byMinute.get(evidence.leftMinute());
        RawMinuteAggregate right = byMinute.get(evidence.rightMinute());
        if (!sameEndpoint(left, evidence.leftValue())
                || !sameEndpoint(right, evidence.rightValue())) {
            throw new IllegalStateException(
                    "质量1真实端点证据已失效，需要人工重算");
        }
    }

    private PointRuntimeConfig requiredPoint(String pointId) {
        return pointConfigProvider.findByPointId(pointId)
                .orElseThrow(() -> new IllegalStateException(
                        "补全任务测点已不存在: " + pointId));
    }

    private RawMinuteAggregate generatedMinute(
            PointRuntimeConfig point,
            long minuteStart,
            double value,
            int quality,
            long finalizedAt,
            String taskId) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("补全值必须为有限数值");
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
                quality,
                null,
                null,
                finalizedAt,
                taskId);
    }

    private boolean sameEndpoint(
            RawMinuteAggregate row, double evidenceValue) {
        return row != null
                && row.dataQuality() == 0
                && row.qualityTaskId() == null
                && Double.isFinite(row.averageValue())
                && Math.abs(row.averageValue() - evidenceValue)
                <= VALUE_TOLERANCE;
    }

    private boolean withinRange(
            PointRuntimeConfig point, double value) {
        if (!Double.isFinite(value)) {
            return false;
        }
        BigDecimal decimal = BigDecimal.valueOf(value);
        return (point.valueMin() == null
                || decimal.compareTo(point.valueMin()) >= 0)
                && (point.valueMax() == null
                || decimal.compareTo(point.valueMax()) <= 0);
    }

    private boolean isAccepted(MinuteQualityWriteResult.Outcome outcome) {
        return isActualWrite(outcome)
                || outcome == MinuteQualityWriteResult.Outcome.IDEMPOTENT;
    }

    private boolean isActualWrite(MinuteQualityWriteResult.Outcome outcome) {
        return outcome == MinuteQualityWriteResult.Outcome.INSERTED
                || outcome == MinuteQualityWriteResult.Outcome.UPGRADED;
    }

    private String errorMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private long toEpoch(LocalDateTime value, String field) {
        return Objects.requireNonNull(value, field + " 不能为空")
                .atZone(PROJECT_ZONE).toInstant().toEpochMilli();
    }

    private LocalDateTime toLocalDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMillis), PROJECT_ZONE);
    }

    private record MinuteKey(String pointId, long minuteStart) {
    }

    private record LateEvidenceCursor(long minuteStart, String pointId) {
    }

    private record RecoveryBatch(
            List<RawMinuteAggregate> generated,
            List<MinuteQualityWriteResult> results) {
    }

    private record FailedMinute(long minuteStart, String error) {
    }
}
