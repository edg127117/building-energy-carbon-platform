package com.platform.iot.dataquality;

import com.platform.config.DataQualityProperties;
import com.platform.iot.dataquality.event.HvacMinuteQualityReadyEvent;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.FillSourceType;
import com.platform.iot.dataquality.model.FillTaskEvidence;
import com.platform.iot.dataquality.model.QualityEventSource;
import com.platform.iot.dataquality.model.TaskReconciliation;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointRuntimeConfig;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.MinuteQualityWriteResult;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 在新的质量 0 右端分钟到达后回溯短期缺口，并把缺失或质量 2 分钟升级为质量 1。
 *
 * <p>服务按同一右端分钟批量读取 TDengine，在内存中为每个测点寻找最近质量 0 左端，
 * 从而避免逐点、逐分钟查询。每个连续缺口创建一个 MySQL 审计任务，随后通过
 * {@link HvacMinuteRepository} 的质量优先级批量写入口保护 {@code Q0 > Q1 > Q2}。
 * 只有实际写入或升级成功的历史分钟才发布修正 READY，公式层会据此回读该建筑完整分钟。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(
        prefix = "data-quality", name = "enabled", havingValue = "true")
public class InterpolationFillService {

    public static final String ALGORITHM_VERSION = "LINEAR_V1";
    private static final long MINUTE_MILLIS = 60_000L;
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    private final DataQualityProperties properties;
    private final DataPointConfigProvider pointConfigProvider;
    private final HvacMinuteRepository minuteRepository;
    private final FillTaskRepository fillTaskRepository;
    private final FillTaskEvidenceCodec evidenceCodec;
    private final LinearMinuteInterpolator interpolator;
    private final ApplicationEventPublisher eventPublisher;

    public InterpolationFillService(
            DataQualityProperties properties,
            DataPointConfigProvider pointConfigProvider,
            HvacMinuteRepository minuteRepository,
            FillTaskRepository fillTaskRepository,
            FillTaskEvidenceCodec evidenceCodec,
            LinearMinuteInterpolator interpolator,
            ApplicationEventPublisher eventPublisher) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.pointConfigProvider =
                Objects.requireNonNull(pointConfigProvider, "pointConfigProvider");
        this.minuteRepository =
                Objects.requireNonNull(minuteRepository, "minuteRepository");
        this.fillTaskRepository =
                Objects.requireNonNull(fillTaskRepository, "fillTaskRepository");
        this.evidenceCodec = Objects.requireNonNull(evidenceCodec, "evidenceCodec");
        this.interpolator = Objects.requireNonNull(interpolator, "interpolator");
        this.eventPublisher =
                Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    /**
     * 以一批当前已确认的真实分钟作为右端点，回溯并修正它们之前的短缺口。
     *
     * <p>调用方应在当前分钟 READY 同步处理完成后调用本方法，保证每分钟结果先及时生成，
     * 历史五分钟回溯不会延迟当前结果。不同右端分钟分别执行一次范围查询；正常分钟冻结
     * 批次中的所有测点共享一次查询。</p>
     */
    public void fillFromRightEndpoints(
            Collection<RawMinuteAggregate> rightEndpoints,
            long finalizedAt) {
        Objects.requireNonNull(rightEndpoints, "rightEndpoints");
        if (rightEndpoints.isEmpty()) {
            return;
        }

        Map<String, PointRuntimeConfig> eligiblePoints = pointConfigProvider.findAll()
                .stream()
                .filter(this::isEligiblePoint)
                .collect(Collectors.toMap(
                        PointRuntimeConfig::pointId,
                        point -> point,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        Map<Long, LinkedHashMap<String, RawMinuteAggregate>> grouped =
                groupEligibleRightEndpoints(rightEndpoints, eligiblePoints);
        int maxGapMinutes = properties.getInterpolation().getMaxGapMinutes();
        for (Map.Entry<Long, LinkedHashMap<String, RawMinuteAggregate>> entry
                : grouped.entrySet()) {
            fillRightMinute(
                    entry.getKey(),
                    entry.getValue(),
                    eligiblePoints,
                    finalizedAt,
                    maxGapMinutes);
        }
    }

    private Map<Long, LinkedHashMap<String, RawMinuteAggregate>>
            groupEligibleRightEndpoints(
                    Collection<RawMinuteAggregate> rightEndpoints,
                    Map<String, PointRuntimeConfig> eligiblePoints) {
        Map<Long, LinkedHashMap<String, RawMinuteAggregate>> grouped =
                new LinkedHashMap<>();
        rightEndpoints.stream()
                .filter(Objects::nonNull)
                .filter(row -> row.dataQuality() == 0)
                .filter(row -> eligiblePoints.containsKey(row.pointId()))
                .sorted(Comparator.comparingLong(RawMinuteAggregate::minuteStart)
                        .thenComparing(RawMinuteAggregate::pointId))
                .forEach(row -> grouped
                        .computeIfAbsent(
                                row.minuteStart(), ignored -> new LinkedHashMap<>())
                        .put(row.pointId(), row));
        return grouped;
    }

    private void fillRightMinute(
            long rightMinute,
            LinkedHashMap<String, RawMinuteAggregate> rightByPoint,
            Map<String, PointRuntimeConfig> eligiblePoints,
            long finalizedAt,
            int maxGapMinutes) {
        if (rightByPoint.isEmpty()) {
            return;
        }
        long fromInclusive =
                rightMinute - (long) (maxGapMinutes + 1) * MINUTE_MILLIS;
        long toExclusive = rightMinute + MINUTE_MILLIS;
        Set<String> pointIds = new LinkedHashSet<>(rightByPoint.keySet());
        List<RawMinuteAggregate> range = minuteRepository.findRange(
                pointIds, fromInclusive, toExclusive);
        Map<String, List<RawMinuteAggregate>> rowsByPoint = range.stream()
                .filter(row -> pointIds.contains(row.pointId()))
                .collect(Collectors.groupingBy(
                        RawMinuteAggregate::pointId,
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                rows -> rows.stream()
                                        .sorted(Comparator.comparingLong(
                                                RawMinuteAggregate::minuteStart))
                                        .toList())));

        for (Map.Entry<String, RawMinuteAggregate> entry
                : rightByPoint.entrySet()) {
            try {
                fillOneGap(
                        eligiblePoints.get(entry.getKey()),
                        entry.getValue(),
                        rowsByPoint.getOrDefault(entry.getKey(), List.of()),
                        finalizedAt,
                        maxGapMinutes);
            } catch (RuntimeException exception) {
                // 单个测点的异常不能阻断同批其他测点；已有任务的失败证据由 fillOneGap 记录。
                log.error("质量1历史回溯失败: pointId={}, rightMinute={}",
                        entry.getKey(), rightMinute, exception);
            }
        }
    }

    private void fillOneGap(
            PointRuntimeConfig point,
            RawMinuteAggregate right,
            List<RawMinuteAggregate> rangeRows,
            long finalizedAt,
            int maxGapMinutes) {
        RawMinuteAggregate left = rangeRows.stream()
                .filter(row -> row.minuteStart() < right.minuteStart())
                .filter(row -> row.dataQuality() == 0)
                .max(Comparator.comparingLong(RawMinuteAggregate::minuteStart))
                .orElse(null);
        if (left == null || right.dataQuality() != 0) {
            return;
        }

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
            return;
        }

        Map<Long, RawMinuteAggregate> existingByMinute = rangeRows.stream()
                .collect(Collectors.toMap(
                        RawMinuteAggregate::minuteStart,
                        row -> row,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        List<LinearMinuteInterpolator.InterpolatedMinute> candidates =
                interpolation.stream()
                        .filter(minute -> {
                            RawMinuteAggregate current =
                                    existingByMinute.get(minute.minuteStart());
                            return current == null || current.dataQuality() == 2;
                        })
                        .toList();
        if (candidates.isEmpty()) {
            return;
        }

        BizDataQualityFillTask task = fillTaskRepository.getOrCreate(
                newTask(point, left, right, interpolation.size(), finalizedAt));
        String taskId = requireText(task.getTaskId(), "taskId");
        List<RawMinuteAggregate> generated = candidates.stream()
                .map(candidate -> generatedMinute(
                        point, candidate, finalizedAt, taskId))
                .toList();

        List<MinuteQualityWriteResult> results;
        try {
            // Repository 会按 point+minute 固定顺序获取条带锁，并在一个批次内完成读、比较和写入。
            results = minuteRepository.saveAllWithQualityPriority(
                    generated, null);
            validateWriteResults(generated, results);
        } catch (RuntimeException exception) {
            recordFailures(taskId, generated, exception);
            return;
        }

        try {
            // READY 属于本次 Q1 应用成功条件。若同步公式链路失败，任务必须保持
            // FAILED，后续恢复才能依据 persisted finalizedAt 精确补发。
            for (int index = 0; index < results.size(); index++) {
                if (isActualWrite(results.get(index).outcome())) {
                    publishReady(generated.get(index), finalizedAt);
                }
            }
        } catch (RuntimeException exception) {
            recordFailures(taskId, generated, exception);
            return;
        }
        markFirstAppliedBestEffort(taskId, results);
        recordReplacedTasksBestEffort(results);
        try {
            reconcile(taskId, generated.size(), results, finalizedAt);
        } catch (RuntimeException exception) {
            // TDengine 已经是正式事实，MySQL 收口失败只能交给补偿任务，不能撤销数据或压掉公式修正。
            log.error("质量1分钟已写入，但补全任务收口失败，等待跨库补偿: taskId={}",
                    taskId, exception);
        }
    }

    /**
     * Q1 升级 Q2 后，按写入结果中的旧 taskId 聚合更新 MySQL。
     *
     * <p>不能依赖 Q2 的 minuteCount 作为上限，因为小时热路径首次写入时该计数
     * 仍为 0；最终准确值仍由小时收口按 TDengine 事实重建。</p>
     */
    private void recordReplacedTasksBestEffort(
            List<MinuteQualityWriteResult> results) {
        Map<String, Integer> replacements = new LinkedHashMap<>();
        results.stream()
                .filter(result -> isActualWrite(result.outcome()))
                .map(MinuteQualityWriteResult::previousTaskId)
                .filter(taskId -> taskId != null && !taskId.isBlank())
                .forEach(taskId ->
                        replacements.merge(taskId, 1, Integer::sum));
        if (replacements.isEmpty()) {
            return;
        }
        try {
            fillTaskRepository.recordReplacements(replacements);
        } catch (RuntimeException exception) {
            // TDengine 升级已经成功，不能因审计计数失败撤销 Q1 或压掉 READY；
            // 当前精简模型不在 TD 保留 previousTaskId，后续小时收口无法反推这次
            // 替换，故必须明确记录日志，交由异常审计入口人工修正旧任务计数。
            log.error("质量1已升级分钟，但旧任务替换计数更新失败: taskIds={}",
                    replacements.keySet(), exception);
        }
    }

    private BizDataQualityFillTask newTask(
            PointRuntimeConfig point,
            RawMinuteAggregate left,
            RawMinuteAggregate right,
            int missingCount,
            long finalizedAt) {
        FillTaskEvidence.Interpolation evidence =
                new FillTaskEvidence.Interpolation(
                        left.minuteStart(),
                        left.averageValue(),
                        right.minuteStart(),
                        right.averageValue(),
                        ALGORITHM_VERSION);
        BizDataQualityFillTask task = new BizDataQualityFillTask();
        task.setIdempotencyKey(FillTaskIdempotency.q1(
                point.pointId(),
                left.minuteStart(),
                right.minuteStart(),
                ALGORITHM_VERSION));
        task.setBuildingId(point.buildingId());
        task.setPointId(point.pointId());
        task.setStartMinute(toLocalDateTime(
                left.minuteStart() + MINUTE_MILLIS));
        task.setEndMinute(toLocalDateTime(right.minuteStart()));
        task.setMinuteCount(missingCount);
        task.setDataQuality(1);
        task.setSourceType(FillSourceType.INTERPOLATION);
        task.setAlgorithmVersion(ALGORITHM_VERSION);
        task.setEvidenceJson(evidenceCodec.encode(
                FillSourceType.INTERPOLATION, evidence));
        task.setApplyStatus(FillApplyStatus.WAITING);
        task.setGeneratedAt(toLocalDateTime(finalizedAt));
        return task;
    }

    private RawMinuteAggregate generatedMinute(
            PointRuntimeConfig point,
            LinearMinuteInterpolator.InterpolatedMinute interpolation,
            long finalizedAt,
            String taskId) {
        double value = interpolation.value();
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
                interpolation.minuteStart(),
                value,
                value,
                value,
                0,
                1,
                null,
                null,
                finalizedAt,
                taskId);
    }

    private void validateWriteResults(
            List<RawMinuteAggregate> generated,
            List<MinuteQualityWriteResult> results) {
        if (results == null || generated.size() != results.size()) {
            throw new IllegalStateException(
                    "质量1分钟写入结果数量与请求不一致");
        }
        for (int index = 0; index < generated.size(); index++) {
            RawMinuteAggregate row = generated.get(index);
            MinuteQualityWriteResult result =
                    Objects.requireNonNull(results.get(index), "writeResult");
            if (!row.pointId().equals(result.pointId())
                    || row.minuteStart() != result.minuteStart()) {
                throw new IllegalStateException(
                        "质量1分钟写入结果顺序与请求不一致");
            }
        }
    }

    private void reconcile(
            String taskId,
            int minuteCount,
            List<MinuteQualityWriteResult> results,
            long finalizedAt) {
        int appliedCount = 0;
        int replacedCount = 0;
        for (MinuteQualityWriteResult result : results) {
            if (isActualWrite(result.outcome())
                    || result.outcome()
                    == MinuteQualityWriteResult.Outcome.IDEMPOTENT) {
                appliedCount++;
            } else {
                replacedCount++;
            }
        }
        FillApplyStatus status = appliedCount == 0
                ? FillApplyStatus.REPLACED
                : FillApplyStatus.APPLIED;
        fillTaskRepository.reconcile(new TaskReconciliation(
                taskId,
                minuteCount,
                appliedCount,
                0,
                replacedCount,
                0,
                status,
                List.of(),
                toLocalDateTime(finalizedAt)));
    }

    private void markFirstAppliedBestEffort(
            String taskId,
            List<MinuteQualityWriteResult> results) {
        boolean accepted = results.stream().anyMatch(result ->
                isActualWrite(result.outcome())
                        || result.outcome()
                        == MinuteQualityWriteResult.Outcome.IDEMPOTENT);
        if (!accepted) {
            return;
        }
        try {
            fillTaskRepository.markFirstApplied(taskId);
        } catch (RuntimeException exception) {
            // 后续 reconcile 或 Task 10 跨库补偿仍可恢复 MySQL 状态。
            log.error("质量1分钟已写入，但任务首次应用状态推进失败: taskId={}",
                    taskId, exception);
        }
    }

    private void recordFailures(
            String taskId,
            List<RawMinuteAggregate> generated,
            RuntimeException cause) {
        String error = cause.getMessage();
        if (error == null || error.isBlank()) {
            error = cause.getClass().getSimpleName();
        }
        for (RawMinuteAggregate row : generated) {
            try {
                fillTaskRepository.recordFailure(
                        taskId, row.minuteStart(), error);
            } catch (RuntimeException recordException) {
                log.error("质量1写入及失败证据记录均失败: taskId={}, minute={}",
                        taskId, row.minuteStart(), recordException);
            }
        }
        log.error("质量1批量写入失败，不发布历史修正 READY: taskId={}",
                taskId, cause);
    }

    private void publishReady(
            RawMinuteAggregate generated,
            long finalizedAt) {
        eventPublisher.publishEvent(new HvacMinuteQualityReadyEvent(
                generated.minuteStart(),
                finalizedAt,
                QualityEventSource.INTERPOLATION_CORRECTION,
                Set.of(generated.buildingId()),
                List.of(generated),
                Set.of(generated.pointId())));
    }

    private boolean isActualWrite(MinuteQualityWriteResult.Outcome outcome) {
        return outcome == MinuteQualityWriteResult.Outcome.INSERTED
                || outcome == MinuteQualityWriteResult.Outcome.UPGRADED;
    }

    private boolean isEligiblePoint(PointRuntimeConfig point) {
        return point != null
                && "ONLINE".equalsIgnoreCase(point.status())
                && "ANALOG".equalsIgnoreCase(point.dataType())
                && point.isForCalc() == 1;
    }

    private boolean isWithinRange(PointRuntimeConfig point, double value) {
        if (!Double.isFinite(value)) {
            return false;
        }
        BigDecimal decimal = BigDecimal.valueOf(value);
        return (point.valueMin() == null
                || decimal.compareTo(point.valueMin()) >= 0)
                && (point.valueMax() == null
                || decimal.compareTo(point.valueMax()) <= 0);
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
}
