package com.platform.iot.deviceparameter.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 标准设备参数治理的稳定 HTTP 契约；持久化对象不会直接暴露给调用方。 */
public final class DeviceParameterContracts {
    private DeviceParameterContracts() {
    }

    public record UnitRequest(
            @NotBlank @Size(max = 20) String unitCode,
            @NotBlank @Size(max = 50) String quantityKind,
            @NotBlank @Size(max = 20) String unitSymbol,
            @NotBlank @Size(max = 255) String evidenceReference,
            @NotNull Integer expectedRevision) {
    }

    public record DefinitionRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,49}") String parameterCode,
            @NotBlank @Size(max = 100) String parameterName,
            @NotBlank @Size(max = 500) String businessDefinition,
            @NotBlank @Size(max = 50) String quantityKind,
            @NotBlank @Size(max = 20) String standardUnit,
            @NotNull @Min(0) @Max(12) Integer storageScale,
            @NotNull @Min(0) @Max(12) Integer displayScale,
            @NotBlank @Size(max = 255) String evidenceReference,
            @NotNull Integer expectedRevision) {
    }

    public record ApplicabilityRequest(
            @NotBlank @Size(max = 20) String equipmentTypeCode,
            @NotBlank String definitionId,
            boolean required,
            boolean formulaReadable,
            BigDecimal hardMin,
            BigDecimal hardMax,
            BigDecimal warningMin,
            BigDecimal warningMax,
            @DecimalMin("0") BigDecimal comparisonTolerance,
            @NotBlank @Size(max = 255) String evidenceReference,
            @NotNull Integer expectedRevision) {
    }

    public record MappingDraftRequest(
            @NotBlank @Size(max = 50) String profileCode,
            @NotBlank @Size(max = 50) String profileVersion,
            @NotBlank @Size(max = 255) String sourcePath,
            @NotBlank String definitionId,
            @NotBlank @Size(max = 20) String sourceUnit,
            @NotNull BigDecimal scale,
            @NotNull BigDecimal offset,
            boolean required,
            @NotBlank @Size(max = 500) String changeReason,
            @NotBlank @Size(max = 255) String evidenceReference,
            @NotNull Integer expectedRevision) {
    }

    public record MappingRollbackRequest(
            @NotBlank String sourceVersionId,
            @NotBlank @Size(max = 500) String reason,
            @NotBlank @Size(max = 255) String evidenceReference,
            @NotNull Integer expectedRevision) {
    }

    public record TemplateValueRequest(
            @NotBlank String definitionId,
            @NotNull BigDecimal value,
            @NotBlank @Size(max = 20) String unit,
            @NotBlank @Size(max = 255) String sourceReference,
            int sortOrder) {
    }

    public record TemplateRevisionRequest(
            @NotBlank String productId,
            @NotBlank @Size(max = 500) String changeReason,
            @NotBlank @Size(max = 255) String evidenceReference,
            @NotEmpty List<@Valid TemplateValueRequest> values,
            @NotNull Integer expectedRevision) {
    }

    public record LegacyMappingRequest(
            @NotBlank @Size(max = 20) String equipmentTypeCode,
            @NotBlank @Pattern(regexp = "rated_capacity|rated_power|design_cop") String legacyField,
            @NotBlank @Pattern(regexp = "MAPPED|NOT_APPLICABLE") String mappingMode,
            String definitionId,
            @Size(max = 20) String sourceUnit,
            BigDecimal scale,
            BigDecimal offset,
            @NotBlank @Size(max = 255) String evidenceReference,
            @NotNull Integer expectedRevision) {
    }

    public record CandidateCreateRequest(
            @NotBlank String equipmentId,
            @NotBlank @Pattern(regexp = "DEVICE|MANUAL|EXCEL|TEMPLATE") String sourceType,
            @NotBlank @Size(max = 255) String sourceReference,
            @Size(max = 100) String sourceVersion,
            @NotBlank @Size(max = 255) String sourceParameterKey,
            @NotBlank @Size(max = 100) String rawValue,
            @Size(max = 20) String rawUnit,
            String mappingVersionId,
            String parameterCode,
            LocalDateTime observedAt,
            @NotBlank @Size(max = 160) String idempotencyKey) {
    }

    public record ConflictResolutionRequest(
            @NotBlank String selectedCandidateId,
            @NotBlank @Size(max = 500) String reason,
            @NotNull Integer expectedRevision) {
    }

    public record DraftValueRequest(
            @NotBlank String definitionId,
            String candidateId,
            @Pattern(regexp = "VALUE|NOT_CONFIGURED") String valueStatus,
            @Size(max = 500) String missingReason,
            @Size(max = 500) String warningReason) {
    }

    public record DraftCreateRequest(
            @NotBlank @Pattern(regexp = "INITIAL|UPDATE|CLEAR") String changeType,
            @NotBlank @Size(max = 500) String changeReason,
            @NotBlank @Size(max = 255) String evidenceReference) {
    }

    public record DraftUpdateRequest(
            @NotNull Integer expectedRevision,
            @NotEmpty List<@Valid DraftValueRequest> values) {
    }

    public record SubmitRequest(
            @NotNull Integer expectedRevision,
            @NotBlank @Size(max = 500) String comment) {
    }

    public record ReviewDecisionRequest(
            @NotBlank @Pattern(regexp = "IMMEDIATE|RETROACTIVE") String publishType,
            LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo,
            @Size(max = 500) String retroactiveReason,
            @NotBlank @Size(max = 255) String evidenceReference,
            String impactFingerprint,
            @NotBlank @Size(max = 500) String comment) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record RollbackRequest(
            @NotBlank String sourceVersionId,
            @NotBlank @Size(max = 500) String reason,
            @NotBlank @Size(max = 255) String evidenceReference) {
    }

    public record AllowedView<T>(T value, List<String> allowedActions) {
    }

    public record ReviewView(
            String requestId,
            String versionId,
            int requestNo,
            String status,
            int submittedRevision,
            Long submittedBy,
            LocalDateTime submittedAt,
            Long reviewerId,
            String reviewComment,
            LocalDateTime reviewedAt,
            List<String> allowedActions) {
    }

    public record DifferenceView(
            String definitionId,
            String parameterCode,
            String changeType,
            BigDecimal beforeValue,
            BigDecimal afterValue,
            String beforeSource,
            String afterSource) {
    }

    /** 双时间查询同时返回选中的时间线及追溯重算状态，调用方无需猜测当前结果是否陈旧。 */
    public record EffectiveParameterView(
            String equipmentId,
            LocalDateTime businessAt,
            LocalDateTime knowledgeAt,
            String timelineRevisionId,
            int timelineRevisionNo,
            LocalDateTime publishedAt,
            String publishType,
            String recalculationStatus,
            LocalDateTime businessEffectiveFrom,
            LocalDateTime businessEffectiveTo,
            com.platform.iot.deviceparameter.DeviceParameterModels.ParameterVersion version) {
    }

    public record RecalculationView(
            String jobId,
            String timelineRevisionId,
            String buildingId,
            String equipmentId,
            List<String> indicatorIds,
            LocalDateTime fromMinute,
            LocalDateTime toMinute,
            String status,
            LocalDateTime cursorMinute,
            int retryCount,
            String failureCode,
            List<String> allowedActions) {
    }

    /** 审计摘要不返回请求哈希、完整快照或外部原始载荷。 */
    public record AuditView(
            String auditId,
            String buildingId,
            String actorType,
            String actionType,
            String objectType,
            String objectId,
            String versionId,
            String result,
            String reasonCode,
            LocalDateTime operationTime) {
    }

    public record FormulaResultRevisionView(
            String resultRevisionId,
            String attemptId,
            String indicatorId,
            String indicatorCode,
            String buildingId,
            String equipmentId,
            long minuteStart,
            double value,
            int dataQuality,
            String formulaVersion,
            String parameterEvidenceJson,
            long calculatedAt) {
    }

    public record ImportBatchView(
            String importBatchId,
            String buildingId,
            String safeFileName,
            String status,
            int rowCount,
            int validRowCount,
            int errorCount,
            String errorSummary,
            List<ImportRowView> rows,
            List<String> allowedActions) {
    }

    public record ImportRowView(
            int rowNo,
            String equipmentCode,
            String parameterCode,
            String rawValue,
            String rawUnit,
            String status,
            String errorField,
            String errorCode) {
    }
}
