package com.platform.iot.dataquality;

import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.FillSourceType;
import com.platform.iot.dataquality.model.FillTaskEvidence;
import com.platform.iot.dataquality.model.TaskReconciliation;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

/**
 * 依据 TDengine 分钟事实一次性收口 MySQL 补全任务。
 *
 * <p>质量 2 按自然小时重建实际分钟段；质量 1 同时补偿“分钟已写入但 MySQL
 * reconcile 中断”的 APPLIED/closedAt 空窗口。该服务不参与分钟热路径，
 * 因此不会把每分钟写入放大为额外 MySQL 更新。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(
        prefix = "data-quality",
        name = {"enabled", "reconciliation-enabled"},
        havingValue = "true")
public class FillTaskReconciliationService {

    private static final int BATCH_LIMIT = 100;
    private static final long MINUTE_MILLIS = 60_000L;
    private static final long HOUR_MILLIS = 3_600_000L;
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    private final FillTaskRepository fillTaskRepository;
    private final FillTaskEvidenceCodec evidenceCodec;
    private final HvacMinuteRepository minuteRepository;

    public FillTaskReconciliationService(
            FillTaskRepository fillTaskRepository,
            FillTaskEvidenceCodec evidenceCodec,
            HvacMinuteRepository minuteRepository) {
        this.fillTaskRepository =
                Objects.requireNonNull(fillTaskRepository, "fillTaskRepository");
        this.evidenceCodec = Objects.requireNonNull(evidenceCodec, "evidenceCodec");
        this.minuteRepository =
                Objects.requireNonNull(minuteRepository, "minuteRepository");
    }

    /**
     * 只处理已经结束的自然小时和遗留 Q1；单个 TDengine/MySQL 失败不会阻断同批。
     */
    public synchronized void reconcile(long now) {
        LocalDateTime closedAt = toLocalDateTime(now);
        long currentHour = now - Math.floorMod(now, HOUR_MILLIS);
        List<BizDataQualityFillTask> typicalTasks =
                fillTaskRepository.findTypicalTasksToClose(
                        toLocalDateTime(currentHour), BATCH_LIMIT);
        for (BizDataQualityFillTask task : typicalTasks) {
            reconcileSafely(task, closedAt);
        }

        List<BizDataQualityFillTask> interpolationTasks =
                fillTaskRepository.findInterpolationTasksToClose(
                        closedAt, BATCH_LIMIT);
        for (BizDataQualityFillTask task : interpolationTasks) {
            reconcileSafely(task, closedAt);
        }
    }

    private void reconcileSafely(
            BizDataQualityFillTask task, LocalDateTime closedAt) {
        try {
            reconcileTask(task, closedAt);
        } catch (RuntimeException exception) {
            // 跨库收口必须按任务隔离；未写 closed_at 的任务会在下一轮重新出现。
            log.error("补全任务跨库收口失败，保留原状态等待下一轮: taskId={}",
                    task == null ? null : task.getTaskId(), exception);
        }
    }

    /**
     * 以 TDengine 中仍由 taskId 持有的正式分钟重建一条 MySQL 任务。
     *
     * <p>Q2 会把离散分钟压缩为 evidence 中的实际应用区间；Q1 保留冻结端点不变。
     * 失败、替换和作废计数沿用 MySQL 审计事实，最终状态由四类计数共同决定。</p>
     */
    private void reconcileTask(
            BizDataQualityFillTask task, LocalDateTime closedAt) {
        Objects.requireNonNull(task, "task 不能为空");
        FillSourceType sourceType = Objects.requireNonNull(
                task.getSourceType(), "补全任务 sourceType 缺失");
        FillTaskEvidence evidence = evidenceCodec.decode(
                sourceType, task.getEvidenceJson());
        long from = toEpoch(task.getStartMinute(), "startMinute");
        long to = toEpoch(task.getEndMinute(), "endMinute");
        List<RawMinuteAggregate> taskRows =
                minuteRepository.findByQualityTaskId(
                        task.getTaskId(),
                        task.getPointId(),
                        from,
                        to,
                        61);
        validateTaskRows(task, from, to, taskRows);
        Set<Long> appliedMinutes = new LinkedHashSet<>();
        taskRows.stream()
                .filter(row -> task.getTaskId().equals(row.qualityTaskId()))
                .filter(row -> Objects.equals(
                        task.getDataQuality(), row.dataQuality()))
                .map(RawMinuteAggregate::minuteStart)
                .sorted()
                .forEach(appliedMinutes::add);

        int applied = appliedMinutes.size();
        int failed = nonNegative(task.getFailedCount());
        int replaced = nonNegative(task.getReplacedCount());
        int voided = nonNegative(task.getVoidedCount());
        int minuteCount = Math.addExact(
                Math.addExact(applied, failed),
                Math.addExact(replaced, voided));
        FillApplyStatus status;
        if (failed > 0) {
            status = FillApplyStatus.FAILED;
        } else if (applied == 0 && replaced > 0) {
            status = FillApplyStatus.REPLACED;
        } else if (applied > 0) {
            status = FillApplyStatus.APPLIED;
        } else {
            // Q2 创建后可能被并发 Q0/Q1 拒绝，既没有 TD 行也没有失败分钟；
            // 这是“没有实际应用”的终态，不应制造一个无法重试的 FAILED 死任务。
            status = FillApplyStatus.REPLACED;
        }
        List<FillTaskEvidence.MinuteSegment> segments =
                evidence instanceof FillTaskEvidence.Typical
                        ? compress(appliedMinutes)
                        : List.of();
        fillTaskRepository.reconcile(new TaskReconciliation(
                task.getTaskId(),
                minuteCount,
                applied,
                failed,
                replaced,
                voided,
                status,
                segments,
                closedAt));
    }

    /**
     * 拒绝 TDengine 返回的越界、错归属、错质量或重复分钟。
     * 收口结果会覆盖 MySQL 计数，因此不能在查询异常时静默忽略可疑行。
     */
    private void validateTaskRows(
            BizDataQualityFillTask task,
            long from,
            long to,
            List<RawMinuteAggregate> rows) {
        Set<Long> seen = new HashSet<>();
        for (RawMinuteAggregate row : rows) {
            if (!task.getTaskId().equals(row.qualityTaskId())
                    || !task.getPointId().equals(row.pointId())
                    || row.minuteStart() < from
                    || row.minuteStart() >= to
                    || !Objects.equals(task.getDataQuality(), row.dataQuality())) {
                throw new IllegalStateException(
                        "TDengine 返回了越过补全任务边界的分钟");
            }
            if (!seen.add(row.minuteStart())) {
                throw new IllegalStateException(
                        "TDengine 返回重复的补全任务分钟");
            }
        }
    }

    private List<FillTaskEvidence.MinuteSegment> compress(
            Set<Long> minutes) {
        if (minutes.isEmpty()) {
            return List.of();
        }
        List<Long> ordered = minutes.stream().sorted().toList();
        List<FillTaskEvidence.MinuteSegment> segments = new ArrayList<>();
        long start = ordered.getFirst();
        long previous = start;
        for (int index = 1; index < ordered.size(); index++) {
            long current = ordered.get(index);
            if (current != previous + MINUTE_MILLIS) {
                segments.add(new FillTaskEvidence.MinuteSegment(
                        start, previous + MINUTE_MILLIS));
                start = current;
            }
            previous = current;
        }
        segments.add(new FillTaskEvidence.MinuteSegment(
                start, previous + MINUTE_MILLIS));
        return List.copyOf(segments);
    }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private LocalDateTime toLocalDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMillis), PROJECT_ZONE);
    }

    private long toEpoch(LocalDateTime value, String field) {
        return Objects.requireNonNull(value, field + " 不能为空")
                .atZone(PROJECT_ZONE).toInstant().toEpochMilli();
    }
}
