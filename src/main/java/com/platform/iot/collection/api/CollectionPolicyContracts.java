package com.platform.iot.collection.api;

import com.platform.framework.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** `/v1` 采集治理接口的独立请求与响应契约。 */
public final class CollectionPolicyContracts {
    private CollectionPolicyContracts() {}

    public record SourceCreateRequest(
            @NotBlank @Size(max = 50) String sourceCode,
            @NotBlank @Size(max = 100) String sourceName,
            @NotBlank @Size(max = 32) String buildingId,
            @NotBlank @Size(max = 20) String transportType,
            @Size(max = 500) String description,
            @NotBlank @Size(max = 500) String changeReason) {}

    public record SourceUpdateRequest(
            @NotBlank @Size(max = 100) String sourceName,
            @Size(max = 500) String description,
            @NotNull @Min(0) Integer expectedRevision,
            @NotBlank @Size(max = 500) String changeReason) {}

    public record InitialPolicyRequest(
            @NotNull @Min(1) Integer expectedIntervalSeconds,
            @NotNull @Min(0) Integer allowedDelaySeconds,
            @NotBlank String rawRetentionMode,
            @Min(1) Integer rawRetentionDays,
            @NotBlank String minuteRetentionMode,
            @Min(1) Integer minuteRetentionDays,
            @NotNull Boolean enabled) {}

    public record AliasCreateRequest(
            @NotBlank @Size(max = 255) String sourcePointCode,
            @NotBlank @Size(max = 32) String pointId,
            @NotNull @Valid InitialPolicyRequest initialPolicy,
            @NotBlank @Size(max = 500) String changeReason) {}

    public record PolicyVersionCreateRequest(
            @NotNull @Valid InitialPolicyRequest policy,
            @NotBlank @Size(max = 500) String changeReason) {}

    public record PolicyVersionUpdateRequest(
            @NotNull @Valid InitialPolicyRequest policy,
            @NotNull @Min(0) Integer expectedRevision,
            @NotBlank @Size(max = 500) String changeReason) {}

    public record SubmitRequest(@NotBlank @Size(max = 500) String comment) {}
    public record ReviewRequest(@Size(max = 500) String comment) {}
    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}
    public record CopyVersionRequest(
            @NotBlank String sourceVersionId,
            @NotBlank @Size(max = 500) String reason) {}

    public record DataSourceView(
            String sourceId, String sourceCode, String sourceName, String buildingId,
            String sourceCategory, String transportType, String status, String description,
            int draftAliasCount, int enabledAliasCount, int disabledAliasCount,
            boolean configurationComplete, boolean configuredEffective,
            String runtimeApplyStatus, int configRevision, long runtimeRevision,
            Long appliedRuntimeRevision, Long runtimeAppliedAt, String ineffectiveReason,
            List<String> allowedActions, Long createTime, Long updateTime) {}

    public record AliasView(
            String aliasId, String sourceId, String buildingId, String sourceSystem,
            String sourcePointCode, String pointId, String status, int revision,
            String policyId, String activeVersionId, String draftVersionId,
            List<String> allowedActions) {}

    public record PolicyView(
            String policyId, String sourceId, String aliasId, String buildingId,
            String activeVersionId, String draftVersionId, List<String> allowedActions) {}

    public record PolicyVersionView(
            String versionId, String policyId, int versionNo, String status, boolean enabled,
            int expectedIntervalSeconds, int allowedDelaySeconds, String timeSemantics,
            String rawRetentionMode, Integer rawRetentionDays,
            String minuteRetentionMode, Integer minuteRetentionDays,
            String sourceCode, String sourcePointCode, String pointId, String pointCode,
            String dataType, String unit, String changeType, String changeSource,
            String changeReason, String copiedFromVersionId, int revision,
            Long publishedAt, Long effectiveFrom, Long effectiveTo, Long retiredAt,
            List<String> allowedActions) {}

    public record ReviewView(
            String requestId, String buildingId, String targetType, String targetId,
            int targetConfigRevision, String status, Long submittedBy, Long submittedAt,
            Long reviewerId, String reviewComment, Long reviewedAt, Long withdrawnAt,
            List<String> allowedActions) {}

    public record AuditView(
            String auditId, String buildingId, String actionType, String objectType,
            String objectId, String versionId, String beforeSummary, String afterSummary,
            Long operationTime) {}

    public record SourcePage(PageResponse<DataSourceView> page) {}
}
