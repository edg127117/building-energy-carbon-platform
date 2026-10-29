package com.platform.relation.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.time.Instant;
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

    public record HistoricalMeteringScopeRequest(
            @NotNull Instant startInclusive, @NotNull Instant endExclusive,
            @NotBlank @Size(max = 500) String evidenceReference,
            @NotBlank @Size(max = 500) String reason) {}

    public record HistoricalMeteringScopeDecision(
            @NotNull Boolean approved, @NotBlank @Size(max = 500) String reason) {}

    /** 历史计量适用范围独立审核，不改变关系版本的实际激活时间或当前资产投影。 */
    public record HistoricalMeteringScopeView(
            String scopeId, String buildingId, String relationVersionId,
            Instant startInclusive, Instant endExclusive, String snapshotSha256,
            String evidenceReference, String reason, String status, Long submittedBy,
            Long reviewedBy, String reviewReason, LocalDateTime submittedAt, LocalDateTime reviewedAt) {}

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
            int unassignedCount, int pendingExpertCount, int errorCount, int warningCount,
            int maxPageSize) {
        private static final int DEFAULT_MAX_PAGE_SIZE = 100;

        /** 保留未持有运行时配置的既有调用方，按历史默认值 100 补齐；HTTP 查询元数据由 Service 传入当前环境上限。 */
        public QueryMetadata(
                String buildingId, String versionId, int versionNo, LocalDateTime effectiveAt,
                long modelRevision, int requestedDepth, boolean truncated,
                int unassignedCount, int pendingExpertCount, int errorCount, int warningCount) {
            this(buildingId, versionId, versionNo, effectiveAt, modelRevision, requestedDepth, truncated,
                    unassignedCount, pendingExpertCount, errorCount, warningCount, DEFAULT_MAX_PAGE_SIZE);
        }
    }

    public record SpaceTreeView(QueryMetadata metadata, List<SpaceNodeView> roots) {}

    public record RelationEdgeView(
            String relationItemId, String relationType, String sourceNodeId,
            String targetNodeId, String confirmationStatus, String description) {}

    public record NodeContextView(
            QueryMetadata metadata, String nodeId, String nodeType, String businessObjectId,
            int page, int size, long total, List<RelationEdgeView> edges) {}

    public record EquipmentAssociationItem(
            String equipmentId, String equipmentCode, String equipmentName,
            String spaceId, String spaceName,
            String systemGroupId, String systemGroupName) {}

    public record EquipmentAssociationSpace(
            String spaceId, String spaceName, String parentSpaceId) {}

    public record EquipmentAssociationSystem(
            String systemGroupId, String systemName) {}

    /**
     * 设备关联工作区的只读契约。versionId 为空表示旧投影，非空表示指定关系版本快照。
     */
    public record EquipmentAssociationsView(
            String buildingId, String versionId, Long versionRevision, int page, int size, long total,
            List<EquipmentAssociationItem> items,
            List<EquipmentAssociationSpace> spaces,
            List<EquipmentAssociationSystem> systems) {}

    public record MeteringBoundaryView(
            String boundaryId, String boundaryCode, String boundaryName, String energyType,
            String confirmationStatus, String status) {}

    public record MeteringBoundariesView(
            QueryMetadata metadata, int page, int size, long total,
            List<MeteringBoundaryView> items) {}

    public record BoundaryCatalogueItem(String boundaryId, String boundaryCode, String boundaryName,
                                        String energyType, String confirmationStatus, String status,
                                        String evidenceReference) {}
    public record BoundaryCatalogueView(QueryMetadata metadata, int page, int size, long total,
                                        boolean editableCatalogue, List<BoundaryCatalogueItem> items) {}
    public record NodeCatalogueItem(String nodeId, String nodeType, String businessObjectId,
                                    String code, String name, String status) {}
    public record NodeCatalogueView(QueryMetadata metadata, int page, int size, long total,
                                    String labelNature, List<NodeCatalogueItem> items) {}

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
