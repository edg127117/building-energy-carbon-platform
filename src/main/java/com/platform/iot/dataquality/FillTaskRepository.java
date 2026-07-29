package com.platform.iot.dataquality;

import com.platform.iot.dataquality.model.TaskReconciliation;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    /**
     * 真实数据替换生成分钟后原子累加旧任务的替换计数。
     *
     * <p>禁止先读取旧计数再整体收口，否则多个迟到分钟会相互覆盖。</p>
     */
    void incrementReplacedCount(String taskId, int increment);

    void recordFailure(String taskId, long minuteStart, String error);

    List<BizDataQualityFillTask> findRetryable(
            LocalDateTime updatedBefore, int limit);

    List<String> findInvalidSourceRetryableTaskIds(
            LocalDateTime updatedBefore, int limit);

    List<BizDataQualityFillTask> findWaitingInterpolationTasks(
            LocalDateTime updatedBefore, int limit);

    List<BizDataQualityFillTask> findTypicalTasksToClose(
            LocalDateTime hourEndedBefore, int limit);

    /**
     * 查找已写入 Q1 但 MySQL 收口中断的任务。
     */
    List<BizDataQualityFillTask> findInterpolationTasksToClose(
            LocalDateTime updatedBefore, int limit);

    void incrementRetry(String taskId);

    void recordRetryError(String taskId, String error);

    void markRetryRecovered(
            String taskId,
            FillApplyStatus applyStatus,
            int ownReplacementIncrement);

    void recordReplacements(Map<String, Integer> countsByOldTaskId);

    void reconcile(TaskReconciliation result);

    void markVoided(String taskId, long operatorId, String reason, LocalDateTime at);
}
