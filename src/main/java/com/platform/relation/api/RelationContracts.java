package com.platform.relation.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** 关系治理 HTTP 契约；不直接暴露数据库实体。 */
public final class RelationContracts {
    private RelationContracts() {
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

    public record RevisionReasonRequest(
            @NotNull @Min(0) Long expectedRevision,
            @NotBlank @Size(max = 500) String reason) {}

    public record CreateVersionRequest(
            @NotBlank @Size(max = 500) String reason,
            String copiedFromVersionId,
            @NotNull @Min(0) Long expectedModelRevision) {}

    public record SpaceParentRequest(
            String parentSpaceId,
            @NotNull @Min(0) Long expectedRevision,
            @Min(0) Integer sortOrder) {}

    public record AssetAssignmentRequest(
            @NotBlank String objectType,
            @NotBlank String objectId,
            String spaceId,
            String systemGroupId,
            String equipmentId,
            @NotNull @Min(0) Long expectedRevision) {}

    public record SemanticRelationRequest(
            @NotBlank String relationType,
            @NotBlank String sourceNodeId,
            @NotBlank String targetNodeId,
            @NotBlank String sourceType,
            @NotBlank String confirmationStatus,
            @Size(max = 500) String evidenceReference,
            @Size(max = 500) String description,
            @NotNull @Min(0) Long expectedRevision) {}

    public record MeteringBoundaryRequest(
            @NotBlank @Size(max = 80) String boundaryCode,
            @NotBlank @Size(max = 160) String boundaryName,
            @Size(max = 64) String energyType,
            @NotBlank String confirmationStatus,
            @Size(max = 500) String evidenceReference,
            @NotNull @Min(0) Long expectedRevision) {}

    public record MeteringAssignmentRequest(
            String meteringBoundaryId,
            String meterPointNodeId,
            String targetNodeId,
            @NotBlank String allocationStatus,
            @Size(max = 64) String reasonCode,
            @Size(max = 500) String reasonText,
            @Size(max = 500) String evidenceReference,
            @NotNull @Min(0) Long expectedRevision) {}

    public record MeterStructureRequest(
            String meteringBoundaryId,
            @NotBlank String meterPointNodeId,
            @NotBlank String meterRole,
            String parentMeterPointNodeId,
            @NotBlank String meterDirection,
            @NotBlank String confirmationStatus,
            @Size(max = 64) String reasonCode,
            @Size(max = 500) String reasonText,
            @Size(max = 500) String evidenceReference,
            @Size(max = 500) String description,
            @NotNull @Min(0) Long expectedRevision) {}

    public record ReviewDecisionRequest(
            @NotBlank @Size(max = 500) String reason) {}

    public record ActivationRequest(
            @NotNull @Min(0) Long expectedModelRevision,
            @NotBlank @Size(max = 500) String reason) {}

    public record ModelView(
            String modelId, String buildingId, String scopeType, String governanceMode,
            String activeVersionId, String draftVersionId, long modelRevision) {}

    public record VersionView(
            String versionId, String modelId, String buildingId, int versionNo,
            String baseVersionId, String copiedFromVersionId, String status,
            long revision, Long submittedRevision, String snapshotSha256,
            String changeReason, Long createdBy, Long submittedBy, Long approvedBy,
            Long activatedBy, LocalDateTime createdAt, LocalDateTime effectiveAt) {}

    public record SnapshotCounts(
            int spaces, int assetAssignments, int semanticRelations,
            int meteringBoundaries, int meterStructures,
            int meteringAssignments, int validationIssues) {}

    public record VersionDetailView(
            QueryMetadata metadata, VersionView version, SnapshotCounts counts,
            List<MeterStructureView> meterStructures) {}

    public record VersionDiffView(
            String buildingId, String fromVersionId, int fromVersionNo,
            String toVersionId, int toVersionNo, long modelRevision,
            SnapshotCounts fromCounts, SnapshotCounts toCounts,
            int addedCount, int removedCount, boolean truncated,
            List<String> addedSamples, List<String> removedSamples,
            int meterStructureAddedCount, int meterStructureRemovedCount) {}

    public record ReviewView(
            String requestId, String versionId, String buildingId, int requestNo,
            String status, long submittedRevision, String snapshotSha256,
            Long submittedBy, Long reviewerId, String reviewReason,
            boolean selfApprovalDevMode, LocalDateTime submittedAt, LocalDateTime reviewedAt) {}

    public record ValidationIssueView(
            String issueId, String level, String code, String objectType,
            String objectId, String message, LocalDateTime detectedAt) {}

    public record ValidationView(
            String buildingId, String versionId, long revision,
            int errorCount, int pendingExpertCount, int warningCount,
            List<ValidationIssueView> issues) {}

    public record SpaceNodeView(
            String spaceId, String spaceName, String spaceType, String parentSpaceId,
            int sortOrder, List<SpaceNodeView> children) {}

    public record QueryMetadata(
            String buildingId, String versionId, int versionNo, LocalDateTime effectiveAt,
            long modelRevision, int requestedDepth, boolean truncated,
            int unassignedCount, int pendingExpertCount, int errorCount, int warningCount) {}

    public record SpaceTreeView(QueryMetadata metadata, List<SpaceNodeView> roots) {}

    public record RelationEdgeView(
            String relationItemId, String relationType, String sourceNodeId,
            String targetNodeId, String confirmationStatus, String description) {}

    public record NodeContextView(
            QueryMetadata metadata, String nodeId, String nodeType, String businessObjectId,
            int page, int size, long total, List<RelationEdgeView> edges) {}

    public record MeteringBoundaryView(
            String boundaryId, String boundaryCode, String boundaryName, String energyType,
            String confirmationStatus, String status) {}

    public record MeteringBoundariesView(
            QueryMetadata metadata, int page, int size, long total,
            List<MeteringBoundaryView> items) {}

    public record MeteringAssignmentView(
            String assignmentItemId, String allocationStatus,
            String reasonCode, String reasonText, String evidenceReference,
            String meteringBoundaryId, String meteringBoundaryCode,
            String meteringBoundaryName, String energyType,
            String boundaryConfirmationStatus, String boundaryStatus,
            String meterPointNodeId, String pointId, String pointCode, String pointName,
            String meterRole, String meterDirection, String meterConfirmationStatus,
            String targetNodeId, String targetNodeType, String targetObjectId,
            String targetObjectCode, String targetObjectName) {}

    public record MeteringAssignmentsView(
            QueryMetadata metadata, int page, int size, long total,
            List<MeteringAssignmentView> items) {}

    public record MeterStructureView(
            String structureItemId, String meteringBoundaryId,
            String meterPointNodeId, String meterPointCode, String meterRole,
            String parentMeterPointNodeId, String parentMeterPointCode,
            String meterDirection, String confirmationStatus,
            String reasonCode, String reasonText, String evidenceReference,
            String description, String sourceType) {}

    public record MeterStructuresView(
            QueryMetadata metadata, int page, int size, long total,
            List<MeterStructureView> items) {}

    public record MeterHierarchyView(
            QueryMetadata metadata, MeterStructureView meter,
            MeterStructureView parent, List<MeterStructureView> children) {}

    public record MeteringImportIssue(
            String level, String sheet, int rowNumber, String field,
            String code, String message) {}

    public record MeteringImportPreviewRow(
            int rowNumber, String templateVersion, String boundaryCode,
            String boundaryName, String energyType, String meterPointCode,
            String meterRole, String parentMeterCode, String meterDirection,
            String targetType, String targetCode, String allocationStatus,
            String reasonCode, String reasonText, String evidenceReference,
            String description) {}

    public record MeteringImportPreflightView(
            String templateVersion, String buildingId, String versionId,
            long expectedRevision, int totalRows, int passedRows,
            int warningCount, int errorCount,
            List<MeteringImportIssue> issues,
            List<MeteringImportPreviewRow> preview) {}

    public record MeteringImportResult(
            String buildingId, String versionId, long revision,
            int structureCount, int assignmentCount, int boundaryCreatedCount,
            boolean idempotentReplay) {}

    public record EffectiveIssuesView(QueryMetadata metadata, List<ValidationIssueView> items) {}

    public record AuditView(
            String auditId, String buildingId, Long operatorId, String actionType,
            String objectType, String objectId, String versionId, String requestId,
            String beforeState, String afterState, String reason, String result,
            String summary, LocalDateTime operationTime) {}

    @Schema(name = "RelationApiError")
    public record RelationApiError(
            @Schema(example = "409") int code,
            @Schema(example = "RELATION_VERSION_CONFLICT") String errorCode,
            @Schema(example = "关系版本已被其他操作修改") String msg,
            @Schema(example = "false") boolean success) {}

    public record PageQuery(
            @Min(1) int page,
            @Min(1) @Max(500) int size) {}
}
