package com.platform.relation.api;

import com.platform.framework.common.Result;
import com.platform.relation.RelationGovernanceService;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.relation.api.RelationContracts.*;

@Tag(name = "建筑关系治理")
@SecurityRequirement(name = "bearerAuth")
@RestController
@Validated
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "字段、作用域或图规则错误",
                content = @Content(schema = @Schema(implementation = RelationApiError.class))),
        @ApiResponse(responseCode = "401", description = "未登录",
                content = @Content(schema = @Schema(implementation = RelationApiError.class))),
        @ApiResponse(responseCode = "403", description = "角色或建筑范围不足",
                content = @Content(schema = @Schema(implementation = RelationApiError.class))),
        @ApiResponse(responseCode = "404", description = "模型、版本或节点不存在",
                content = @Content(schema = @Schema(implementation = RelationApiError.class))),
        @ApiResponse(responseCode = "409", description = "版本、审核、引用或专业确认冲突",
                content = @Content(schema = @Schema(implementation = RelationApiError.class)))
})
/** HTTP 层只适配 DTO 和角色入口；真实建筑范围、状态机、快照和事务由 Service 强制执行。 */
public class RelationGovernanceController {
    private final RelationGovernanceService service;

    @GetMapping("/v1/relation-models")
    public Result<ModelView> model(
            Authentication authentication, @RequestParam String buildingId) {
        return Result.success(service.model(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId));
    }

    @PostMapping("/v1/relation-models/{buildingId}/initialize")
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<VersionView> initialize(
            Authentication authentication, @PathVariable String buildingId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReasonRequest request) {
        return Result.success(service.initialize(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, idempotencyKey, request.reason()));
    }

    @PostMapping("/v1/relation-models/{buildingId}/versions")
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<VersionView> createVersion(
            Authentication authentication, @PathVariable String buildingId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateVersionRequest request) {
        return Result.success(service.createVersion(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, idempotencyKey, request));
    }

    @GetMapping("/v1/relation-models/{buildingId}/versions")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<List<VersionView>> versions(
            Authentication authentication, @PathVariable String buildingId) {
        return Result.success(service.versions(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId));
    }

    @GetMapping("/v1/relation-models/{buildingId}/versions/{versionId}")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<VersionDetailView> versionDetail(
            Authentication authentication, @PathVariable String buildingId,
            @PathVariable String versionId) {
        return Result.success(service.versionDetail(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, versionId));
    }

    @GetMapping("/v1/relation-models/{buildingId}/versions/{versionId}/diff")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<VersionDiffView> versionDiff(
            Authentication authentication, @PathVariable String buildingId,
            @PathVariable String versionId, @RequestParam String againstVersionId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(500) int sampleSize) {
        return Result.success(service.versionDiff(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, againstVersionId,
                versionId, sampleSize));
    }

    @PutMapping("/v1/relation-models/versions/{versionId}/structure/spaces/{spaceId}")
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<VersionView> updateSpaceParent(
            Authentication authentication, @PathVariable String versionId,
            @PathVariable String spaceId, @Valid @RequestBody SpaceParentRequest request) {
        return Result.success(service.updateSpaceParent(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, spaceId, request));
    }

    @PutMapping("/v1/relation-models/versions/{versionId}/structure/assignments")
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<VersionView> updateAssignment(
            Authentication authentication, @PathVariable String versionId,
            @Valid @RequestBody AssetAssignmentRequest request) {
        return Result.success(service.updateAssignment(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, request));
    }

    @PostMapping("/v1/relation-models/versions/{versionId}/relations")
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<RelationEdgeView> addRelation(
            Authentication authentication, @PathVariable String versionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SemanticRelationRequest request) {
        return Result.success(service.addSemanticRelation(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, idempotencyKey, request));
    }

    @PutMapping("/v1/relation-models/versions/{versionId}/relations/{relationItemId}")
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<RelationEdgeView> updateRelation(
            Authentication authentication, @PathVariable String versionId,
            @PathVariable String relationItemId,
            @Valid @RequestBody SemanticRelationRequest request) {
        return Result.success(service.updateSemanticRelation(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, relationItemId, request));
    }

    @DeleteMapping("/v1/relation-models/versions/{versionId}/relations/{relationItemId}")
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<Void> deleteRelation(
            Authentication authentication, @PathVariable String versionId,
            @PathVariable String relationItemId, @RequestParam @Min(0) long expectedRevision) {
        service.deleteSemanticRelation(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                versionId, relationItemId, expectedRevision);
        return Result.success();
    }

    @PostMapping("/v1/relation-models/versions/{versionId}/metering/boundaries")
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<MeteringBoundaryView> createBoundary(
            Authentication authentication, @PathVariable String versionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody MeteringBoundaryRequest request) {
        return Result.success(service.createBoundary(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, idempotencyKey, request));
    }

    @PutMapping("/v1/relation-models/versions/{versionId}/metering/boundaries/{boundaryId}")
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<MeteringBoundaryView> updateBoundary(
            Authentication authentication, @PathVariable String versionId,
            @PathVariable String boundaryId,
            @Valid @RequestBody MeteringBoundaryRequest request) {
        return Result.success(service.updateBoundary(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, boundaryId, request));
    }

    @DeleteMapping("/v1/relation-models/versions/{versionId}/metering/boundaries/{boundaryId}")
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<Void> retireBoundary(
            Authentication authentication, @PathVariable String versionId,
            @PathVariable String boundaryId, @RequestParam @Min(0) long expectedRevision) {
        service.retireBoundary(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                versionId, boundaryId, expectedRevision);
        return Result.success();
    }

    @PostMapping("/v1/relation-models/versions/{versionId}/metering/assignments")
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<String> createMeteringAssignment(
            Authentication authentication, @PathVariable String versionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody MeteringAssignmentRequest request) {
        return Result.success(service.createMeteringAssignment(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, idempotencyKey, request));
    }

    @PutMapping("/v1/relation-models/versions/{versionId}/metering/assignments/{assignmentId}")
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<String> updateMeteringAssignment(
            Authentication authentication, @PathVariable String versionId,
            @PathVariable String assignmentId,
            @Valid @RequestBody MeteringAssignmentRequest request) {
        return Result.success(service.updateMeteringAssignment(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, assignmentId, request));
    }

    @DeleteMapping("/v1/relation-models/versions/{versionId}/metering/assignments/{assignmentId}")
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<Void> deleteMeteringAssignment(
            Authentication authentication, @PathVariable String versionId,
            @PathVariable String assignmentId, @RequestParam @Min(0) long expectedRevision) {
        service.deleteMeteringAssignment(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, assignmentId, expectedRevision);
        return Result.success();
    }

    @PostMapping("/v1/relation-models/versions/{versionId}/metering/structures")
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<MeterStructureView> createMeterStructure(
            Authentication authentication, @PathVariable String versionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody MeterStructureRequest request) {
        return Result.success(service.createMeterStructure(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, idempotencyKey, request));
    }

    @PutMapping("/v1/relation-models/versions/{versionId}/metering/structures/{structureItemId}")
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<MeterStructureView> updateMeterStructure(
            Authentication authentication, @PathVariable String versionId,
            @PathVariable String structureItemId,
            @Valid @RequestBody MeterStructureRequest request) {
        return Result.success(service.updateMeterStructure(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, structureItemId, request));
    }

    @DeleteMapping("/v1/relation-models/versions/{versionId}/metering/structures/{structureItemId}")
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<Void> deleteMeterStructure(
            Authentication authentication, @PathVariable String versionId,
            @PathVariable String structureItemId,
            @RequestParam @Min(0) long expectedRevision) {
        service.deleteMeterStructure(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, structureItemId, expectedRevision);
        return Result.success();
    }

    @PostMapping("/v1/relation-models/versions/{versionId}/validate")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ValidationView> validate(
            Authentication authentication, @PathVariable String versionId) {
        return Result.success(service.validate(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId));
    }

    @PostMapping("/v1/relation-models/versions/{versionId}/submit")
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<ReviewView> submit(
            Authentication authentication, @PathVariable String versionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RevisionReasonRequest request) {
        return Result.success(service.submit(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, idempotencyKey, request));
    }

    @PostMapping("/v1/relation-models/versions/{versionId}/withdraw")
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<VersionView> withdraw(
            Authentication authentication, @PathVariable String versionId,
            @Valid @RequestBody RevisionReasonRequest request) {
        return Result.success(service.withdraw(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, request));
    }

    @PostMapping("/v1/relation-models/review-requests/{requestId}/approve")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<ReviewView> approve(
            Authentication authentication, @PathVariable String requestId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReviewDecisionRequest request) {
        return Result.success(service.approve(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), requestId, idempotencyKey, request));
    }

    @PostMapping("/v1/relation-models/review-requests/{requestId}/reject")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<ReviewView> reject(
            Authentication authentication, @PathVariable String requestId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReviewDecisionRequest request) {
        return Result.success(service.reject(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), requestId, idempotencyKey, request));
    }

    @PostMapping("/v1/relation-models/versions/{versionId}/activate")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<VersionView> activate(
            Authentication authentication, @PathVariable String versionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ActivationRequest request) {
        return Result.success(service.activate(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, idempotencyKey, request));
    }

    @GetMapping("/v1/relation-models/reviews")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<List<ReviewView>> reviews(
            Authentication authentication, @RequestParam String buildingId) {
        return Result.success(service.reviews(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId));
    }

    @GetMapping("/v1/relation-models/audits")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<List<AuditView>> audits(
            Authentication authentication, @RequestParam String buildingId) {
        return Result.success(service.audits(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId));
    }

    @GetMapping("/v1/relation-models/{buildingId}/effective/space-tree")
    public Result<SpaceTreeView> effectiveSpaceTree(
            Authentication authentication, @PathVariable String buildingId) {
        return Result.success(service.effectiveSpaceTree(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId));
    }

    @GetMapping("/v1/relation-models/{buildingId}/versions/{versionId}/query/space-tree")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<SpaceTreeView> historicalSpaceTree(
            Authentication authentication, @PathVariable String buildingId,
            @PathVariable String versionId) {
        return Result.success(service.historicalSpaceTree(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, versionId));
    }

    @GetMapping("/v1/relation-models/{buildingId}/effective/nodes/{nodeType}/{nodeId}/context")
    public Result<NodeContextView> nodeContext(
            Authentication authentication, @PathVariable String buildingId,
            @PathVariable String nodeType, @PathVariable String nodeId,
            @RequestParam(defaultValue = "1") @Min(1) int depth,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(500) int size) {
        return Result.success(service.nodeContext(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, nodeType, nodeId, depth, page, size));
    }

    @GetMapping("/v1/relation-models/{buildingId}/versions/{versionId}/query/nodes/{nodeType}/{nodeId}/context")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<NodeContextView> historicalNodeContext(
            Authentication authentication, @PathVariable String buildingId,
            @PathVariable String versionId, @PathVariable String nodeType,
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "1") @Min(1) int depth,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(500) int size) {
        return Result.success(service.historicalNodeContext(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, versionId,
                nodeType, nodeId, depth, page, size));
    }

    @GetMapping("/v1/relation-models/{buildingId}/effective/metering-boundaries")
    public Result<MeteringBoundariesView> effectiveBoundaries(
            Authentication authentication, @PathVariable String buildingId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(500) int size) {
        return Result.success(service.effectiveBoundaries(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, page, size));
    }

    @GetMapping("/v1/relation-models/{buildingId}/effective/metering-assignments")
    public Result<MeteringAssignmentsView> effectiveMeteringAssignments(
            Authentication authentication, @PathVariable String buildingId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int size) {
        return Result.success(service.effectiveMeteringAssignments(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                buildingId, page, size));
    }

    @GetMapping("/v1/relation-models/{buildingId}/versions/{versionId}/query/metering-assignments")
    public Result<MeteringAssignmentsView> historicalMeteringAssignments(
            Authentication authentication, @PathVariable String buildingId,
            @PathVariable String versionId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int size) {
        return Result.success(service.historicalMeteringAssignments(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                buildingId, versionId, page, size));
    }

    @GetMapping("/v1/relation-models/{buildingId}/effective/metering-structures")
    public Result<MeterStructuresView> effectiveMeterStructures(
            Authentication authentication, @PathVariable String buildingId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int size) {
        return Result.success(service.effectiveMeterStructures(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, page, size));
    }

    @GetMapping("/v1/relation-models/{buildingId}/versions/{versionId}/query/metering-structures")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<MeterStructuresView> historicalMeterStructures(
            Authentication authentication, @PathVariable String buildingId,
            @PathVariable String versionId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int size) {
        return Result.success(service.historicalMeterStructures(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, versionId, page, size));
    }

    @GetMapping("/v1/relation-models/{buildingId}/effective/meters/{meterPointNodeId}/hierarchy")
    public Result<MeterHierarchyView> effectiveMeterHierarchy(
            Authentication authentication, @PathVariable String buildingId,
            @PathVariable String meterPointNodeId) {
        return Result.success(service.effectiveMeterHierarchy(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, meterPointNodeId));
    }

    @GetMapping("/v1/relation-models/{buildingId}/versions/{versionId}/query/meters/{meterPointNodeId}/hierarchy")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<MeterHierarchyView> historicalMeterHierarchy(
            Authentication authentication, @PathVariable String buildingId,
            @PathVariable String versionId, @PathVariable String meterPointNodeId) {
        return Result.success(service.historicalMeterHierarchy(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, versionId, meterPointNodeId));
    }

    @GetMapping("/v1/relation-models/{buildingId}/effective/issues")
    public Result<EffectiveIssuesView> effectiveIssues(
            Authentication authentication, @PathVariable String buildingId) {
        return Result.success(service.effectiveIssues(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId));
    }
}
