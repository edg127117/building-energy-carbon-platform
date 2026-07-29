package com.platform.iot.dataquality;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.platform.iot.dataquality.model.RecalculationChunkStats;
import com.platform.iot.dataquality.model.RecalculationJobStatus;
import com.platform.iot.dataquality.model.RecalculationJobType;
import com.platform.iot.dataquality.model.entity.BizDataQualityRecalcJob;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 人工重算批次的 MySQL 业务仓储边界。
 *
 * <p>状态领取、作废阶段收口和分块游标推进都通过条件 SQL 完成；业务服务不直接
 * 访问 Mapper，也不能先读后写普通更新冒充并发保护。</p>
 */
public interface RecalculationJobRepository {

    BizDataQualityRecalcJob insert(BizDataQualityRecalcJob candidate);

    Optional<BizDataQualityRecalcJob> findById(String jobId);

    Optional<BizDataQualityRecalcJob> findByIdempotencyKey(String key);

    List<BizDataQualityRecalcJob> findOverlappingForUpdate(
            String buildingId, LocalDateTime from, LocalDateTime to);

    IPage<BizDataQualityRecalcJob> findPage(
            int pageNum,
            int pageSize,
            String buildingId,
            RecalculationJobType type,
            RecalculationJobStatus status,
            LocalDateTime from,
            LocalDateTime to);

    List<BizDataQualityRecalcJob> findClaimable(
            LocalDateTime staleBefore, int limit);

    boolean claim(
            String jobId,
            LocalDateTime staleBefore,
            LocalDateTime now);

    void resumeFailed(String jobId);

    void freezeVoidTargets(String jobId, String targetMinutesJson);

    void completeVoid(String jobId, int voidedCount, int replacedCount);

    void advanceChunk(
            String jobId,
            LocalDateTime expectedCursor,
            LocalDateTime nextCursor,
            RecalculationChunkStats stats,
            boolean finished,
            LocalDateTime at);

    void markFailed(
            String jobId,
            LocalDateTime expectedCursor,
            String error);
}
