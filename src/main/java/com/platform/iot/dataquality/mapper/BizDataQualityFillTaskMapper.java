package com.platform.iot.dataquality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * MySQL 补全任务的数据访问接口。
 *
 * <p>状态和计数通过单条 SQL 原子更新；需要同时读取 JSON 证据的操作先在事务内
 * 使用 {@code FOR UPDATE} 锁定目标行，避免多个恢复任务互相覆盖审计信息。</p>
 */
@Mapper
public interface BizDataQualityFillTaskMapper
        extends BaseMapper<BizDataQualityFillTask> {

    /**
     * 在 MySQL 完成建筑范围、来源、状态和半开时间区间过滤后再分页。
     *
     * <p>普通角色没有建筑授权时显式返回空集，不能因为 IN 集合为空而退化成全量查询。</p>
     */
    @Select("""
            <script>
            SELECT *
            FROM biz_data_quality_fill_task
            WHERE 1 = 1
            <if test="allBuildings == false">
              <choose>
                <when test="buildingIds != null and !buildingIds.isEmpty()">
                  AND building_id IN
                  <foreach collection="buildingIds" item="scopeBuildingId"
                           open="(" separator="," close=")">
                    #{scopeBuildingId}
                  </foreach>
                </when>
                <otherwise>
                  AND 1 = 0
                </otherwise>
              </choose>
            </if>
            <if test="buildingId != null and buildingId != ''">
              AND building_id = #{buildingId}
            </if>
            <if test="pointId != null and pointId != ''">
              AND point_id = #{pointId}
            </if>
            <if test="sourceType != null">
              AND source_type = #{sourceType}
            </if>
            <if test="dataQuality != null">
              AND data_quality = #{dataQuality}
            </if>
            <if test="applyStatus != null">
              AND apply_status = #{applyStatus}
            </if>
            <if test="fromInclusive != null">
              AND end_minute &gt; #{fromInclusive}
            </if>
            <if test="toExclusive != null">
              AND start_minute &lt; #{toExclusive}
            </if>
            ORDER BY generated_at DESC, task_id DESC
            </script>
            """)
    IPage<BizDataQualityFillTask> selectPageFiltered(
            IPage<BizDataQualityFillTask> page,
            @Param("allBuildings") boolean allBuildings,
            @Param("buildingIds") Collection<String> buildingIds,
            @Param("buildingId") String buildingId,
            @Param("pointId") String pointId,
            @Param("sourceType") String sourceType,
            @Param("dataQuality") Integer dataQuality,
            @Param("applyStatus") String applyStatus,
            @Param("fromInclusive") LocalDateTime fromInclusive,
            @Param("toExclusive") LocalDateTime toExclusive);

    @Select("""
            SELECT *
            FROM biz_data_quality_fill_task
            WHERE recalc_job_id = #{jobId}
            ORDER BY generated_at, task_id
            """)
    List<BizDataQualityFillTask> selectByRecalculationJobId(
            @Param("jobId") String jobId);

    @Select("""
            SELECT *
            FROM biz_data_quality_fill_task
            WHERE idempotency_key = #{idempotencyKey}
            LIMIT 1
            """)
    BizDataQualityFillTask selectByIdempotencyKey(
            @Param("idempotencyKey") String idempotencyKey);

    /**
     * 唯一键并发冲突后使用锁定读获取另一事务刚提交的任务。
     *
     * <p>在 MySQL REPEATABLE READ 下，普通快照读可能仍看不到并发事务的新行；
     * {@code FOR UPDATE} 属于当前读，既能看到已提交结果，也会等待仍在提交中的事务。</p>
     */
    @Select("""
            SELECT *
            FROM biz_data_quality_fill_task
            WHERE idempotency_key = #{idempotencyKey}
            LIMIT 1
            FOR UPDATE
            """)
    BizDataQualityFillTask selectByIdempotencyKeyForUpdate(
            @Param("idempotencyKey") String idempotencyKey);

    /**
     * 调用方必须位于 MySQL 事务中，锁用于合并失败分钟或重建证据区间。
     */
    @Select("""
            SELECT *
            FROM biz_data_quality_fill_task
            WHERE task_id = #{taskId}
            FOR UPDATE
            """)
    BizDataQualityFillTask selectByTaskIdForUpdate(
            @Param("taskId") String taskId);

    /**
     * 仅第一次成功把技术状态从 WAITING 推进到 APPLIED。
     * 后续每分钟成功不会高频更新业务库计数，计数由收口任务统一重建。
     */
    @Update("""
            UPDATE biz_data_quality_fill_task
            SET apply_status = 'APPLIED',
                update_time = CURRENT_TIMESTAMP(3)
            WHERE task_id = #{taskId}
              AND apply_status = 'WAITING'
            """)
    int markFirstApplied(@Param("taskId") String taskId);

    /**
     * 迟到 Q0 写入成功后只原子累加替换计数，小时收口任务再统一重建最终状态。
     */
    @Update("""
            UPDATE biz_data_quality_fill_task
            SET replaced_count = LEAST(
                    TIMESTAMPDIFF(MINUTE, start_minute, end_minute),
                    replaced_count + #{increment}),
                closed_at = NULL,
                update_time = CURRENT_TIMESTAMP(3)
            WHERE task_id = #{taskId}
              AND apply_status <> 'VOIDED'
            """)
    int incrementReplacedCountAtomic(
            @Param("taskId") String taskId,
            @Param("increment") int increment);

    /**
     * 写 TDengine 失败后立即保存失败分钟证据，但不增加 retry_count。
     * retry_count 只在恢复服务真正发起重试时另行更新。
     */
    @Update("""
            UPDATE biz_data_quality_fill_task
            SET apply_status = 'FAILED',
                failed_minutes_json = #{failedMinutesJson},
                failed_count = #{failedCount},
                last_error = #{lastError},
                update_time = CURRENT_TIMESTAMP(3)
            WHERE task_id = #{taskId}
              AND apply_status IN ('WAITING', 'APPLIED', 'FAILED')
            """)
    int recordFailureAtomic(
            @Param("taskId") String taskId,
            @Param("failedMinutesJson") String failedMinutesJson,
            @Param("failedCount") int failedCount,
            @Param("lastError") String lastError);

    /**
     * 失败队列按 update_time 退避，只取固定批量，避免坏任务造成无界扫描。
     */
    @Select("""
            SELECT *
            FROM biz_data_quality_fill_task
            WHERE apply_status = 'FAILED'
              AND update_time <= #{updatedBefore}
              AND source_type IN ('TYPICAL_VALUE', 'INTERPOLATION')
            ORDER BY update_time, task_id
            LIMIT #{limit}
            """)
    List<BizDataQualityFillTask> selectRetryable(
            @Param("updatedBefore") LocalDateTime updatedBefore,
            @Param("limit") int limit);

    /**
     * 只返回 task_id，避免未知枚举字符串在 MyBatis 实体映射阶段中断整批恢复。
     */
    @Select("""
            SELECT task_id
            FROM biz_data_quality_fill_task
            WHERE apply_status = 'FAILED'
              AND update_time <= #{updatedBefore}
              AND (
                  source_type IS NULL
                  OR source_type NOT IN ('TYPICAL_VALUE', 'INTERPOLATION')
              )
            ORDER BY update_time, task_id
            LIMIT #{limit}
            """)
    List<String> selectInvalidSourceRetryableTaskIds(
            @Param("updatedBefore") LocalDateTime updatedBefore,
            @Param("limit") int limit);

    @Select("""
            SELECT *
            FROM biz_data_quality_fill_task
            WHERE source_type = 'INTERPOLATION'
              AND apply_status = 'WAITING'
              AND closed_at IS NULL
              AND update_time <= #{updatedBefore}
            ORDER BY update_time, task_id
            LIMIT #{limit}
            """)
    List<BizDataQualityFillTask> selectWaitingInterpolationTasks(
            @Param("updatedBefore") LocalDateTime updatedBefore,
            @Param("limit") int limit);

    @Select("""
            SELECT *
            FROM biz_data_quality_fill_task
            WHERE source_type = 'TYPICAL_VALUE'
              AND end_minute <= #{hourEndedBefore}
              AND closed_at IS NULL
              AND apply_status IN ('WAITING', 'APPLIED', 'FAILED', 'REPLACED')
            ORDER BY end_minute, task_id
            LIMIT #{limit}
            """)
    List<BizDataQualityFillTask> selectTypicalTasksToClose(
            @Param("hourEndedBefore") LocalDateTime hourEndedBefore,
            @Param("limit") int limit);

    @Select("""
            SELECT *
            FROM biz_data_quality_fill_task
            WHERE source_type = 'INTERPOLATION'
              AND apply_status IN ('APPLIED', 'REPLACED')
              AND closed_at IS NULL
              AND update_time <= #{updatedBefore}
            ORDER BY update_time, task_id
            LIMIT #{limit}
            """)
    List<BizDataQualityFillTask> selectInterpolationTasksToClose(
            @Param("updatedBefore") LocalDateTime updatedBefore,
            @Param("limit") int limit);

    @Update("""
            UPDATE biz_data_quality_fill_task
            SET retry_count = retry_count + 1,
                update_time = CURRENT_TIMESTAMP(3)
            WHERE task_id = #{taskId}
              AND apply_status = 'FAILED'
            """)
    int incrementRetryAtomic(@Param("taskId") String taskId);

    @Update("""
            UPDATE biz_data_quality_fill_task
            SET apply_status = 'FAILED',
                last_error = #{lastError},
                update_time = CURRENT_TIMESTAMP(3)
            WHERE task_id = #{taskId}
              AND apply_status = 'FAILED'
            """)
    int recordRetryErrorAtomic(
            @Param("taskId") String taskId,
            @Param("lastError") String lastError);

    @Update("""
            UPDATE biz_data_quality_fill_task
            SET apply_status = #{applyStatus},
                failed_count = 0,
                failed_minutes_json = NULL,
                last_error = NULL,
                replaced_count = LEAST(
                    TIMESTAMPDIFF(MINUTE, start_minute, end_minute),
                    replaced_count + #{ownReplacementIncrement}),
                closed_at = NULL,
                update_time = CURRENT_TIMESTAMP(3)
            WHERE task_id = #{taskId}
              AND apply_status IN ('FAILED', 'WAITING')
            """)
    int markRetryRecoveredAtomic(
            @Param("taskId") String taskId,
            @Param("applyStatus") FillApplyStatus applyStatus,
            @Param("ownReplacementIncrement") int ownReplacementIncrement);

    /**
     * 按 TDengine 实际结果一次性收口计数和典型值应用区间。
     */
    @Update("""
            UPDATE biz_data_quality_fill_task
            SET minute_count = #{minuteCount},
                applied_count = #{appliedCount},
                failed_count = #{failedCount},
                replaced_count = #{replacedCount},
                voided_count = #{voidedCount},
                apply_status = #{applyStatus},
                evidence_json = #{evidenceJson},
                closed_at = #{closedAt},
                update_time = CURRENT_TIMESTAMP(3)
            WHERE task_id = #{taskId}
              AND (
                  apply_status IN ('WAITING', 'APPLIED', 'FAILED')
                  OR (apply_status = 'REPLACED' AND #{applyStatus} = 'REPLACED')
              )
            """)
    int reconcileAtomic(
            @Param("taskId") String taskId,
            @Param("minuteCount") int minuteCount,
            @Param("appliedCount") int appliedCount,
            @Param("failedCount") int failedCount,
            @Param("replacedCount") int replacedCount,
            @Param("voidedCount") int voidedCount,
            @Param("applyStatus") FillApplyStatus applyStatus,
            @Param("evidenceJson") String evidenceJson,
            @Param("closedAt") LocalDateTime closedAt);

    /**
     * 人工作废是少量异常处理入口，不是逐条审批状态。
     */
    @Update("""
            UPDATE biz_data_quality_fill_task
            SET apply_status = 'VOIDED',
                voided_count = minute_count,
                void_by = #{operatorId},
                void_reason = #{reason},
                void_at = #{voidAt},
                closed_at = COALESCE(closed_at, #{voidAt}),
                update_time = CURRENT_TIMESTAMP(3)
            WHERE task_id = #{taskId}
              AND apply_status <> 'VOIDED'
            """)
    int markVoidedAtomic(
            @Param("taskId") String taskId,
            @Param("operatorId") long operatorId,
            @Param("reason") String reason,
            @Param("voidAt") LocalDateTime voidAt);
}
