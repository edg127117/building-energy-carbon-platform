package com.platform.iot.qualityusage.governance.api;

import com.platform.framework.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** `/v1/quality-usage` 的稳定请求与响应契约；Entity 不穿透到 HTTP 层。 */
public final class QualityUsageGovernanceContracts {
    private QualityUsageGovernanceContracts() {
    }

    public record PolicyDraftRequest(
            @NotBlank @Size(max = 32) String pointId,
            @NotBlank @Size(max = 64) String scenarioCode,
            @NotNull List<@NotBlank @Size(max = 2) String> allowedQualities,
            @Size(max = 32) String copiedFromVersionId,
            @NotBlank @Size(max = 500) String changeReason) {
    }

    public record ChangeSetCreateRequest(
            @NotBlank @Size(max = 32) String buildingId,
            @NotBlank @Size(max = 100) String title,
            @Size(max = 1000) String description,
            List<@Valid PolicyDraftRequest> policies) {
    }

    public record ChangeSetUpdateRequest(
            @NotNull @Min(0) Integer expectedRevision,
            @NotBlank @Size(max = 100) String title,
            @Size(max = 1000) String description,
            @NotNull List<@Valid PolicyDraftRequest> policies) {
    }

    public record SubmitRequest(@Size(max = 500) String comment) {
    }

    public record ReviewDecisionRequest(@Size(max = 500) String comment) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record ScenarioView(
            String scenarioCode,
            String scenarioName,
            String adapterType,
            String status,
            String introducedVersion) {
    }

    public record PolicyView(
            String policyId,
            String buildingId,
            String pointId,
            String scenarioCode,
            String scenarioName,
            String policySource,
            Integer policyVersion,
            List<String> allowedQualities,
            Long effectiveFromMs,
            Long effectiveToMs,
            String status) {
    }

    public record PolicyVersionView(
            String versionId,
            String policyId,
            String changeSetId,
            Integer versionNo,
            String status,
            String baseActiveVersionId,
            String copiedFromVersionId,
            List<String> allowedQualities,
            Long effectiveFromMs,
            Long effectiveToMs,
            boolean initialBaseline,
            Long publishedConfigRevision,
            String changeSource,
            String changeReason,
            Long createdBy,
            Long createTime,
            Long publishedBy,
            Long publishedAt,
            Long retiredAt) {
    }

    public record ChangeSetView(
            String changeSetId,
            String buildingId,
            String status,
            Integer revision,
            Integer submittedRevision,
            Long createdBy,
            boolean hasBeenSubmitted,
            String title,
            String description,
            String lastFailureCode,
            Long createTime,
            Long updateTime,
            Long submittedAt,
            Long publishedAt,
            Long cancelledAt,
            List<PolicyVersionView> policyVersions) {
    }

    public record ReviewRequestView(
            String requestId,
            String changeSetId,
            String buildingId,
            Integer requestNo,
            String status,
            String reviewMode,
            Integer submittedRevision,
            String snapshotSha256,
            Long submittedBy,
            Long submittedAt,
            Long reviewerId,
            String reviewComment,
            Long reviewedAt,
            Long withdrawnBy,
            Long withdrawnAt) {
    }

    public record AuditView(
            String auditId,
            String buildingId,
            Long operatorId,
            String actionType,
            String objectType,
            String objectId,
            String versionId,
            String beforeSummary,
            String afterSummary,
            String result,
            String reasonCode,
            Long configRevision,
            Long operationTime) {
    }

    public record PolicyPage(PageResponse<PolicyView> page) {
    }

    /** 页面参数边界与其他版本化管理 API 一致，避免暴露 MyBatis 分页对象。 */
    public record PageQuery(@Min(1) int page, @Min(1) @Max(200) int size) {
    }
}
