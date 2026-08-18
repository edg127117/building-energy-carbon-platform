package com.platform.iot.onboarding.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** 产品模板管理 API 的独立请求和响应契约。 */
public final class DeviceProductContracts {
    private DeviceProductContracts() {
    }

    @Schema(description = "创建产品草稿")
    public record CreateRequest(
            @NotBlank @Size(max = 50) String productCode,
            @NotBlank @Size(max = 100) String productName,
            @Size(max = 100) String manufacturer,
            @Size(max = 100) String model,
            @NotBlank @Size(max = 20) String equipmentTypeCode,
            @NotBlank @Size(max = 50) String expectedProfileCode,
            @NotBlank @Size(max = 20) String identityType,
            @NotEmpty List<@Valid PointTemplateRequest> points) {
    }

    @Schema(description = "更新仍为草稿且未被设备使用的产品")
    public record UpdateRequest(
            @NotBlank @Size(max = 100) String productName,
            @Size(max = 100) String manufacturer,
            @Size(max = 100) String model,
            @NotBlank @Size(max = 20) String equipmentTypeCode,
            @NotBlank @Size(max = 50) String expectedProfileCode,
            @NotBlank @Size(max = 20) String identityType,
            @NotEmpty List<@Valid PointTemplateRequest> points) {
    }

    @Schema(description = "复制为新的产品草稿")
    public record CopyRequest(
            @NotBlank @Size(max = 50) String productCode,
            @NotBlank @Size(max = 100) String productName) {
    }

    @Schema(description = "产品测点模板")
    public record PointTemplateRequest(
            @NotBlank @Size(max = 100) String metricCode,
            @NotBlank @Size(max = 100) String pointNameTemplate,
            @NotBlank @Size(max = 20) String suffixCode,
            @NotBlank @Size(max = 20) String unit,
            BigDecimal minValue,
            BigDecimal maxValue,
            @NotNull Boolean forCalc,
            @NotNull Boolean required,
            @NotNull Integer sortOrder,
            @NotNull Boolean enabled) {
    }

    @Schema(description = "产品列表项")
    public record ListItemView(
            String productId,
            String productCode,
            String productName,
            String manufacturer,
            String model,
            String equipmentTypeCode,
            String expectedProfileCode,
            String identityType,
            String status,
            int pointCount,
            long updateTime) {
    }

    @Schema(description = "产品详情")
    public record DetailView(
            String productId,
            String productCode,
            String productName,
            String manufacturer,
            String model,
            String equipmentTypeCode,
            String expectedProfileCode,
            String identityType,
            String status,
            List<PointTemplateView> points,
            List<String> allowedActions,
            long createTime,
            long updateTime) {
    }

    @Schema(description = "产品测点模板详情")
    public record PointTemplateView(
            String templatePointId,
            String metricCode,
            String pointNameTemplate,
            String suffixCode,
            String unit,
            BigDecimal minValue,
            BigDecimal maxValue,
            boolean forCalc,
            boolean required,
            int sortOrder,
            boolean enabled) {
    }
}
