package com.platform.system.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Date;

/** 建筑查看权限申请和审核流程使用的请求与响应模型。 */
public final class BuildingAccessDtos {
    private BuildingAccessDtos() {}

    /** 用户提交建筑访问申请，必须说明目标建筑和申请原因。 */
    public record SubmitRequest(@NotBlank String buildingId,
                                @NotBlank @Size(max=500) String reason) {}
    /** 平台管理员审批或拒绝时填写的可选意见。 */
    public record ReviewRequest(@Size(max=500) String comment) {}
    /** 申请详情视图，同时返回用户、建筑和审核轨迹，便于管理端直接展示。 */
    public record RequestView(Long id, Long userId, String username, String buildingId,
                              String buildingName, String reason, String status,
                              Long reviewerId, String reviewComment, Date reviewTime,
                              Date createTime) {}
}
