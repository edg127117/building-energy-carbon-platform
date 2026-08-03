package com.platform.iot.dataquality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.dataquality.model.RecalculationJobPhase;
import com.platform.iot.dataquality.model.RecalculationJobType;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import com.platform.iot.dataquality.model.entity.BizDataQualityRecalcJob;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 作废并重算批次中旧补全任务的所有权安全删除与精确收口服务。
 *
 * <p>本服务先把仍由旧任务持有的分钟冻结到 MySQL，再逐分钟进入 TDengine
 * 所有权锁执行条件删除。重启后只使用冻结列表继续核对，避免重新扫描时扩大删除
 * 范围或误删已经升级为 Q0、Q1 及其他任务的数据。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(
        prefix = "data-quality",
        name = {"enabled", "recalculation-enabled"},
        havingValue = "true")
public class RecalculationVoidService {

    private static final long MINUTE_MILLIS = 60_000L;
    private static final int MAX_VOID_MINUTES = 60;
    private static final String TD_FAILURE_SUMMARY = "TDengine作废操作失败";
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final TypeReference<List<Long>> LONG_LIST =
            new TypeReference<>() {
            };

    private final HvacMinuteRepository minuteRepository;
    private final FillTaskRepository fillTaskRepository;
    private final RecalculationJobRepository jobRepository;
    private final ObjectMapper objectMapper;

    public RecalculationVoidService(
            HvacMinuteRepository minuteRepository,
            FillTaskRepository fillTaskRepository,
            RecalculationJobRepository jobRepository,
            ObjectMapper objectMapper) {
        this.minuteRepository =
                Objects.requireNonNull(minuteRepository, "minuteRepository 不能为空");
        this.fillTaskRepository =
                Objects.requireNonNull(fillTaskRepository, "fillTaskRepository 不能为空");
        this.jobRepository =
                Objects.requireNonNull(jobRepository, "jobRepository 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 作废旧任务并返回本批次作废、替换计数。
     *
     * <p>MySQL 与 TDengine 不具备分布式事务，因此冻结目标是跨库恢复点。只有旧任务
     * 和批次均完成精确收口后，调用方才能进入后续重算阶段。</p>
     */
    public VoidResult voidOldTask(
            BizDataQualityRecalcJob job,
            BizDataQualityFillTask oldTask,
            long now) {
        VoidContext context = validate(job, oldTask, now);
        List<Long> targets = resolveFrozenTargets(job, oldTask, context);

        for (Long target : targets) {
            deleteSafely(job.getJobId(), oldTask, target);
        }

        List<RawMinuteAggregate> current = readRangeSafely(
                job.getJobId(), oldTask, context.fromInclusive(), context.toExclusive());
        Map<Long, RawMinuteAggregate> currentByMinute =
                indexCurrentRows(oldTask, context, current);
        Set<Long> targetMinutes = Set.copyOf(targets);
        boolean stillOwned = currentByMinute.values().stream()
                .anyMatch(row -> oldTask.getTaskId().equals(row.qualityTaskId()));
        if (stillOwned) {
            // 条件删除返回后仍能读到旧所有权，说明 TDengine 尚未完成删除或发生异常。
            // 此时绝不能收口为 VOIDED，否则后续重算可能与旧数据并存。
            throw new IllegalStateException(TD_FAILURE_SUMMARY);
        }

        int failedCount = nonNegative(oldTask.getFailedCount(), "failedCount");
        int previousMinuteCount =
                nonNegative(oldTask.getMinuteCount(), "minuteCount");
        int previousReplaced =
                nonNegative(oldTask.getReplacedCount(), "replacedCount");
        int previousVoided =
                nonNegative(oldTask.getVoidedCount(), "voidedCount");

        /*
         * 典型值任务的表区间通常是一整个自然小时，但它可能只真正写入其中几个
         * 缺失分钟。不能把范围内所有既有 Q0/Q1 都算成该任务的 replacement。
         * 已收口任务以 minuteCount 推导原先仍应持有但现在已换源/缺失的数量；
         * 尚未小时收口的热任务 minuteCount 可能仍为 0，此时冻结目标本身就是
         * 最可信的实际应用事实。
         */
        int expectedPreviouslyOwned = Math.max(
                0,
                previousMinuteCount
                        - failedCount
                        - previousReplaced
                        - previousVoided);
        int historicalReplacementCapacity = Math.max(
                0, expectedPreviouslyOwned - targets.size());
        int nonTargetCurrentRows = Math.toIntExact(currentByMinute.keySet().stream()
                .filter(minute -> !targetMinutes.contains(minute))
                .count());
        int historicalReplaced = Math.min(
                historicalReplacementCapacity, nonTargetCurrentRows);
        int historicalAbsent =
                historicalReplacementCapacity - historicalReplaced;

        int targetReplaced = Math.toIntExact(currentByMinute.keySet().stream()
                .filter(targetMinutes::contains)
                .count());
        int targetVoided = targets.size() - targetReplaced;
        int replacedCount = Math.addExact(
                previousReplaced,
                Math.addExact(historicalReplaced, targetReplaced));
        int voidedCount = Math.addExact(
                previousVoided,
                Math.addExact(historicalAbsent, targetVoided));
        int minuteCount = Math.addExact(
                failedCount, Math.addExact(replacedCount, voidedCount));
        LocalDateTime at = toLocal(now);

        // 两个 MySQL 收口都使用精确值而不是累加；在 TD 删除后进程重启，
        // 重复执行仍会得到相同旧任务计数和批次阶段。
        fillTaskRepository.markVoidedExact(
                oldTask.getTaskId(),
                job.getOperatorId(),
                job.getReason(),
                at,
                minuteCount,
                failedCount,
                replacedCount,
                voidedCount);
        jobRepository.completeVoid(job.getJobId(), voidedCount, replacedCount);
        return new VoidResult(voidedCount, replacedCount);
    }

    /**
     * 首次执行时冻结仍由旧 taskId 持有的分钟，重启后只读取该冻结清单。
     * 冻结记录必须先于任何 TDengine 删除提交，否则进程中断后无法区分“原本不存在”
     * 与“已经被本批删除”，也就不能精确恢复作废和替换计数。
     */
    private List<Long> resolveFrozenTargets(
            BizDataQualityRecalcJob job,
            BizDataQualityFillTask oldTask,
            VoidContext context) {
        if (job.getVoidTargetMinutesJson() != null) {
            return decodeTargets(
                    job.getVoidTargetMinutesJson(),
                    context.fromInclusive(),
                    context.toExclusive());
        }

        List<RawMinuteAggregate> current = readRangeSafely(
                job.getJobId(), oldTask, context.fromInclusive(), context.toExclusive());
        List<Long> targets = current.stream()
                .filter(row -> oldTask.getTaskId().equals(row.qualityTaskId()))
                .map(RawMinuteAggregate::minuteStart)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(TreeSet::new),
                        ArrayList::new));
        String encoded = encodeTargets(targets);
        // 这是跨 MySQL/TDengine 的恢复点：任何 DELETE 都必须发生在冻结成功之后。
        jobRepository.freezeVoidTargets(job.getJobId(), encoded);
        return List.copyOf(targets);
    }

    private Map<Long, RawMinuteAggregate> indexCurrentRows(
            BizDataQualityFillTask oldTask,
            VoidContext context,
            List<RawMinuteAggregate> rows) {
        Map<Long, RawMinuteAggregate> indexed = new LinkedHashMap<>();
        for (RawMinuteAggregate row : rows) {
            if (!oldTask.getPointId().equals(row.pointId())
                    || row.minuteStart() < context.fromInclusive()
                    || row.minuteStart() >= context.toExclusive()) {
                throw new IllegalStateException("TDengine返回了作废范围外的分钟");
            }
            RawMinuteAggregate duplicate = indexed.put(row.minuteStart(), row);
            if (duplicate != null) {
                throw new IllegalStateException("TDengine返回了重复分钟");
            }
        }
        return indexed;
    }

    private List<RawMinuteAggregate> readRangeSafely(
            String jobId,
            BizDataQualityFillTask oldTask,
            long fromInclusive,
            long toExclusive) {
        try {
            return List.copyOf(minuteRepository.findRange(
                    Set.of(oldTask.getPointId()), fromInclusive, toExclusive));
        } catch (RuntimeException exception) {
            throw tdFailure(jobId, oldTask.getTaskId(), exception);
        }
    }

    private void deleteSafely(
            String jobId,
            BizDataQualityFillTask oldTask,
            long minuteStart) {
        try {
            minuteRepository.deleteIfOwnedByTask(
                    oldTask.getPointId(), minuteStart, oldTask.getTaskId());
        } catch (RuntimeException exception) {
            throw tdFailure(jobId, oldTask.getTaskId(), exception);
        }
    }

    private IllegalStateException tdFailure(
            String jobId,
            String taskId,
            RuntimeException exception) {
        // 原始 JDBC/SQL/连接异常只写服务端错误日志，调度器持久化固定摘要。
        log.error(
                "人工重算批次执行旧任务作废时访问 TDengine 失败: jobId={}, taskId={}",
                jobId,
                taskId,
                exception);
        return new IllegalStateException(TD_FAILURE_SUMMARY);
    }

    /**
     * 确认作废批次、旧任务、建筑和时间范围完全一致。
     * 该入口一次最多处理 60 个分钟，并要求范围为左闭右开且分钟对齐，避免扩大
     * TDengine 条件删除边界或将其他任务的正式分钟纳入作废统计。
     */
    private VoidContext validate(
            BizDataQualityRecalcJob job,
            BizDataQualityFillTask oldTask,
            long now) {
        Objects.requireNonNull(job, "job 不能为空");
        Objects.requireNonNull(oldTask, "oldTask 不能为空");
        if (job.getJobType() != RecalculationJobType.VOID_AND_RECALCULATE
                || job.getPhase() != RecalculationJobPhase.VOIDING) {
            throw new IllegalArgumentException("批次不处于旧任务作废阶段");
        }
        requireText(job.getJobId(), "jobId");
        requireText(job.getSupersedesTaskId(), "supersedesTaskId");
        requireText(oldTask.getTaskId(), "oldTask.taskId");
        requireText(oldTask.getPointId(), "oldTask.pointId");
        requireText(oldTask.getBuildingId(), "oldTask.buildingId");
        requireText(job.getBuildingId(), "job.buildingId");
        requireText(job.getReason(), "job.reason");
        if (!job.getSupersedesTaskId().equals(oldTask.getTaskId())) {
            throw new IllegalArgumentException("批次目标与旧补全任务不一致");
        }
        if (!job.getBuildingId().equals(oldTask.getBuildingId())) {
            throw new IllegalArgumentException("批次建筑与旧补全任务不一致");
        }
        if (job.getOperatorId() == null || job.getOperatorId() <= 0L) {
            throw new IllegalArgumentException("operatorId 必须大于 0");
        }
        Objects.requireNonNull(oldTask.getStartMinute(), "startMinute 不能为空");
        Objects.requireNonNull(oldTask.getEndMinute(), "endMinute 不能为空");
        long fromInclusive = toEpoch(oldTask.getStartMinute());
        long toExclusive = toEpoch(oldTask.getEndMinute());
        if (Math.floorMod(fromInclusive, MINUTE_MILLIS) != 0L
                || Math.floorMod(toExclusive, MINUTE_MILLIS) != 0L
                || fromInclusive >= toExclusive) {
            throw new IllegalArgumentException("旧补全任务时间范围必须为有效分钟半开区间");
        }
        long rangeMinutes = (toExclusive - fromInclusive) / MINUTE_MILLIS;
        if (rangeMinutes > MAX_VOID_MINUTES) {
            throw new IllegalArgumentException("旧补全任务作废范围不能超过 60 分钟");
        }
        toLocal(now);
        return new VoidContext(fromInclusive, toExclusive);
    }

    private List<Long> decodeTargets(
            String json,
            long fromInclusive,
            long toExclusive) {
        try {
            List<Long> decoded = objectMapper.readValue(json, LONG_LIST);
            if (decoded == null || decoded.size() > MAX_VOID_MINUTES) {
                throw new IllegalStateException("作废目标分钟已损坏");
            }
            TreeSet<Long> sorted = new TreeSet<>();
            for (Long minute : decoded) {
                if (minute == null
                        || Math.floorMod(minute, MINUTE_MILLIS) != 0L
                        || minute < fromInclusive
                        || minute >= toExclusive
                        || !sorted.add(minute)) {
                    throw new IllegalStateException("作废目标分钟已损坏");
                }
            }
            return List.copyOf(sorted);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("作废目标分钟已损坏");
        }
    }

    private String encodeTargets(List<Long> targets) {
        try {
            return objectMapper.writeValueAsString(targets);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("作废目标分钟无法序列化");
        }
    }

    private int nonNegative(Integer value, String field) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException(field + " 不能为负数或空");
        }
        return value;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    private long toEpoch(LocalDateTime value) {
        return value.atZone(PROJECT_ZONE).toInstant().toEpochMilli();
    }

    private LocalDateTime toLocal(long value) {
        try {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(value), PROJECT_ZONE);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("时间戳超出允许范围");
        }
    }

    public record VoidResult(int voidedCount, int replacedCount) {

        public VoidResult {
            if (voidedCount < 0 || replacedCount < 0) {
                throw new IllegalArgumentException("作废结果计数不能为负数");
            }
        }
    }

    private record VoidContext(long fromInclusive, long toExclusive) {
    }
}
