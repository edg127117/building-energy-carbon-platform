package com.platform.iot.dataquality.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 小时批次收口时一次性写回 MySQL 的实际应用统计。
 *
 * <p>运行期不会为质量 2 每分钟更新一次 MySQL 计数；收口任务依据 TDengine
 * 真实结果重建这些统计和区间，降低高频写入对业务库的压力。</p>
 */
public record TaskReconciliation(
        String taskId,
        int minuteCount,
        int appliedCount,
        int failedCount,
        int replacedCount,
        int voidedCount,
        FillApplyStatus applyStatus,
        List<FillTaskEvidence.MinuteSegment> appliedSegments,
        LocalDateTime closedAt) {

    public TaskReconciliation {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        if (minuteCount < 0 || appliedCount < 0 || failedCount < 0
                || replacedCount < 0 || voidedCount < 0) {
            throw new IllegalArgumentException("补全任务统计不能为负数");
        }
        Objects.requireNonNull(applyStatus, "applyStatus 不能为空");
        if (applyStatus == FillApplyStatus.WAITING
                || applyStatus == FillApplyStatus.VOIDED) {
            throw new IllegalArgumentException(
                    "收口状态只能是 APPLIED、FAILED 或 REPLACED");
        }
        appliedSegments = List.copyOf(
                Objects.requireNonNull(appliedSegments, "appliedSegments 不能为空"));
        Objects.requireNonNull(closedAt, "closedAt 不能为空");
    }
}
