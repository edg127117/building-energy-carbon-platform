package com.platform.iot.dataquality;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.iot.dataquality.mapper.BizDataQualityRecalcJobMapper;
import com.platform.iot.dataquality.model.RecalculationChunkStats;
import com.platform.iot.dataquality.model.RecalculationJobStatus;
import com.platform.iot.dataquality.model.RecalculationJobType;
import com.platform.iot.dataquality.model.entity.BizDataQualityRecalcJob;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用 MyBatis Mapper 持久化低频人工重算批次。
 *
 * <p>这里明确只访问 MySQL。TDengine 分钟写入由后续重算服务通过时序仓储完成，
 * 两个数据库之间不伪装成分布式事务，而是依靠确定性任务键、持久化游标和条件
 * 状态更新实现恢复。</p>
 */
@Repository
public class MySqlRecalculationJobRepository
        implements RecalculationJobRepository {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_BATCH_LIMIT = 100;
    private static final int MAX_ERROR_LENGTH = 1_000;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_KEY_LENGTH = 160;

    private final BizDataQualityRecalcJobMapper mapper;

    public MySqlRecalculationJobRepository(
            BizDataQualityRecalcJobMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper 不能为空");
    }

    /**
     * 完全相同请求复用一行；并发唯一键冲突后用当前读取得另一事务已创建的批次。
     */
    @Override
    @Transactional
    public BizDataQualityRecalcJob insert(
            BizDataQualityRecalcJob candidate) {
        validateCandidate(candidate);
        BizDataQualityRecalcJob existing =
                mapper.selectByIdempotencyKey(candidate.getIdempotencyKey());
        if (existing != null) {
            return existing;
        }

        initializeForInsert(candidate);
        try {
            if (mapper.insert(candidate) != 1) {
                throw new IllegalStateException("人工重算批次创建失败");
            }
            return candidate;
        } catch (DuplicateKeyException duplicate) {
            BizDataQualityRecalcJob concurrent =
                    mapper.selectByIdempotencyKeyForUpdate(
                            candidate.getIdempotencyKey());
            if (concurrent == null) {
                throw duplicate;
            }
            return concurrent;
        }
    }

    @Override
    public Optional<BizDataQualityRecalcJob> findById(String jobId) {
        return Optional.ofNullable(mapper.selectById(
                requireText(jobId, "jobId")));
    }

    @Override
    public Optional<BizDataQualityRecalcJob> findByIdempotencyKey(
            String key) {
        return Optional.ofNullable(mapper.selectByIdempotencyKey(
                requireText(key, "idempotencyKey")));
    }

    @Override
    @Transactional
    public List<BizDataQualityRecalcJob> findOverlappingForUpdate(
            String buildingId,
            LocalDateTime from,
            LocalDateTime to) {
        requirePeriod(from, to);
        return List.copyOf(mapper.selectOverlappingForUpdate(
                requireText(buildingId, "buildingId"), from, to));
    }

    @Override
    public IPage<BizDataQualityRecalcJob> findPage(
            int pageNum,
            int pageSize,
            String buildingId,
            RecalculationJobType type,
            RecalculationJobStatus status,
            LocalDateTime from,
            LocalDateTime to) {
        if (pageNum < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("分页参数超出允许范围");
        }
        if (from != null && to != null && !to.isAfter(from)) {
            throw new IllegalArgumentException("查询结束时间必须晚于开始时间");
        }
        String normalizedBuilding = trimToNull(buildingId);
        return Objects.requireNonNull(mapper.selectPageFiltered(
                new Page<>(pageNum, pageSize),
                normalizedBuilding,
                type == null ? null : type.name(),
                status == null ? null : status.name(),
                from,
                to), "重算批次分页结果不能为空");
    }

    @Override
    public List<BizDataQualityRecalcJob> findClaimable(
            LocalDateTime staleBefore,
            int limit) {
        Objects.requireNonNull(staleBefore, "staleBefore 不能为空");
        requireLimit(limit);
        return List.copyOf(mapper.selectClaimable(staleBefore, limit));
    }

    /**
     * 返回 false 表示另一个执行者已经领取或任务状态发生变化，属于正常并发结果。
     */
    @Override
    public boolean claim(
            String jobId,
            LocalDateTime staleBefore,
            LocalDateTime now) {
        requireText(jobId, "jobId");
        Objects.requireNonNull(staleBefore, "staleBefore 不能为空");
        Objects.requireNonNull(now, "now 不能为空");
        return mapper.claimAtomic(jobId, staleBefore, now) == 1;
    }

    @Override
    public void resumeFailed(String jobId) {
        String normalizedJobId = requireText(jobId, "jobId");
        if (mapper.resumeFailedAtomic(normalizedJobId) != 1) {
            throw new IllegalStateException(
                    "只有 FAILED 人工重算批次允许恢复");
        }
    }

    @Override
    public void freezeVoidTargets(
            String jobId,
            String targetMinutesJson) {
        String normalizedJobId = requireText(jobId, "jobId");
        String normalizedTargets =
                requireText(targetMinutesJson, "targetMinutesJson");
        if (mapper.freezeVoidTargetsAtomic(
                normalizedJobId, normalizedTargets) != 1) {
            throw new IllegalStateException(
                    "作废目标只能由 RUNNING/VOIDING 批次冻结一次");
        }
    }

    @Override
    public void completeVoid(
            String jobId,
            int voidedCount,
            int replacedCount) {
        String normalizedJobId = requireText(jobId, "jobId");
        requireNonNegative(voidedCount, "voidedCount");
        requireNonNegative(replacedCount, "replacedCount");
        if (mapper.completeVoidAtomic(
                normalizedJobId, voidedCount, replacedCount) != 1) {
            throw new IllegalStateException(
                    "作废阶段状态已变化，不能重复收口");
        }
    }

    @Override
    public void advanceChunk(
            String jobId,
            LocalDateTime expectedCursor,
            LocalDateTime nextCursor,
            RecalculationChunkStats stats,
            boolean finished,
            LocalDateTime at) {
        String normalizedJobId = requireText(jobId, "jobId");
        Objects.requireNonNull(expectedCursor, "expectedCursor 不能为空");
        Objects.requireNonNull(nextCursor, "nextCursor 不能为空");
        Objects.requireNonNull(stats, "stats 不能为空");
        Objects.requireNonNull(at, "at 不能为空");
        if (!nextCursor.isAfter(expectedCursor)) {
            throw new IllegalArgumentException(
                    "nextCursor 必须晚于 expectedCursor");
        }
        int updated = mapper.advanceChunkAtomic(
                normalizedJobId,
                expectedCursor,
                nextCursor,
                stats.q0Count(),
                stats.q1Count(),
                stats.q2Count(),
                stats.missingCount(),
                finished,
                at);
        if (updated != 1) {
            throw new IllegalStateException(
                    "重算批次游标已变化，拒绝重复推进");
        }
    }

    @Override
    public void markFailed(
            String jobId,
            LocalDateTime expectedCursor,
            String error) {
        String normalizedJobId = requireText(jobId, "jobId");
        Objects.requireNonNull(expectedCursor, "expectedCursor 不能为空");
        String normalizedError = requireText(error, "error");
        if (normalizedError.length() > MAX_ERROR_LENGTH) {
            normalizedError = normalizedError.substring(0, MAX_ERROR_LENGTH);
        }
        if (mapper.markFailedAtomic(
                normalizedJobId, expectedCursor, normalizedError) != 1) {
            throw new IllegalStateException(
                    "重算批次状态或游标已变化，不能写入失败状态");
        }
    }

    private void validateCandidate(BizDataQualityRecalcJob candidate) {
        Objects.requireNonNull(candidate, "candidate 不能为空");
        String key = requireText(
                candidate.getIdempotencyKey(), "idempotencyKey");
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "idempotencyKey 不能超过 160 个字符");
        }
        requireText(candidate.getBuildingId(), "buildingId");
        requireText(candidate.getPointIdsJson(), "pointIdsJson");
        requirePeriod(candidate.getFromMinute(), candidate.getToMinute());
        String reason = requireText(candidate.getReason(), "reason");
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("reason 不能超过 500 个字符");
        }
        if (candidate.getOperatorId() == null
                || candidate.getOperatorId() <= 0L) {
            throw new IllegalArgumentException("operatorId 必须大于 0");
        }
        Objects.requireNonNull(candidate.getJobType(), "jobType 不能为空");
        Objects.requireNonNull(candidate.getStatus(), "status 不能为空");
        Objects.requireNonNull(candidate.getPhase(), "phase 不能为空");
        LocalDateTime cursor = Objects.requireNonNull(
                candidate.getCursorMinute(), "cursorMinute 不能为空");
        if (cursor.isBefore(candidate.getFromMinute())
                || cursor.isAfter(candidate.getToMinute())) {
            throw new IllegalArgumentException(
                    "cursorMinute 必须位于重算时间范围内");
        }
    }

    private void initializeForInsert(BizDataQualityRecalcJob candidate) {
        if (candidate.getJobId() == null
                || candidate.getJobId().isBlank()) {
            candidate.setJobId(
                    UUID.randomUUID().toString().replace("-", ""));
        }
        candidate.setQ0Count(defaultZero(candidate.getQ0Count()));
        candidate.setQ1Count(defaultZero(candidate.getQ1Count()));
        candidate.setQ2Count(defaultZero(candidate.getQ2Count()));
        candidate.setMissingCount(defaultZero(candidate.getMissingCount()));
        candidate.setVoidedCount(defaultZero(candidate.getVoidedCount()));
        candidate.setReplacedCount(defaultZero(candidate.getReplacedCount()));
    }

    private void requirePeriod(
            LocalDateTime from,
            LocalDateTime to) {
        Objects.requireNonNull(from, "from 不能为空");
        Objects.requireNonNull(to, "to 不能为空");
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("to 必须晚于 from");
        }
    }

    private void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_BATCH_LIMIT) {
            throw new IllegalArgumentException(
                    "limit 必须在 1 到 100 之间");
        }
    }

    private void requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " 不能为负数");
        }
    }

    private int defaultZero(Integer value) {
        if (value == null) {
            return 0;
        }
        if (value < 0) {
            throw new IllegalArgumentException("重算计数不能为负数");
        }
        return value;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String requireText(String value, String field) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return normalized;
    }
}
