package com.platform.iot.dataquality;

import com.platform.iot.dataquality.model.TaskReconciliation;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 补全任务的 MySQL 业务仓储边界。
 *
 * <p>该仓储只管理任务、证据和跨库最终一致状态，不直接读写 TDengine，也不提供
 * 管理员逐条批准或拒绝生成数据的接口。</p>
 */
public interface FillTaskRepository {

    BizDataQualityFillTask getOrCreate(BizDataQualityFillTask candidate);

    Optional<BizDataQualityFillTask> findById(String taskId);

    Optional<BizDataQualityFillTask> findByIdForUpdate(String taskId);

    void markFirstApplied(String taskId);

    void recordFailure(String taskId, long minuteStart, String error);

    void reconcile(TaskReconciliation result);

    void markVoided(String taskId, long operatorId, String reason, LocalDateTime at);
}
