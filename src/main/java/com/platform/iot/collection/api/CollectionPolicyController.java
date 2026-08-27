package com.platform.iot.collection.api;

import com.platform.framework.common.Result;
import com.platform.framework.web.PageResponse;
import com.platform.iot.collection.CollectionPolicyService;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.iot.collection.api.CollectionPolicyContracts.*;

@Tag(name = "数据源与采集策略治理")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "字段或业务规则错误",
                content = @Content(schema = @Schema(implementation = CollectionApiError.class))),
        @ApiResponse(responseCode = "401", description = "未登录",
                content = @Content(schema = @Schema(implementation = CollectionApiError.class))),
        @ApiResponse(responseCode = "403", description = "角色或建筑范围不足",
                content = @Content(schema = @Schema(implementation = CollectionApiError.class))),
        @ApiResponse(responseCode = "404", description = "对象不存在或不可见",
                content = @Content(schema = @Schema(implementation = CollectionApiError.class))),
        @ApiResponse(responseCode = "409", description = "状态、引用、草稿、审核或并发冲突",
                content = @Content(schema = @Schema(implementation = CollectionApiError.class)))
})
/** 采集治理的版本化 HTTP 入口；权限、建筑范围和状态机由服务层再次校验。 */
public class CollectionPolicyController {
    private final CollectionPolicyService service;

    @GetMapping("/v1/data-sources")
    public Result<PageResponse<DataSourceView>> listSources(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String buildingId) {
        return Result.success(service.listSources(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, page, size));
    }

    @GetMapping("/v1/data-sources/{sourceId}")
    public Result<DataSourceView> sourceDetail(Authentication authentication,
                                               @PathVariable String sourceId) {
        return Result.success(service.sourceDetail(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), sourceId));
    }

    @PostMapping("/v1/data-sources")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<DataSourceView> createSource(Authentication authentication,
                                               @Valid @RequestBody SourceCreateRequest request) {
        return Result.success(service.createSource(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }

    @PutMapping("/v1/data-sources/{sourceId}")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<DataSourceView> updateSource(Authentication authentication,
                                               @PathVariable String sourceId,
                                               @Valid @RequestBody SourceUpdateRequest request) {
        return Result.success(service.updateSource(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), sourceId, request));
    }

    @PostMapping("/v1/data-sources/{sourceId}/submit")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ReviewView> submitSource(Authentication authentication,
                                           @PathVariable String sourceId,
                                           @Valid @RequestBody SubmitRequest request) {
        return Result.success(service.submitSource(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), sourceId, request.comment()));
    }

    @PostMapping("/v1/data-sources/{sourceId}/enable")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<ReviewView> enableSource(Authentication authentication,
                                           @PathVariable String sourceId,
                                           @Valid @RequestBody ReasonRequest request) {
        return Result.success(service.submitSourceEnable(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), sourceId, request.reason()));
    }

    @PostMapping("/v1/data-sources/{sourceId}/disable")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<ReviewView> disableSource(Authentication authentication,
                                            @PathVariable String sourceId,
                                            @Valid @RequestBody ReasonRequest request) {
        return Result.success(service.submitSourceDisable(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), sourceId, request.reason()));
    }

    @PostMapping("/v1/data-sources/{sourceId}/runtime-refresh")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<DataSourceView> refreshRuntime(Authentication authentication,
                                                 @PathVariable String sourceId) {
        return Result.success(service.refreshRuntime(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), sourceId));
    }

    @DeleteMapping("/v1/data-sources/{sourceId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Void> deleteSource(Authentication authentication, @PathVariable String sourceId) {
        service.deleteSource(SecurityUser.userId(authentication), SecurityUser.roles(authentication), sourceId);
        return Result.success();
    }

    @GetMapping("/v1/data-sources/{sourceId}/aliases")
    public Result<List<AliasView>> listAliases(Authentication authentication, @PathVariable String sourceId) {
        return Result.success(service.listAliases(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), sourceId));
    }

    @PostMapping("/v1/data-sources/{sourceId}/aliases")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<AliasView> createAlias(Authentication authentication,
                                         @PathVariable String sourceId,
                                         @Valid @RequestBody AliasCreateRequest request) {
        return Result.success(service.createAlias(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), sourceId, request));
    }

    @PostMapping("/v1/data-sources/{sourceId}/aliases/{aliasId}/submit")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ReviewView> submitAlias(Authentication authentication,
                                          @PathVariable String sourceId,
                                          @PathVariable String aliasId,
                                          @Valid @RequestBody SubmitRequest request) {
        return Result.success(service.submitAlias(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), sourceId, aliasId, request.comment()));
    }

    @PostMapping("/v1/data-sources/{sourceId}/aliases/{aliasId}/enable")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<ReviewView> enableAlias(Authentication authentication,
                                          @PathVariable String sourceId,
                                          @PathVariable String aliasId,
                                          @Valid @RequestBody ReasonRequest request) {
        return Result.success(service.submitAliasEnable(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), sourceId, aliasId, request.reason()));
    }

    @PostMapping("/v1/data-sources/{sourceId}/aliases/{aliasId}/disable")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<ReviewView> disableAlias(Authentication authentication,
                                           @PathVariable String sourceId,
                                           @PathVariable String aliasId,
                                           @Valid @RequestBody ReasonRequest request) {
        return Result.success(service.submitAliasDisable(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), sourceId, aliasId, request.reason()));
    }

    @DeleteMapping("/v1/data-sources/{sourceId}/aliases/{aliasId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Void> deleteAlias(Authentication authentication,
                                    @PathVariable String sourceId,
                                    @PathVariable String aliasId) {
        service.deleteAlias(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                sourceId, aliasId);
        return Result.success();
    }

    @GetMapping("/v1/collection-policies")
    public Result<PageResponse<PolicyView>> listPolicies(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String buildingId) {
        return Result.success(service.listPolicies(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, page, size));
    }

    @GetMapping("/v1/collection-policies/{policyId}")
    public Result<PolicyView> policyDetail(Authentication authentication, @PathVariable String policyId) {
        return Result.success(service.policyDetail(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), policyId));
    }

    @GetMapping("/v1/collection-policies/{policyId}/versions")
    public Result<List<PolicyVersionView>> listVersions(Authentication authentication,
                                                        @PathVariable String policyId) {
        return Result.success(service.listVersions(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), policyId));
    }

    @PostMapping("/v1/collection-policies/{policyId}/versions")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<PolicyVersionView> createVersion(Authentication authentication,
                                                   @PathVariable String policyId,
                                                   @Valid @RequestBody PolicyVersionCreateRequest request) {
        return Result.success(service.createVersion(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), policyId, request));
    }

    @PutMapping("/v1/collection-policies/{policyId}/versions/{versionId}")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<PolicyVersionView> updateVersion(Authentication authentication,
                                                   @PathVariable String policyId,
                                                   @PathVariable String versionId,
                                                   @Valid @RequestBody PolicyVersionUpdateRequest request) {
        return Result.success(service.updateVersion(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), policyId, versionId, request));
    }

    @PostMapping("/v1/collection-policies/{policyId}/versions/{versionId}/submit")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ReviewView> submitVersion(Authentication authentication,
                                            @PathVariable String policyId,
                                            @PathVariable String versionId,
                                            @Valid @RequestBody SubmitRequest request) {
        return Result.success(service.submitVersion(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), policyId, versionId, request.comment()));
    }

    @PostMapping("/v1/collection-policies/{policyId}/versions/{versionId}/publish")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<PolicyVersionView> publishVersion(Authentication authentication,
                                                    @PathVariable String policyId,
                                                    @PathVariable String versionId,
                                                    @Valid @RequestBody ReviewRequest request) {
        return Result.success(service.publishVersion(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), policyId, versionId, request.comment()));
    }

    @PostMapping("/v1/collection-policies/{policyId}/versions/{versionId}/copy")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<PolicyVersionView> copyVersion(Authentication authentication,
                                                 @PathVariable String policyId,
                                                 @PathVariable String versionId,
                                                 @Valid @RequestBody CopyVersionRequest request) {
        if (!versionId.equals(request.sourceVersionId())) {
            throw new com.platform.framework.exception.BusinessException(400,
                    com.platform.iot.collection.CollectionErrors.VALIDATION_FAILED,
                    "路径版本与复制来源不一致");
        }
        return Result.success(service.copyVersion(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), policyId, request));
    }

    @PostMapping("/v1/collection-policies/{policyId}/disable")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<PolicyVersionView> disablePolicy(Authentication authentication,
                                                   @PathVariable String policyId,
                                                   @Valid @RequestBody ReasonRequest request) {
        return Result.success(service.createPolicyDisableDraft(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), policyId, request.reason()));
    }

    @DeleteMapping("/v1/collection-policies/{policyId}/versions/{versionId}")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<Void> deleteVersion(Authentication authentication,
                                      @PathVariable String policyId,
                                      @PathVariable String versionId) {
        service.deleteVersion(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                policyId, versionId);
        return Result.success();
    }

    @GetMapping("/v1/collection-review-requests")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<PageResponse<ReviewView>> listReviews(Authentication authentication,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return Result.success(service.listReviews(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), page, size));
    }

    @GetMapping("/v1/collection-review-requests/{requestId}")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ReviewView> reviewDetail(Authentication authentication, @PathVariable String requestId) {
        return Result.success(service.reviewDetail(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), requestId));
    }

    @PostMapping("/v1/collection-review-requests/{requestId}/approve")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<ReviewView> approveReview(Authentication authentication,
                                            @PathVariable String requestId,
                                            @Valid @RequestBody ReviewRequest request) {
        return Result.success(service.approveReview(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), requestId, request.comment()));
    }

    @PostMapping("/v1/collection-review-requests/{requestId}/reject")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<ReviewView> rejectReview(Authentication authentication,
                                           @PathVariable String requestId,
                                           @Valid @RequestBody ReasonRequest request) {
        return Result.success(service.rejectReview(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), requestId, request.reason()));
    }

    @PostMapping("/v1/collection-review-requests/{requestId}/withdraw")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ReviewView> withdrawReview(Authentication authentication, @PathVariable String requestId) {
        return Result.success(service.withdrawReview(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), requestId));
    }

    @GetMapping("/v1/collection-audit-logs")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<PageResponse<AuditView>> listAudits(Authentication authentication,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return Result.success(service.listAudits(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), page, size));
    }
}
