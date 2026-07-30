package com.platform.iot.dataquality.model.dto;

import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.FillSourceType;
import com.platform.iot.dataquality.model.FillTaskEvidence;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 补全任务查询和异常作废接口使用的稳定模型。
 *
 * <p>响应显式列出允许公开的审计字段，不返回失败堆栈或持久化实体；所有时间使用
 * Unix 毫秒，区间统一采用左闭右开语义。</p>
 */
public final class DataQualityFillDtos {

    private DataQualityFillDtos() {
    }

    /** 异常作废必须留下可审计原因。 */
    public record VoidAndRecalculateRequest(
            @NotBlank @Size(max = 500) String reason) {
    }

    /** 补全任务的审计视图；evidence 已通过强类型编解码器验证。 */
    public record Response(
            String taskId,
            String idempotencyKey,
            String buildingId,
            String pointId,
            Long startMinute,
            Long endMinute,
            Integer minuteCount,
            Integer dataQuality,
            FillSourceType sourceType,
            String algorithmVersion,
            FillTaskEvidence evidence,
            List<FillTaskEvidence.MinuteSegment> actualSegments,
            String typicalConfigId,
            Integer typicalConfigVersion,
            FillApplyStatus applyStatus,
            Integer appliedCount,
            Integer failedCount,
            Integer replacedCount,
            Integer voidedCount,
            Integer retryCount,
            String lastError,
            Long generatedAt,
            Long closedAt,
            Long voidBy,
            String voidReason,
            Long voidAt,
            String supersedesTaskId,
            Long createTime,
            Long updateTime) {
    }

}
