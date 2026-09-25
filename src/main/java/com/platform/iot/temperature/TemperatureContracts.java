package com.platform.iot.temperature;

import com.platform.iot.onboarding.api.DeviceOnboardingContracts.TypedBindRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;

/** 自动与人工温度配置共享的预览、审批和批量进度契约；采样状态不代表历史已验收。 */
public final class TemperatureContracts {
    private TemperatureContracts() { }
    @Schema(name = "HvacTemperatureInput")
    public record Input(@NotBlank String pendingId, @NotBlank String mode, String templateProductId,
                        String numericSourceId, Map<String, String> existingPointIds, @Valid TypedBindRequest binding) {
        public Input { existingPointIds = existingPointIds == null ? Map.of() : Map.copyOf(existingPointIds); }
    }
    @Schema(name = "HvacTemperaturePreviewRequest")
    public record PreviewRequest(@NotEmpty @Size(max = 50) List<@NotNull @Valid Input> items) { }
    @Schema(name = "HvacTemperaturePointPlan")
    public record PointPlan(String metricCode, String semantic, String unit, String action,
                            String pointId, String pointCode, String pointName) { }
    @Schema(name = "HvacTemperaturePlanView")
    public record PlanView(String pendingId, String buildingId, String mode, String templateProductId,
                           String templateName, String numericSourceId, String status, String message,
                           String digest, long expiresAt, List<PointPlan> points) { }
    @Schema(name = "HvacTemperatureTemplateOption")
    public record TemplateOption(String productId, String productName) { }
    @Schema(name = "HvacTemperatureSourceOption")
    public record SourceOption(String sourceId, String sourceName) { }
    @Schema(name = "HvacTemperatureOptions")
    public record Options(List<TemplateOption> templates, List<SourceOption> numericSources, List<Rule> rules) { }
    @Schema(name = "HvacTemperatureRule")
    public record Rule(String ruleId, @NotBlank String adapterId, @NotBlank String buildingId,
                       String sourceScope, String model, @NotBlank String templateProductId,
                       @NotBlank String numericSourceId, int revision, boolean enabled) { }
    @Schema(name = "HvacTemperatureRuleRequest")
    public record RuleRequest(@NotNull @Valid Rule rule, @NotBlank @Size(max = 100) String idempotencyKey) { }
    @Schema(name = "HvacTemperatureApplication")
    public record Application(String requestId, String status) { }
    @Schema(name = "HvacTemperatureItem")
    public record Item(@NotBlank String pendingId, @NotBlank String mode, String templateProductId,
                       String numericSourceId, Map<String, String> existingPointIds, @NotBlank String digest) {
        public Input input() { return new Input(pendingId, mode, templateProductId, numericSourceId, existingPointIds, null); }
    }
    @Schema(name = "HvacTemperatureBatchRequest")
    public record BatchRequest(@NotBlank @Size(max = 100) String idempotencyKey,
                               @NotEmpty @Size(max = 50) List<@NotNull @Valid Item> items) { }
    @Schema(name = "HvacTemperatureRetryRequest")
    public record RetryRequest(@NotEmpty @Size(max = 50) List<@NotBlank String> pendingIds) { }
    @Schema(name = "HvacTemperatureJobItem")
    public record JobItem(String pendingId, String requestId, String configurationStatus,
                          String message, String samplingStatus) { }
    @Schema(name = "HvacTemperatureJobView")
    public record JobView(String jobId, List<JobItem> items) { }
}
