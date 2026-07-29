package com.platform.iot.dataquality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * MySQL 补全任务的数据访问接口。
 *
 * <p>状态和计数通过单条 SQL 原子更新；需要同时读取 JSON 证据的操作先在事务内
 * 使用 {@code FOR UPDATE} 锁定目标行，避免多个恢复任务互相覆盖审计信息。</p>
 */
@Mapper
public interface BizDataQualityFillTaskMapper
        extends BaseMapper<BizDataQualityFillTask> {

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
