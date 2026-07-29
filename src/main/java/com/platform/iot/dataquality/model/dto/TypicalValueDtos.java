package com.platform.iot.dataquality.model.dto;

import com.platform.iot.dataquality.model.TypicalValueStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 典型值配置管理接口的请求和响应模型。
 *
 * <p>请求只接收业务人员可维护的字段；响应显式列出允许公开的审批和版本信息，
 * 不直接返回 MySQL Entity，避免后续持久化字段变化意外扩大 API 契约。</p>
 */
public final class TypicalValueDtos {

    private TypicalValueDtos() {
    }

    /** 创建草稿，版本、状态、单位和创建人由服务端冻结。 */
    public record CreateRequest(
            @NotBlank String pointId,
            @NotNull BigDecimal typicalValue,
            @NotBlank @Size(max = 500) String sourceDescription,
            @NotBlank @Size(max = 500) String reason,
            @NotNull Long validFrom,
            Long validTo) {
    }

    /** 修改草稿；测点和版本不可通过修改接口变更。 */
    public record UpdateRequest(
            @NotNull BigDecimal typicalValue,
            @NotBlank @Size(max = 500) String sourceDescription,
            @NotBlank @Size(max = 500) String reason,
            @NotNull Long validFrom,
            Long validTo) {
    }

    /** 平台管理员的审批意见；批准和拒绝都必须留下可审计说明。 */
    public record ReviewRequest(@NotBlank @Size(max = 500) String comment) {
    }

    /** 停用已批准配置时记录的业务原因。 */
    public record DisableRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /**
     * 典型值配置查询视图。
     *
     * <p>所有时间均为 Unix 毫秒，调用方无需了解 MySQL 使用的本地时间类型。</p>
     */
    public record Response(
            String configId,
            String pointId,
            String buildingId,
            BigDecimal typicalValue,
            String unit,
            String sourceDescription,
            String reason,
            Long validFrom,
            Long validTo,
            TypicalValueStatus status,
            Integer version,
            Long createdBy,
            Long submittedAt,
            Long reviewerId,
            String reviewComment,
            Long reviewedAt,
            Long disabledBy,
            String disabledReason,
            Long disabledAt,
            Long createTime,
            Long updateTime) {
    }
}
