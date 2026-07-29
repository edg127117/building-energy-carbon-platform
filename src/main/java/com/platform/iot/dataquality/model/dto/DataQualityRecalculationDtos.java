package com.platform.iot.dataquality.model.dto;

import com.platform.iot.dataquality.model.RecalculationJobPhase;
import com.platform.iot.dataquality.model.RecalculationJobStatus;
import com.platform.iot.dataquality.model.RecalculationJobType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 异步人工重算批次的请求、进度和详情视图。
 *
 * <p>接口时间统一使用 Unix 毫秒，任务范围采用
 * {@code [fromInclusive,toExclusive)}；MySQL 的本地时间和 JSON 审计字段不会
 * 直接暴露给管理端。</p>
 */
public final class DataQualityRecalculationDtos {

    private DataQualityRecalculationDtos() {
    }

    /**
     * 平台管理员按建筑提交的范围重算请求。
     *
     * <p>测点会在服务端去空白、去重并排序，最多 100 个；起止时间必须对齐到
     * 已结束的自然分钟，原因用于幂等键和人工审计。</p>
     */
    public record RecalculateRequest(
            @NotBlank String buildingId,
            @NotEmpty @Size(max = 100) List<@NotBlank String> pointIds,
            @NotNull Long fromInclusive,
            @NotNull Long toExclusive,
            @NotBlank @Size(max = 500) String reason) {
    }

    /**
     * 批次进度视图；Q0/Q1/Q2/缺失计数只统计已经成功推进游标的目标分钟。
     */
    public record Response(
            String jobId,
            RecalculationJobType jobType,
            String buildingId,
            List<String> pointIds,
            long fromInclusive,
            long toExclusive,
            String supersedesTaskId,
            String reason,
            long operatorId,
            RecalculationJobStatus status,
            RecalculationJobPhase phase,
            long cursorMinute,
            int q0Count,
            int q1Count,
            int q2Count,
            int missingCount,
            int voidedCount,
            int replacedCount,
            String lastError,
            Long startedAt,
            Long finishedAt,
            long createTime,
            long updateTime) {
    }

    /**
     * 批次详情连同本批次生成的 Q1/Q2 补全任务，便于追溯数据来源和证据。
     */
    public record Detail(
            Response job,
            List<DataQualityFillDtos.Response> childTasks) {
    }
}
