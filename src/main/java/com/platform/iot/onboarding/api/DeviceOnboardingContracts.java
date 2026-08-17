package com.platform.iot.onboarding.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 待绑定查询、绑定和身份启停 API 的独立契约。 */
public final class DeviceOnboardingContracts {
    private DeviceOnboardingContracts() {
    }

    @Schema(description = "待绑定列表项；身份值已脱敏")
    public record PendingListItemView(
            String pendingId,
            String identityType,
            String maskedIdentityValue,
            String profileCode,
            int lastProfileVersion,
            String status,
            long reportCount,
            long firstSeenTime,
            long lastSeenTime,
            boolean sampleTruncated) {
    }

    @Schema(description = "授权管理员可读取的待绑定详情")
    public record PendingDetailView(
            String pendingId,
            String identityType,
            String identityValue,
            String profileCode,
            int lastProfileVersion,
            String status,
            String boundIdentityId,
            long reportCount,
            long firstSeenTime,
            long lastSeenTime,
            long latestEventTime,
            String latestTimeSource,
            JsonNode latestMetrics,
            boolean sampleTruncated,
            List<String> allowedActions) {
    }

    @Schema(description = "忽略或恢复待绑定状态")
    public record PendingStatusRequest(
            @NotBlank String status,
            @Size(max = 200) String reason) {
    }

    @Schema(description = "绑定请求；目标身份建筑最终从经校验的设备归属派生")
    public record BindRequest(
            @NotBlank String productId,
            @NotBlank String buildingId,
            @NotBlank String spaceId,
            @NotBlank String systemGroupId,
            String existingEquipmentId,
            @Valid NewEquipmentRequest newEquipment,
            @NotEmpty List<@Valid PointBindingRequest> pointBindings) {
    }

    @Schema(description = "新建设备台账")
    public record NewEquipmentRequest(
            @NotBlank @Size(max = 100) String equipmentName,
            @Size(max = 100) String manufacturer) {
    }

    @Schema(description = "一个标准指标到具体测点的明确映射")
    public record PointBindingRequest(
            @NotBlank @Size(max = 100) String metricCode,
            String existingPointId,
            @Size(max = 100) String pointCode,
            @Size(max = 100) String pointName,
            @Size(max = 32) String namingRuleId,
            @Size(max = 20) String familyCode,
            @Size(max = 20) String componentCode,
            @Size(max = 20) String dataType) {
    }

    @Schema(description = "绑定完成结果；身份仍保持停用")
    public record BindResultView(
            String pendingId,
            String identityId,
            String equipmentId,
            List<String> pointIds,
            String status,
            boolean configEffective) {
    }

    @Schema(description = "身份启停结果")
    public record IdentityStatusView(
            String identityId,
            String status,
            boolean configEffective) {
    }
}
