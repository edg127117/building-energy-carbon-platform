package com.platform.iot.dataquality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.dataquality.mapper.BizDataQualityFillTaskMapper;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.FillSourceType;
import com.platform.iot.dataquality.model.FillTaskEvidence;
import com.platform.iot.dataquality.model.TaskReconciliation;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用 MyBatis Mapper 持久化补全任务的 MySQL 仓储。
 *
 * <p>任务与 TDengine 分钟行通过 taskId 关联，但这里不会注入 TDengine
 * JdbcTemplate。跨库一致性依靠确定性幂等键、原子状态更新和后续恢复收口完成，
 * 不伪装成数据库分布式事务。</p>
 */
@Repository
public class MySqlFillTaskRepository implements FillTaskRepository {

    private static final int MAX_FAILED_MINUTES = 60;
    private static final int MAX_LAST_ERROR_LENGTH = 1000;
    private static final long MINUTE_MILLIS = 60_000L;
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final TypeReference<List<FailedMinute>> FAILED_MINUTE_LIST =
            new TypeReference<>() {
            };

    private final BizDataQualityFillTaskMapper mapper;
    private final FillTaskEvidenceCodec evidenceCodec;
    private final ObjectMapper objectMapper;

    public MySqlFillTaskRepository(
            BizDataQualityFillTaskMapper mapper,
            FillTaskEvidenceCodec evidenceCodec,
            ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper 不能为空");
        this.evidenceCodec =
                Objects.requireNonNull(evidenceCodec, "evidenceCodec 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 唯一键冲突说明另一线程已创建相同业务批次，此时重新读取并复用该任务。
     */
    @Override
    @Transactional
    public BizDataQualityFillTask getOrCreate(BizDataQualityFillTask candidate) {
        validateCandidate(candidate);
        BizDataQualityFillTask existing =
                mapper.selectByIdempotencyKey(candidate.getIdempotencyKey());
        if (existing != null) {
            return validateStoredTask(existing);
        }

        initializeForInsert(candidate);
        try {
            if (mapper.insert(candidate) != 1) {
                throw new IllegalStateException("补全任务创建失败");
            }
            return candidate;
        } catch (DuplicateKeyException duplicate) {
            BizDataQualityFillTask concurrent =
                    mapper.selectByIdempotencyKeyForUpdate(
                            candidate.getIdempotencyKey());
            if (concurrent == null) {
                throw duplicate;
            }
            return validateStoredTask(concurrent);
        }
    }

    @Override
    public Optional<BizDataQualityFillTask> findById(String taskId) {
        requireText(taskId, "taskId");
        return Optional.ofNullable(mapper.selectById(taskId))
                .map(this::validateStoredTask);
    }

    @Override
    @Transactional
    public Optional<BizDataQualityFillTask> findByIdForUpdate(String taskId) {
        requireText(taskId, "taskId");
        return Optional.ofNullable(mapper.selectByTaskIdForUpdate(taskId))
                .map(this::validateStoredTask);
    }

    @Override
    public void markFirstApplied(String taskId) {
        requireText(taskId, "taskId");
        // 返回 0 既可能表示任务已应用，也可能表示任务已进入其他终态；幂等调用无需报错。
        mapper.markFirstApplied(taskId);
    }

    @Override
    public void incrementReplacedCount(String taskId, int increment) {
        requireText(taskId, "taskId");
        if (increment <= 0) {
            throw new IllegalArgumentException("increment 必须大于 0");
        }
        // TDengine 是分钟事实来源；任务并发作废或已删除时返回 0，等待小时收口
        // 根据实际 quality_task_id 重建计数，不能反向撤销已经写入的 Q0。
        mapper.incrementReplacedCountAtomic(taskId, increment);
    }

    /**
     * 失败分钟 JSON 需要先锁行再合并，保证同一任务并发失败时不会相互覆盖。
     * 相同分钟只保留最新错误，列表超过自然小时上限时淘汰最早记录。
     */
    @Override
    @Transactional
    public void recordFailure(String taskId, long minuteStart, String error) {
        requireText(taskId, "taskId");
        requireMinuteAligned(minuteStart);
        String normalizedError = normalizeError(error);
        BizDataQualityFillTask task = Optional.ofNullable(
                        mapper.selectByTaskIdForUpdate(taskId))
                .orElseThrow(() -> new IllegalArgumentException(
                        "补全任务不存在: " + taskId));
        validateStoredTask(task);
        requireFailureWritable(task);
        requireMinuteWithinTask(task, minuteStart);

        LinkedHashMap<Long, FailedMinute> failures = new LinkedHashMap<>();
        for (FailedMinute failure : decodeFailures(task.getFailedMinutesJson())) {
            failures.put(failure.minuteStart(), failure);
        }
        // 重复分钟移到末尾，代表当前保存的是最近一次失败及其错误。
        failures.remove(minuteStart);
        failures.put(minuteStart, new FailedMinute(minuteStart, normalizedError));
        while (failures.size() > MAX_FAILED_MINUTES) {
            Long oldestMinute = failures.keySet().iterator().next();
            failures.remove(oldestMinute);
        }

        String failedMinutesJson = encodeFailures(
                new ArrayList<>(failures.values()));
        int updated = mapper.recordFailureAtomic(
                taskId, failedMinutesJson, failures.size(), normalizedError);
        if (updated != 1) {
            throw new IllegalStateException("补全任务失败状态更新失败: " + taskId);
        }
    }

    /**
     * 只有典型值小时任务包含可变的应用区间；插值证据的两个真实端点不可改写。
     */
    @Override
    @Transactional
    public void reconcile(TaskReconciliation result) {
        Objects.requireNonNull(result, "result 不能为空");
        BizDataQualityFillTask task = Optional.ofNullable(
                        mapper.selectByTaskIdForUpdate(result.taskId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "补全任务不存在: " + result.taskId()));
        validateReconciliationTransition(task, result.applyStatus());
        FillTaskEvidence current =
                evidenceCodec.decode(task.getSourceType(), task.getEvidenceJson());
        FillTaskEvidence reconciled = withAppliedSegments(
                task.getSourceType(), current, result.appliedSegments());
        String evidenceJson = evidenceCodec.encode(task.getSourceType(), reconciled);

        int updated = mapper.reconcileAtomic(
                result.taskId(),
                result.minuteCount(),
                result.appliedCount(),
                result.failedCount(),
                result.replacedCount(),
                result.voidedCount(),
                result.applyStatus(),
                evidenceJson,
                result.closedAt());
        if (updated != 1) {
            throw new IllegalStateException(
                    "补全任务收口状态更新失败: " + result.taskId());
        }
    }

    @Override
    public void markVoided(
            String taskId,
            long operatorId,
            String reason,
            LocalDateTime at) {
        requireText(taskId, "taskId");
        if (operatorId <= 0L) {
            throw new IllegalArgumentException("operatorId 必须大于 0");
        }
        String normalizedReason = requireText(reason, "reason");
        Objects.requireNonNull(at, "at 不能为空");
        // 已作废任务返回 0，视为幂等成功；不存在任务由上层查询接口报告 404。
        mapper.markVoidedAtomic(taskId, operatorId, normalizedReason, at);
    }

    private FillTaskEvidence withAppliedSegments(
            FillSourceType sourceType,
            FillTaskEvidence evidence,
            List<FillTaskEvidence.MinuteSegment> appliedSegments) {
        if (evidence instanceof FillTaskEvidence.Typical typical) {
            return new FillTaskEvidence.Typical(
                    typical.configId(),
                    typical.version(),
                    typical.value(),
                    typical.unit(),
                    typical.validFrom(),
                    typical.validTo(),
                    typical.hourStart(),
                    typical.algorithmVersion(),
                    List.copyOf(appliedSegments));
        }
        if (!appliedSegments.isEmpty()) {
            throw new IllegalArgumentException(
                    "插值任务不能改写典型值应用区间");
        }
        if (sourceType != FillSourceType.INTERPOLATION) {
            throw new IllegalArgumentException("未知的补全任务来源类型");
        }
        return evidence;
    }

    private void validateCandidate(BizDataQualityFillTask candidate) {
        Objects.requireNonNull(candidate, "candidate 不能为空");
        requireText(candidate.getIdempotencyKey(), "idempotencyKey");
        if (candidate.getIdempotencyKey().length() > 160) {
            throw new IllegalArgumentException("idempotencyKey 不能超过 160 个字符");
        }
        requireText(candidate.getBuildingId(), "buildingId");
        requireText(candidate.getPointId(), "pointId");
        Objects.requireNonNull(candidate.getStartMinute(), "startMinute 不能为空");
        Objects.requireNonNull(candidate.getEndMinute(), "endMinute 不能为空");
        if (!candidate.getEndMinute().isAfter(candidate.getStartMinute())) {
            throw new IllegalArgumentException("endMinute 必须晚于 startMinute");
        }
        requireText(candidate.getAlgorithmVersion(), "algorithmVersion");
        FillSourceType sourceType =
                Objects.requireNonNull(candidate.getSourceType(), "sourceType 不能为空");
        FillTaskEvidence evidence =
                evidenceCodec.decode(sourceType, candidate.getEvidenceJson());
        validateEvidenceMatchesTask(candidate, evidence);
    }

    private BizDataQualityFillTask validateStoredTask(
            BizDataQualityFillTask task) {
        if (task.getSourceType() == null) {
            throw new IllegalStateException("补全任务 sourceType 缺失: " + task.getTaskId());
        }
        try {
            FillTaskEvidence evidence =
                    evidenceCodec.decode(task.getSourceType(), task.getEvidenceJson());
            validateEvidenceMatchesTask(task, evidence);
        } catch (IllegalArgumentException invalidEvidence) {
            throw new IllegalStateException(
                    "补全任务 evidenceJson 无效: " + task.getTaskId(),
                    invalidEvidence);
        }
        return task;
    }

    private void validateEvidenceMatchesTask(
            BizDataQualityFillTask task,
            FillTaskEvidence evidence) {
        if (!Objects.equals(task.getAlgorithmVersion(), algorithmVersion(evidence))) {
            throw new IllegalArgumentException(
                    "任务 algorithmVersion 与 evidence 不一致");
        }
        if (task.getSourceType() == FillSourceType.TYPICAL_VALUE) {
            if (!Integer.valueOf(2).equals(task.getDataQuality())) {
                throw new IllegalArgumentException("典型值任务的数据质量必须为 2");
            }
            FillTaskEvidence.Typical typical =
                    (FillTaskEvidence.Typical) evidence;
            if (!Objects.equals(task.getTypicalConfigId(), typical.configId())
                    || !Objects.equals(
                    task.getTypicalConfigVersion(), typical.version())) {
                throw new IllegalArgumentException(
                        "任务典型值配置版本与 evidence 不一致");
            }
        } else if (!Integer.valueOf(1).equals(task.getDataQuality())) {
            throw new IllegalArgumentException("插值任务的数据质量必须为 1");
        }
    }

    private void requireMinuteWithinTask(
            BizDataQualityFillTask task,
            long minuteStart) {
        LocalDateTime minute = Instant.ofEpochMilli(minuteStart)
                .atZone(PROJECT_ZONE)
                .toLocalDateTime();
        if (minute.isBefore(task.getStartMinute())
                || !minute.isBefore(task.getEndMinute())) {
            throw new IllegalArgumentException(
                    "失败分钟不在补全任务的左闭右开区间内");
        }
    }

    /**
     * 作废和全部替代都是不可被迟到失败回写覆盖的审计终态。
     */
    private void requireFailureWritable(BizDataQualityFillTask task) {
        FillApplyStatus status = Objects.requireNonNull(
                task.getApplyStatus(), "补全任务 applyStatus 不能为空");
        if (status == FillApplyStatus.REPLACED
                || status == FillApplyStatus.VOIDED) {
            throw new IllegalStateException(
                    "终态补全任务不能记录迟到失败: " + task.getTaskId());
        }
    }

    /**
     * 收口只能产生技术完成态；REPLACED 允许重复收口为自身，VOIDED 只能由人工
     * 作废入口维护完整的操作人、原因和时间，不能被后台核对任务改写。
     */
    private void validateReconciliationTransition(
            BizDataQualityFillTask task,
            FillApplyStatus targetStatus) {
        Objects.requireNonNull(targetStatus, "applyStatus 不能为空");
        if (targetStatus == FillApplyStatus.WAITING
                || targetStatus == FillApplyStatus.VOIDED) {
            throw new IllegalArgumentException(
                    "收口状态只能是 APPLIED、FAILED 或 REPLACED");
        }
        FillApplyStatus currentStatus = Objects.requireNonNull(
                task.getApplyStatus(), "补全任务 applyStatus 不能为空");
        if (currentStatus == FillApplyStatus.VOIDED
                || (currentStatus == FillApplyStatus.REPLACED
                && targetStatus != FillApplyStatus.REPLACED)) {
            throw new IllegalStateException(
                    "终态补全任务不能被收口任务改写: " + task.getTaskId());
        }
    }

    private String algorithmVersion(FillTaskEvidence evidence) {
        return switch (evidence) {
            case FillTaskEvidence.Typical typical -> typical.algorithmVersion();
            case FillTaskEvidence.Interpolation interpolation ->
                    interpolation.algorithmVersion();
        };
    }

    private void initializeForInsert(BizDataQualityFillTask task) {
        if (task.getTaskId() == null || task.getTaskId().isBlank()) {
            task.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        }
        if (task.getApplyStatus() == null) {
            task.setApplyStatus(FillApplyStatus.WAITING);
        }
        task.setMinuteCount(defaultZero(task.getMinuteCount()));
        task.setAppliedCount(defaultZero(task.getAppliedCount()));
        task.setFailedCount(defaultZero(task.getFailedCount()));
        task.setReplacedCount(defaultZero(task.getReplacedCount()));
        task.setVoidedCount(defaultZero(task.getVoidedCount()));
        task.setRetryCount(defaultZero(task.getRetryCount()));
        if (task.getGeneratedAt() == null) {
            task.setGeneratedAt(LocalDateTime.now());
        }
    }

    private List<FailedMinute> decodeFailures(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<FailedMinute> failures =
                    objectMapper.readValue(json, FAILED_MINUTE_LIST);
            if (failures == null) {
                throw new IllegalStateException("failed_minutes_json 不能为 null");
            }
            return failures;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "补全任务 failed_minutes_json 已损坏", exception);
        }
    }

    private String encodeFailures(List<FailedMinute> failures) {
        try {
            return objectMapper.writeValueAsString(failures);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("失败分钟证据无法序列化", exception);
        }
    }

    private int defaultZero(Integer value) {
        return value == null ? 0 : value;
    }

    private void requireMinuteAligned(long minuteStart) {
        if (Math.floorMod(minuteStart, MINUTE_MILLIS) != 0L) {
            throw new IllegalArgumentException("minuteStart 必须对齐到分钟");
        }
    }

    private String normalizeError(String error) {
        String normalized = requireText(error, "error");
        return normalized.length() <= MAX_LAST_ERROR_LENGTH
                ? normalized
                : normalized.substring(0, MAX_LAST_ERROR_LENGTH);
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    private record FailedMinute(long minuteStart, String error) {
    }
}
