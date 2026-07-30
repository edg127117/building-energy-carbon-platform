package com.platform.iot.dataquality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.platform.iot.dataquality.model.entity.BizDataQualityRecalcJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 人工重算批次的 MySQL 数据访问入口。
 *
 * <p>所有状态推进都带当前状态、阶段或游标谓词，调用方必须检查更新行数，避免
 * 多实例同时执行同一分块或用过期游标覆盖已完成进度。</p>
 */
@Mapper
public interface BizDataQualityRecalcJobMapper
        extends BaseMapper<BizDataQualityRecalcJob> {

    @Select("""
            SELECT *
            FROM biz_data_quality_recalc_job
            WHERE idempotency_key = #{idempotencyKey}
            LIMIT 1
            """)
    BizDataQualityRecalcJob selectByIdempotencyKey(
            @Param("idempotencyKey") String idempotencyKey);

    /**
     * 唯一键并发冲突后使用当前读取得另一事务刚提交的同一批次。
     */
    @Select("""
            SELECT *
            FROM biz_data_quality_recalc_job
            WHERE idempotency_key = #{idempotencyKey}
            LIMIT 1
            FOR UPDATE
            """)
    BizDataQualityRecalcJob selectByIdempotencyKeyForUpdate(
            @Param("idempotencyKey") String idempotencyKey);

    /**
     * 提交服务先锁建筑父记录，再锁同建筑活动批次，保证重叠检查可串行化。
     */
    @Select("""
            SELECT *
            FROM biz_data_quality_recalc_job
            WHERE building_id = #{buildingId}
              AND status IN ('WAITING', 'RUNNING')
              AND to_minute > #{fromInclusive}
              AND from_minute < #{toExclusive}
            ORDER BY from_minute, job_id
            FOR UPDATE
            """)
    List<BizDataQualityRecalcJob> selectOverlappingForUpdate(
            @Param("buildingId") String buildingId,
            @Param("fromInclusive") LocalDateTime fromInclusive,
            @Param("toExclusive") LocalDateTime toExclusive);

    /**
     * 管理端列表在 MySQL 内完成稳定分页和半开区间重叠过滤。
     */
    @Select("""
            <script>
            SELECT *
            FROM biz_data_quality_recalc_job
            WHERE 1 = 1
            <if test="buildingId != null and buildingId != ''">
              AND building_id = #{buildingId}
            </if>
            <if test="jobType != null and jobType != ''">
              AND job_type = #{jobType}
            </if>
            <if test="status != null and status != ''">
              AND status = #{status}
            </if>
            <if test="fromInclusive != null">
              AND to_minute &gt; #{fromInclusive}
            </if>
            <if test="toExclusive != null">
              AND from_minute &lt; #{toExclusive}
            </if>
            ORDER BY create_time DESC, job_id DESC
            </script>
            """)
    IPage<BizDataQualityRecalcJob> selectPageFiltered(
            IPage<BizDataQualityRecalcJob> page,
            @Param("buildingId") String buildingId,
            @Param("jobType") String jobType,
            @Param("status") String status,
            @Param("fromInclusive") LocalDateTime fromInclusive,
            @Param("toExclusive") LocalDateTime toExclusive);

    /**
     * WAITING 和超时 RUNNING 共用一个有界领取候选列表；最终所有权由 claimAtomic 决定。
     */
    @Select("""
            SELECT *
            FROM biz_data_quality_recalc_job
            WHERE status = 'WAITING'
               OR (status = 'RUNNING' AND update_time <= #{staleBefore})
            ORDER BY update_time, job_id
            LIMIT #{limit}
            """)
    List<BizDataQualityRecalcJob> selectClaimable(
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("limit") int limit);

    @Update("""
            UPDATE biz_data_quality_recalc_job
            SET status = 'RUNNING',
                started_at = COALESCE(started_at, #{now}),
                last_error = NULL,
                update_time = #{now}
            WHERE job_id = #{jobId}
              AND (
                  status = 'WAITING'
                  OR (status = 'RUNNING' AND update_time <= #{staleBefore})
              )
            """)
    int claimAtomic(
            @Param("jobId") String jobId,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE biz_data_quality_recalc_job
            SET status = 'WAITING',
                last_error = NULL,
                finished_at = NULL,
                update_time = CURRENT_TIMESTAMP(3)
            WHERE job_id = #{jobId}
              AND status = 'FAILED'
            """)
    int resumeFailedAtomic(@Param("jobId") String jobId);

    @Update("""
            UPDATE biz_data_quality_recalc_job
            SET void_target_minutes_json = #{targetMinutesJson},
                update_time = CURRENT_TIMESTAMP(3)
            WHERE job_id = #{jobId}
              AND status = 'RUNNING'
              AND phase = 'VOIDING'
              AND void_target_minutes_json IS NULL
            """)
    int freezeVoidTargetsAtomic(
            @Param("jobId") String jobId,
            @Param("targetMinutesJson") String targetMinutesJson);

    @Update("""
            UPDATE biz_data_quality_recalc_job
            SET phase = 'RECALCULATING',
                voided_count = #{voidedCount},
                replaced_count = #{replacedCount},
                update_time = CURRENT_TIMESTAMP(3)
            WHERE job_id = #{jobId}
              AND status = 'RUNNING'
              AND phase = 'VOIDING'
              AND void_target_minutes_json IS NOT NULL
            """)
    int completeVoidAtomic(
            @Param("jobId") String jobId,
            @Param("voidedCount") int voidedCount,
            @Param("replacedCount") int replacedCount);

    @Update("""
            UPDATE biz_data_quality_recalc_job
            SET cursor_minute = #{nextCursor},
                q0_count = q0_count + #{q0},
                q1_count = q1_count + #{q1},
                q2_count = q2_count + #{q2},
                missing_count = missing_count + #{missing},
                status = CASE
                    WHEN #{finished} = TRUE THEN 'SUCCEEDED'
                    ELSE 'WAITING'
                END,
                finished_at = CASE
                    WHEN #{finished} = TRUE THEN #{at}
                    ELSE NULL
                END,
                update_time = #{at}
            WHERE job_id = #{jobId}
              AND status = 'RUNNING'
              AND phase = 'RECALCULATING'
              AND cursor_minute = #{expectedCursor}
            """)
    int advanceChunkAtomic(
            @Param("jobId") String jobId,
            @Param("expectedCursor") LocalDateTime expectedCursor,
            @Param("nextCursor") LocalDateTime nextCursor,
            @Param("q0") int q0,
            @Param("q1") int q1,
            @Param("q2") int q2,
            @Param("missing") int missing,
            @Param("finished") boolean finished,
            @Param("at") LocalDateTime at);

    @Update("""
            UPDATE biz_data_quality_recalc_job
            SET status = 'FAILED',
                last_error = #{lastError},
                update_time = CURRENT_TIMESTAMP(3)
            WHERE job_id = #{jobId}
              AND status = 'RUNNING'
              AND cursor_minute = #{expectedCursor}
            """)
    int markFailedAtomic(
            @Param("jobId") String jobId,
            @Param("expectedCursor") LocalDateTime expectedCursor,
            @Param("lastError") String lastError);
}
