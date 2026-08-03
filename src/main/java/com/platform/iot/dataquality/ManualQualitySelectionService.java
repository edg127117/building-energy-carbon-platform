package com.platform.iot.dataquality;

import com.platform.config.DataQualityProperties;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.FillSourceType;
import com.platform.iot.dataquality.model.FillTaskEvidence;
import com.platform.iot.dataquality.model.RecalculationChunkStats;
import com.platform.iot.dataquality.model.TypicalValueStatus;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import com.platform.iot.dataquality.model.entity.BizDataQualityRecalcJob;
import com.platform.iot.dataquality.model.entity.BizPointTypicalValueConfig;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.MinuteQualityWriteResult;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 为人工重算的一个目标分块选择并写入质量 1、质量 2 分钟。
 *
 * <p>本服务位于重算分块编排与 MySQL 补全任务、TDengine 质量优先写入口之间。
 * 它只基于一次上下文正式分钟快照选择数据：Q1 必须由当前 Q0 端点产生，Q2
 * 只补仍然缺失的分钟。子任务边界按完整短缺口或配置自然小时计算，因此跨
 * 60 分钟分块重试仍复用同一份真实来源证据，不会因 chunk 边界产生重复任务。</p>
 *
 * <p>本服务不聚合 Q0、不发布公式事件，也不推进重算游标；这些职责由重算
 * 编排层承担。任何子任务写入或审计收口失败都会抛出，调用方不得推进当前
 * chunk。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "data-quality",
        name = {"enabled", "recalculation-enabled"},
        havingValue = "true")
public class ManualQualitySelectionService {

    private static final long MINUTE_MILLIS = 60_000L;
    private static final long HOUR_MILLIS = 3_600_000L;
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Comparator<RawMinuteAggregate> ROW_ORDER =
            Comparator.comparingLong(RawMinuteAggregate::minuteStart)
                    .thenComparing(RawMinuteAggregate::pointId);

    private final DataQualityProperties properties;
    private final HvacMinuteRepository minuteRepository;
    private final FillTaskRepository fillTaskRepository;
    private final TypicalValueConfigProvider typicalValueConfigProvider;
    private final FillTaskEvidenceCodec evidenceCodec;
    private final LinearMinuteInterpolator interpolator;

    public ManualQualitySelectionService(
            DataQualityProperties properties,
            HvacMinuteRepository minuteRepository,
            FillTaskRepository fillTaskRepository,
            TypicalValueConfigProvider typicalValueConfigProvider,
            FillTaskEvidenceCodec evidenceCodec,
            LinearMinuteInterpolator interpolator) {
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.minuteRepository =
                Objects.requireNonNull(minuteRepository, "minuteRepository 不能为空");
        this.fillTaskRepository =
                Objects.requireNonNull(fillTaskRepository, "fillTaskRepository 不能为空");
        this.typicalValueConfigProvider = Objects.requireNonNull(
                typicalValueConfigProvider, "typicalValueConfigProvider 不能为空");
        this.evidenceCodec =
                Objects.requireNonNull(evidenceCodec, "evidenceCodec 不能为空");
        this.interpolator =
                Objects.requireNonNull(interpolator, "interpolator 不能为空");
    }

    /**
     * 读取一次有界上下文，先写 Q1，再为剩余缺口写 Q2。
     *
     * @param targetFrom 当前 chunk 的包含起点
     * @param targetTo 当前 chunk 的不包含终点
     * @param contextFrom 为跨 chunk Q1 端点读取的包含起点
     * @param contextTo 为跨 chunk Q1 端点读取的不包含终点
     * @return 本 chunk 尝试写入的 Q1/Q2 行及复用或创建的子任务
     */
    public ChunkSelection selectAndPersist(
            BizDataQualityRecalcJob job,
            Map<String, PointRuntimeConfig> points,
            long targetFrom,
            long targetTo,
            long contextFrom,
            long contextTo,
            long finalizedAt) {
        ValidatedContext context = validate(
                job, points, targetFrom, targetTo, contextFrom, contextTo);
        Set<String> pointIds = new LinkedHashSet<>(context.points().keySet());
        List<RawMinuteAggregate> currentRows = minuteRepository.findRange(
                pointIds, contextFrom, contextTo);
        Map<PointMinute, RawMinuteAggregate> currentByMinute =
                indexCurrentRows(currentRows, pointIds, contextFrom, contextTo);

        List<RawMinuteAggregate> q1Rows = new ArrayList<>();
        List<RawMinuteAggregate> q2Rows = new ArrayList<>();
        List<BizDataQualityFillTask> childTasks = new ArrayList<>();

        selectQ1(
                context,
                currentByMinute,
                targetFrom,
                targetTo,
                finalizedAt,
                q1Rows,
                childTasks);
        selectQ2(
                context,
                currentByMinute,
                targetFrom,
                targetTo,
                finalizedAt,
                q2Rows,
                childTasks);

        q1Rows.sort(ROW_ORDER);
        q2Rows.sort(ROW_ORDER);
        return new ChunkSelection(
                q1Rows,
                q2Rows,
                childTasks,
                classifyTarget(
                        context.points().keySet(),
                        targetFrom,
                        targetTo,
                        currentByMinute));
    }

    /**
     * 在上下文快照中按相邻 Q0 端点寻找短缺口，并只写入目标分块内的 Q1。
     *
     * <p>端点可以位于分块外，因此读取范围会多带最大缺口长度；但子任务边界保留
     * 完整 gap 与重算区间的交集，使后续分块复用同一幂等任务。Q1 只允许填空或
     * 升级 Q2，不能覆盖 Q0 或既有 Q1。</p>
     */
    private void selectQ1(
            ValidatedContext context,
            Map<PointMinute, RawMinuteAggregate> currentByMinute,
            long targetFrom,
            long targetTo,
            long finalizedAt,
            List<RawMinuteAggregate> selectedRows,
            List<BizDataQualityFillTask> childTasks) {
        int maxGapMinutes = properties.getInterpolation().getMaxGapMinutes();
        for (PointRuntimeConfig point : context.points().values()) {
            List<RawMinuteAggregate> q0Endpoints = currentByMinute.values().stream()
                    .filter(row -> point.pointId().equals(row.pointId()))
                    .filter(row -> row.dataQuality() == 0)
                    .sorted(Comparator.comparingLong(
                            RawMinuteAggregate::minuteStart))
                    .toList();
            for (int index = 1; index < q0Endpoints.size(); index++) {
                RawMinuteAggregate left = q0Endpoints.get(index - 1);
                RawMinuteAggregate right = q0Endpoints.get(index);
                List<LinearMinuteInterpolator.InterpolatedMinute> interpolation =
                        interpolator.interpolate(
                                left.minuteStart(),
                                left.averageValue(),
                                right.minuteStart(),
                                right.averageValue(),
                                maxGapMinutes);
                if (interpolation.isEmpty()
                        || interpolation.stream().anyMatch(
                                minute -> !isWithinRange(point, minute.value()))) {
                    continue;
                }

                long taskFrom = Math.max(
                        context.jobFrom(), left.minuteStart() + MINUTE_MILLIS);
                long taskTo = Math.min(context.jobTo(), right.minuteStart());
                if (taskFrom >= taskTo) {
                    continue;
                }
                List<LinearMinuteInterpolator.InterpolatedMinute> candidates =
                        interpolation.stream()
                                .filter(minute -> minute.minuteStart() >= targetFrom)
                                .filter(minute -> minute.minuteStart() < targetTo)
                                .filter(minute -> minute.minuteStart() >= taskFrom)
                                .filter(minute -> minute.minuteStart() < taskTo)
                                .filter(minute -> q1MayReplace(currentByMinute.get(
                                        new PointMinute(
                                                point.pointId(),
                                                minute.minuteStart()))))
                                .toList();
                if (candidates.isEmpty()) {
                    continue;
                }

                BizDataQualityFillTask child = requireWritableTask(
                        fillTaskRepository.getOrCreate(newQ1Task(
                                context.job(),
                                point,
                                left,
                                right,
                                taskFrom,
                                taskTo,
                                finalizedAt)));
                List<RawMinuteAggregate> rows = candidates.stream()
                        .map(candidate -> generatedMinute(
                                point,
                                candidate.minuteStart(),
                                candidate.value(),
                                1,
                                finalizedAt,
                                requireText(child.getTaskId(), "taskId")))
                        .sorted(ROW_ORDER)
                        .toList();
                List<MinuteQualityWriteResult> results =
                        persistChildBatch(context.job(), child, rows);
                occupyAfterWrite(currentByMinute, rows, results);
                selectedRows.addAll(rows);
                childTasks.add(child);
            }
        }
    }

    /**
     * 对 Q1 选择后仍缺失的目标分钟应用当时有效的批准典型值。
     *
     * <p>分钟按“测点、配置版本、自然小时”合并为子任务，任务范围再与重算区间和
     * 配置有效期取交集；这样跨分块执行仍复用同一审计记录，且不会把一个版本的值
     * 写到另一版本生效区间。</p>
     */
    private void selectQ2(
            ValidatedContext context,
            Map<PointMinute, RawMinuteAggregate> currentByMinute,
            long targetFrom,
            long targetTo,
            long finalizedAt,
            List<RawMinuteAggregate> selectedRows,
            List<BizDataQualityFillTask> childTasks) {
        Map<TypicalTaskKey, TypicalBatch> batches = new LinkedHashMap<>();
        for (PointRuntimeConfig point : context.points().values()) {
            for (long minute = targetFrom; minute < targetTo;
                    minute += MINUTE_MILLIS) {
                PointMinute key = new PointMinute(point.pointId(), minute);
                // Q2 只能填真正缺失的分钟；现有 Q0/Q1/Q2 均为正式事实，不能降级或换源。
                if (currentByMinute.containsKey(key)) {
                    continue;
                }
                Optional<BizPointTypicalValueConfig> approved =
                        typicalValueConfigProvider.findApproved(
                                point.pointId(), minute);
                if (approved.isEmpty()) {
                    continue;
                }
                BizPointTypicalValueConfig config = requireApprovedConfig(
                        context.job(), point, minute, approved.orElseThrow());
                int version = Objects.requireNonNull(
                        config.getVersion(), "批准配置 version 不能为空");
                long hourStart = minute - Math.floorMod(minute, HOUR_MILLIS);
                TypicalTaskKey taskKey = new TypicalTaskKey(
                        point.pointId(),
                        requireText(config.getConfigId(), "configId"),
                        version,
                        hourStart);
                TypicalBatch batch = batches.computeIfAbsent(
                        taskKey,
                        ignored -> new TypicalBatch(
                                point,
                                config,
                                typicalTaskBounds(
                                        context, config, hourStart)));
                batch.minutes().add(minute);
            }
        }

        for (Map.Entry<TypicalTaskKey, TypicalBatch> entry : batches.entrySet()) {
            TypicalTaskKey key = entry.getKey();
            TypicalBatch batch = entry.getValue();
            BizDataQualityFillTask child = requireWritableTask(
                    fillTaskRepository.getOrCreate(newQ2Task(
                            context.job(),
                            batch.point(),
                            batch.config(),
                            key.version(),
                            key.hourStart(),
                            batch.bounds(),
                            finalizedAt)));
            double value = requireFiniteAndInRange(
                    batch.point(), batch.config().getTypicalValue());
            List<RawMinuteAggregate> rows = batch.minutes().stream()
                    .map(minute -> generatedMinute(
                            batch.point(),
                            minute,
                            value,
                            2,
                            finalizedAt,
                            requireText(child.getTaskId(), "taskId")))
                    .sorted(ROW_ORDER)
                    .toList();
            List<MinuteQualityWriteResult> results =
                    persistChildBatch(context.job(), child, rows);
            occupyAfterWrite(currentByMinute, rows, results);
            selectedRows.addAll(rows);
            childTasks.add(child);
        }
    }

    /**
     * 将一个 Q1/Q2 子任务批量写入 TDengine，并同步维护 MySQL 审计状态。
     *
     * <p>写入携带旧任务 ID 作为允许替换边界；任一技术失败都把本批分钟逐项记入
     * 冻结失败证据后抛给重算批次。成功只推进首次应用和旧任务替换计数，精确分钟
     * 数由跨库收口根据正式事实重建。</p>
     */
    private List<MinuteQualityWriteResult> persistChildBatch(
            BizDataQualityRecalcJob job,
            BizDataQualityFillTask child,
            List<RawMinuteAggregate> rows) {
        String taskId = requireText(child.getTaskId(), "taskId");
        try {
            List<MinuteQualityWriteResult> results =
                    minuteRepository.saveAllWithQualityPriority(
                            rows, normalizedOptional(job.getSupersedesTaskId()));
            validateWriteResults(rows, results);
            if (results.stream().anyMatch(this::acceptedForTask)) {
                // 人工任务跨 chunk 复用，首次成功后只推进技术状态；精确计数由
                // 后续 TDengine 事实收口重建，避免每分钟高频更新 MySQL。
                markChildApplied(child);
            }
            Map<String, Integer> replacements = actualReplacements(results);
            if (!replacements.isEmpty()) {
                fillTaskRepository.recordReplacements(replacements);
            }
            return results;
        } catch (RuntimeException failure) {
            recordChildFailures(taskId, rows, failure);
            throw failure;
        }
    }

    private Map<String, Integer> actualReplacements(
            List<MinuteQualityWriteResult> results) {
        Map<String, Integer> replacements = new LinkedHashMap<>();
        results.stream()
                .filter(result -> switch (result.outcome()) {
                    case UPGRADED, UPDATED_REAL -> true;
                    case INSERTED, IDEMPOTENT, REJECTED_HIGHER_QUALITY,
                            REJECTED_SAME_QUALITY -> false;
                })
                .map(MinuteQualityWriteResult::previousTaskId)
                .filter(taskId -> taskId != null && !taskId.isBlank())
                .forEach(taskId -> replacements.merge(taskId, 1, Integer::sum));
        return replacements;
    }

    private void markChildApplied(BizDataQualityFillTask child) {
        FillApplyStatus status = Objects.requireNonNull(
                child.getApplyStatus(), "补全任务 applyStatus 不能为空");
        switch (status) {
            case WAITING -> fillTaskRepository.markFirstApplied(
                    child.getTaskId());
            case FAILED -> fillTaskRepository.markRetryRecovered(
                    child.getTaskId(), FillApplyStatus.APPLIED, 0);
            case APPLIED -> {
                // 同一完整 gap 或自然小时跨 chunk 复用时，任务已经处于应用态。
            }
            case REPLACED, VOIDED -> throw new IllegalStateException(
                    "终态补全任务不能继续写入人工重算分钟");
        }
    }

    private void recordChildFailures(
            String taskId,
            List<RawMinuteAggregate> rows,
            RuntimeException failure) {
        String error = failure.getMessage();
        if (error == null || error.isBlank()) {
            error = failure.getClass().getSimpleName();
        }
        for (RawMinuteAggregate row : rows) {
            try {
                fillTaskRepository.recordFailure(
                        taskId, row.minuteStart(), error);
            } catch (RuntimeException recordFailure) {
                // 保留最初的写入/审计异常作为批次失败原因，同时附带失败证据落库异常。
                failure.addSuppressed(recordFailure);
            }
        }
    }

    private void validateWriteResults(
            List<RawMinuteAggregate> rows,
            List<MinuteQualityWriteResult> results) {
        if (results == null || results.size() != rows.size()) {
            throw new IllegalStateException("人工补全分钟写入结果数量与请求不一致");
        }
        for (int index = 0; index < rows.size(); index++) {
            RawMinuteAggregate row = rows.get(index);
            MinuteQualityWriteResult result =
                    Objects.requireNonNull(results.get(index), "writeResult 不能为空");
            if (!row.pointId().equals(result.pointId())
                    || row.minuteStart() != result.minuteStart()) {
                throw new IllegalStateException("人工补全分钟写入结果顺序与请求不一致");
            }
        }
    }

    private void occupyAfterWrite(
            Map<PointMinute, RawMinuteAggregate> currentByMinute,
            List<RawMinuteAggregate> rows,
            List<MinuteQualityWriteResult> results) {
        for (int index = 0; index < rows.size(); index++) {
            RawMinuteAggregate row = rows.get(index);
            MinuteQualityWriteResult result = results.get(index);
            /*
             * 拒绝 Q1 只可能说明并发出现了同等或更高质量正式分钟。虽然一次上下文
             * 快照无法取得该并发行的完整值，也必须把此键视为已占用，避免随后再
             * 创建一个注定被拒绝的 Q2 子任务并污染审计。
             */
            if (acceptedForTask(result)) {
                currentByMinute.put(
                        new PointMinute(row.pointId(), row.minuteStart()), row);
            } else if (result.outcome()
                    == MinuteQualityWriteResult.Outcome.REJECTED_HIGHER_QUALITY
                    || result.outcome()
                    == MinuteQualityWriteResult.Outcome.REJECTED_SAME_QUALITY) {
                int previousQuality = Objects.requireNonNull(
                        result.previousQuality(),
                        "拒绝写入结果 previousQuality 不能为空");
                currentByMinute.put(
                        new PointMinute(row.pointId(), row.minuteStart()),
                        withQuality(row, previousQuality));
            }
        }
    }

    /**
     * 按最终内存快照对目标笛卡尔积逐分钟分类，生成批次推进所需的精确统计。
     * Q0、Q1、Q2 与缺失之和若不等于“测点数 × 分钟数”则拒绝推进游标。
     */
    private RecalculationChunkStats classifyTarget(
            Set<String> pointIds,
            long targetFrom,
            long targetTo,
            Map<PointMinute, RawMinuteAggregate> currentByMinute) {
        int q0 = 0;
        int q1 = 0;
        int q2 = 0;
        int missing = 0;
        for (String pointId : pointIds) {
            for (long minute = targetFrom; minute < targetTo;
                    minute += MINUTE_MILLIS) {
                RawMinuteAggregate row = currentByMinute.get(
                        new PointMinute(pointId, minute));
                if (row == null) {
                    missing++;
                    continue;
                }
                switch (row.dataQuality()) {
                    case 0 -> q0++;
                    case 1 -> q1++;
                    case 2 -> q2++;
                    default -> throw new IllegalStateException(
                            "正式分钟包含未知数据质量: " + row.dataQuality());
                }
            }
        }
        RecalculationChunkStats stats =
                new RecalculationChunkStats(q0, q1, q2, missing);
        int expected = Math.multiplyExact(
                pointIds.size(),
                Math.toIntExact((targetTo - targetFrom) / MINUTE_MILLIS));
        int actual = q0 + q1 + q2 + missing;
        if (actual != expected) {
            throw new IllegalStateException("重算分块最终分类数量与目标测点分钟数不一致");
        }
        return stats;
    }

    private RawMinuteAggregate withQuality(
            RawMinuteAggregate row,
            int dataQuality) {
        return new RawMinuteAggregate(
                row.pointId(),
                row.pointCode(),
                row.buildingId(),
                row.systemGroupId(),
                row.equipId(),
                row.equipCode(),
                row.familyCode(),
                row.componentCode(),
                row.suffixCode(),
                row.isForCalc(),
                row.minuteStart(),
                row.averageValue(),
                row.minimumValue(),
                row.maximumValue(),
                row.sampleCount(),
                dataQuality,
                row.firstReceivedTime(),
                row.lastReceivedTime(),
                row.finalizedAt(),
                row.qualityTaskId());
    }

    private BizDataQualityFillTask newQ1Task(
            BizDataQualityRecalcJob job,
            PointRuntimeConfig point,
            RawMinuteAggregate left,
            RawMinuteAggregate right,
            long taskFrom,
            long taskTo,
            long finalizedAt) {
        FillTaskEvidence.Interpolation evidence =
                new FillTaskEvidence.Interpolation(
                        left.minuteStart(),
                        left.averageValue(),
                        right.minuteStart(),
                        right.averageValue(),
                        InterpolationFillService.ALGORITHM_VERSION);
        BizDataQualityFillTask task = baseTask(
                job,
                point,
                taskFrom,
                taskTo,
                finalizedAt);
        task.setIdempotencyKey(FillTaskIdempotency.recalculationQ1(
                job.getJobId(),
                point.pointId(),
                left.minuteStart(),
                right.minuteStart(),
                InterpolationFillService.ALGORITHM_VERSION));
        task.setDataQuality(1);
        task.setSourceType(FillSourceType.INTERPOLATION);
        task.setAlgorithmVersion(InterpolationFillService.ALGORITHM_VERSION);
        task.setEvidenceJson(evidenceCodec.encode(
                FillSourceType.INTERPOLATION, evidence));
        return task;
    }

    private BizDataQualityFillTask newQ2Task(
            BizDataQualityRecalcJob job,
            PointRuntimeConfig point,
            BizPointTypicalValueConfig config,
            int version,
            long hourStart,
            MinuteBounds bounds,
            long finalizedAt) {
        long validFrom = toEpoch(config.getValidFrom(), "validFrom");
        Long validTo = config.getValidTo() == null
                ? null : toEpoch(config.getValidTo(), "validTo");
        FillTaskEvidence.Typical evidence = new FillTaskEvidence.Typical(
                config.getConfigId(),
                version,
                Objects.requireNonNull(
                        config.getTypicalValue(), "typicalValue 不能为空"),
                requireText(config.getUnit(), "unit"),
                validFrom,
                validTo,
                hourStart,
                TypicalValueFillService.ALGORITHM_VERSION,
                List.of());
        BizDataQualityFillTask task = baseTask(
                job,
                point,
                bounds.fromInclusive(),
                bounds.toExclusive(),
                finalizedAt);
        task.setIdempotencyKey(FillTaskIdempotency.recalculationQ2(
                job.getJobId(),
                point.pointId(),
                config.getConfigId(),
                version,
                hourStart));
        task.setDataQuality(2);
        task.setSourceType(FillSourceType.TYPICAL_VALUE);
        task.setAlgorithmVersion(TypicalValueFillService.ALGORITHM_VERSION);
        task.setEvidenceJson(evidenceCodec.encode(
                FillSourceType.TYPICAL_VALUE, evidence));
        task.setTypicalConfigId(config.getConfigId());
        task.setTypicalConfigVersion(version);
        return task;
    }

    private BizDataQualityFillTask baseTask(
            BizDataQualityRecalcJob job,
            PointRuntimeConfig point,
            long taskFrom,
            long taskTo,
            long finalizedAt) {
        BizDataQualityFillTask task = new BizDataQualityFillTask();
        task.setBuildingId(point.buildingId());
        task.setPointId(point.pointId());
        task.setStartMinute(toLocalDateTime(taskFrom));
        task.setEndMinute(toLocalDateTime(taskTo));
        task.setMinuteCount(Math.toIntExact(
                (taskTo - taskFrom) / MINUTE_MILLIS));
        task.setApplyStatus(FillApplyStatus.WAITING);
        task.setGeneratedAt(toLocalDateTime(finalizedAt));
        task.setRecalcJobId(job.getJobId());
        task.setSupersedesTaskId(
                normalizedOptional(job.getSupersedesTaskId()));
        return task;
    }

    private MinuteBounds typicalTaskBounds(
            ValidatedContext context,
            BizPointTypicalValueConfig config,
            long hourStart) {
        long validFrom = ceilToMinute(toEpoch(
                config.getValidFrom(), "validFrom"));
        long validTo = config.getValidTo() == null
                ? Long.MAX_VALUE
                : ceilToMinute(toEpoch(config.getValidTo(), "validTo"));
        long from = Math.max(
                Math.max(context.jobFrom(), hourStart), validFrom);
        long to = Math.min(
                Math.min(context.jobTo(), hourStart + HOUR_MILLIS), validTo);
        if (from >= to) {
            throw new IllegalStateException(
                    "批准典型值在目标分钟没有有效任务区间");
        }
        return new MinuteBounds(from, to);
    }

    /**
     * 复核运行时提供的典型值仍为本建筑、本测点、目标分钟有效的批准版本。
     * 受理后配置若发生归属或有效期变化，当前块失败而不是静默换用其他历史值。
     */
    private BizPointTypicalValueConfig requireApprovedConfig(
            BizDataQualityRecalcJob job,
            PointRuntimeConfig point,
            long minute,
            BizPointTypicalValueConfig config) {
        if (config.getStatus() != TypicalValueStatus.APPROVED) {
            throw new IllegalStateException("典型值配置必须处于 APPROVED 状态");
        }
        if (!point.pointId().equals(config.getPointId())
                || !point.buildingId().equals(config.getBuildingId())
                || !point.buildingId().equals(job.getBuildingId())) {
            throw new IllegalStateException("典型值配置与重算测点归属不一致");
        }
        int version = Objects.requireNonNull(
                config.getVersion(), "批准配置 version 不能为空");
        if (version <= 0) {
            throw new IllegalStateException("批准配置 version 必须大于 0");
        }
        long validFrom = toEpoch(config.getValidFrom(), "validFrom");
        Long validTo = config.getValidTo() == null
                ? null : toEpoch(config.getValidTo(), "validTo");
        if (minute < validFrom || (validTo != null && minute >= validTo)) {
            throw new IllegalStateException("典型值配置在目标分钟无效");
        }
        requireFiniteAndInRange(point, config.getTypicalValue());
        return config;
    }

    private double requireFiniteAndInRange(
            PointRuntimeConfig point,
            BigDecimal value) {
        BigDecimal required = Objects.requireNonNull(
                value, "typicalValue 不能为空");
        double numeric = required.doubleValue();
        if (!isWithinRange(point, numeric)) {
            throw new IllegalStateException("典型值超出测点量程或不是有限数");
        }
        return numeric;
    }

    /**
     * 固定本分块可参与补全的测点集合及任务边界。
     * 只接受当前仍在线、模拟量且参与计算的同建筑测点，防止批次执行期间的档案变化
     * 造成请求范围与最终审计数量不一致。
     */
    private ValidatedContext validate(
            BizDataQualityRecalcJob job,
            Map<String, PointRuntimeConfig> points,
            long targetFrom,
            long targetTo,
            long contextFrom,
            long contextTo) {
        Objects.requireNonNull(job, "job 不能为空");
        requireText(job.getJobId(), "jobId");
        requireText(job.getBuildingId(), "buildingId");
        Objects.requireNonNull(points, "points 不能为空");
        requireRange(targetFrom, targetTo, "目标区间");
        requireRange(contextFrom, contextTo, "上下文区间");
        if (contextFrom > targetFrom || contextTo < targetTo) {
            throw new IllegalArgumentException("上下文区间必须覆盖目标区间");
        }
        long jobFrom = toEpoch(job.getFromMinute(), "job.fromMinute");
        long jobTo = toEpoch(job.getToMinute(), "job.toMinute");
        requireRange(jobFrom, jobTo, "重算批次区间");
        if (targetFrom < jobFrom || targetTo > jobTo) {
            throw new IllegalArgumentException("目标区间必须位于重算批次区间内");
        }

        Map<String, PointRuntimeConfig> ordered = new LinkedHashMap<>();
        for (String pointId : new TreeSet<>(points.keySet())) {
            PointRuntimeConfig point = Objects.requireNonNull(
                    points.get(pointId), "points 不能包含 null");
            if (!pointId.equals(point.pointId())) {
                throw new IllegalArgumentException("测点 Map 键与 pointId 不一致");
            }
            if (!job.getBuildingId().equals(point.buildingId())) {
                throw new IllegalArgumentException("重算测点不属于批次建筑");
            }
            if (isEligiblePoint(point)) {
                ordered.put(pointId, point);
            }
        }
        if (ordered.isEmpty()) {
            throw new IllegalArgumentException("没有可参与质量补全的测点");
        }
        return new ValidatedContext(job, ordered, jobFrom, jobTo);
    }

    private Map<PointMinute, RawMinuteAggregate> indexCurrentRows(
            List<RawMinuteAggregate> rows,
            Set<String> pointIds,
            long fromInclusive,
            long toExclusive) {
        Map<PointMinute, RawMinuteAggregate> indexed = new LinkedHashMap<>();
        for (RawMinuteAggregate row : Objects.requireNonNull(
                rows, "正式分钟查询结果不能为空")) {
            if (row == null
                    || !pointIds.contains(row.pointId())
                    || row.minuteStart() < fromInclusive
                    || row.minuteStart() >= toExclusive) {
                continue;
            }
            PointMinute key = new PointMinute(
                    row.pointId(), row.minuteStart());
            if (indexed.putIfAbsent(key, row) != null) {
                throw new IllegalStateException("正式分钟查询返回重复测点分钟");
            }
        }
        return indexed;
    }

    private boolean q1MayReplace(RawMinuteAggregate current) {
        return current == null || current.dataQuality() == 2;
    }

    private boolean acceptedForTask(MinuteQualityWriteResult result) {
        return switch (result.outcome()) {
            case INSERTED, UPGRADED, IDEMPOTENT -> true;
            case UPDATED_REAL, REJECTED_HIGHER_QUALITY,
                    REJECTED_SAME_QUALITY -> false;
        };
    }

    private BizDataQualityFillTask requireWritableTask(
            BizDataQualityFillTask task) {
        BizDataQualityFillTask required =
                Objects.requireNonNull(task, "补全任务不能为空");
        requireText(required.getTaskId(), "taskId");
        if (required.getApplyStatus() == FillApplyStatus.VOIDED
                || required.getApplyStatus() == FillApplyStatus.REPLACED) {
            throw new IllegalStateException("终态补全任务不能被人工重算复活");
        }
        return required;
    }

    private RawMinuteAggregate generatedMinute(
            PointRuntimeConfig point,
            long minuteStart,
            double value,
            int dataQuality,
            long finalizedAt,
            String taskId) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("生成分钟值必须是有限数");
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
                dataQuality,
                null,
                null,
                finalizedAt,
                taskId);
    }

    private boolean isEligiblePoint(PointRuntimeConfig point) {
        return point != null
                && "ONLINE".equalsIgnoreCase(point.status())
                && "ANALOG".equalsIgnoreCase(point.dataType())
                && point.isForCalc() == 1;
    }

    private boolean isWithinRange(
            PointRuntimeConfig point,
            double value) {
        if (!Double.isFinite(value)) {
            return false;
        }
        BigDecimal decimal = BigDecimal.valueOf(value);
        return (point.valueMin() == null
                || decimal.compareTo(point.valueMin()) >= 0)
                && (point.valueMax() == null
                || decimal.compareTo(point.valueMax()) <= 0);
    }

    private void requireRange(long from, long to, String field) {
        requireMinuteAligned(from, field + ".from");
        requireMinuteAligned(to, field + ".to");
        if (to <= from) {
            throw new IllegalArgumentException(field + "终点必须晚于起点");
        }
    }

    private void requireMinuteAligned(long value, String field) {
        if (Math.floorMod(value, MINUTE_MILLIS) != 0L) {
            throw new IllegalArgumentException(field + "必须对齐到分钟");
        }
    }

    private long ceilToMinute(long value) {
        long remainder = Math.floorMod(value, MINUTE_MILLIS);
        return remainder == 0L
                ? value
                : Math.addExact(value, MINUTE_MILLIS - remainder);
    }

    private long toEpoch(LocalDateTime value, String field) {
        return Objects.requireNonNull(value, field + " 不能为空")
                .atZone(PROJECT_ZONE)
                .toInstant()
                .toEpochMilli();
    }

    private LocalDateTime toLocalDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMillis), PROJECT_ZONE);
    }

    private String normalizedOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    /**
     * 当前 chunk 的质量选择结果。
     *
     * <p>Q1/Q2 行表示本轮尝试应用的数据；最终统计由本服务把唯一一次上下文
     * 快照与逐行写入结果合并后得到。编排层直接使用该统计推进游标，不能再查询
     * TDengine，否则会破坏每 chunk 一次正式分钟批量读取的性能边界。</p>
     */
    public record ChunkSelection(
            List<RawMinuteAggregate> q1Rows,
            List<RawMinuteAggregate> q2Rows,
            List<BizDataQualityFillTask> childTasks,
            RecalculationChunkStats finalStats) {

        public ChunkSelection {
            q1Rows = List.copyOf(q1Rows);
            q2Rows = List.copyOf(q2Rows);
            childTasks = List.copyOf(childTasks);
            finalStats = Objects.requireNonNull(
                    finalStats, "finalStats 不能为空");
        }
    }

    private record PointMinute(String pointId, long minuteStart) {
    }

    private record TypicalTaskKey(
            String pointId,
            String configId,
            int version,
            long hourStart) {
    }

    private record MinuteBounds(long fromInclusive, long toExclusive) {
    }

    private record ValidatedContext(
            BizDataQualityRecalcJob job,
            Map<String, PointRuntimeConfig> points,
            long jobFrom,
            long jobTo) {

        private ValidatedContext {
            points = Collections.unmodifiableMap(
                    new LinkedHashMap<>(points));
        }
    }

    private record TypicalBatch(
            PointRuntimeConfig point,
            BizPointTypicalValueConfig config,
            MinuteBounds bounds,
            List<Long> minutes) {

        private TypicalBatch(
                PointRuntimeConfig point,
                BizPointTypicalValueConfig config,
                MinuteBounds bounds) {
            this(point, config, bounds, new ArrayList<>());
        }
    }
}
