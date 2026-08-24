package com.platform.iot.qualityusage.governance.api;

import com.platform.framework.common.Result;
import com.platform.framework.web.PageResponse;
import com.platform.iot.qualityusage.governance.QualityUsageGovernanceService;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.iot.qualityusage.governance.api.QualityUsageGovernanceContracts.*;

@Tag(name = "Q0/Q1/Q2 使用策略治理")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "字段或质量策略规则错误",
                content = @Content(schema = @Schema(implementation = QualityUsageApiError.class))),
        @ApiResponse(responseCode = "401", description = "未登录",
                content = @Content(schema = @Schema(implementation = QualityUsageApiError.class))),
        @ApiResponse(responseCode = "403", description = "角色或建筑范围不足",
                content = @Content(schema = @Schema(implementation = QualityUsageApiError.class))),
        @ApiResponse(responseCode = "404", description = "对象不存在或不可见",
                content = @Content(schema = @Schema(implementation = QualityUsageApiError.class))),
        @ApiResponse(responseCode = "409", description = "状态、版本、待审核或幂等冲突",
                content = @Content(schema = @Schema(implementation = QualityUsageApiError.class))),
        @ApiResponse(responseCode = "503", description = "场景或运行快照暂不可用",
                content = @Content(schema = @Schema(implementation = QualityUsageApiError.class)))
})
/** HTTP 仅适配 DTO 和角色入口；建筑范围、可见性、状态机与事务仍由 Service 强制执行。 */
public class QualityUsageGovernanceController {
    private final QualityUsageGovernanceService service;

    @GetMapping("/v1/quality-usage/scenarios")
    public Result<List<ScenarioView>> listScenarios(Authentication authentication) {
        return Result.success(service.listScenarios(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication)));
    }

    @GetMapping("/v1/quality-usage/policies/active")
    public Result<PageResponse<PolicyView>> listActivePolicies(
            Authentication authentication,
            @RequestParam(required = false) String buildingId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(service.listActivePolicies(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, page, size));
    }

    @GetMapping("/v1/quality-usage/policies/{policyId}")
    public Result<PolicyView> policyDetail(Authentication authentication, @PathVariable String policyId) {
        return Result.success(service.policyDetail(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), policyId));
    }

    @GetMapping("/v1/quality-usage/policies/{policyId}/versions")
    public Result<List<PolicyVersionView>> listVersions(
            Authentication authentication, @PathVariable String policyId) {
        return Result.success(service.listVersions(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), policyId));
    }

    @GetMapping("/v1/quality-usage/change-sets")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<PageResponse<ChangeSetView>> listChangeSets(
            Authentication authentication,
            @RequestParam(required = false) String buildingId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(service.listChangeSets(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, page, size));
    }

    @GetMapping("/v1/quality-usage/change-sets/{changeSetId}")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ChangeSetView> changeSetDetail(
            Authentication authentication, @PathVariable String changeSetId) {
        return Result.success(service.changeSetDetail(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), changeSetId));
    }

    @PostMapping("/v1/quality-usage/change-sets")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ChangeSetView> createChangeSet(
            Authentication authentication, @Valid @RequestBody ChangeSetCreateRequest request) {
        return Result.success(service.createChangeSet(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }

    @PutMapping("/v1/quality-usage/change-sets/{changeSetId}")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ChangeSetView> updateChangeSet(
            Authentication authentication,
            @PathVariable String changeSetId,
            @Valid @RequestBody ChangeSetUpdateRequest request) {
        return Result.success(service.updateChangeSet(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), changeSetId, request));
    }

    @PostMapping("/v1/quality-usage/change-sets/{changeSetId}/submit")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ReviewRequestView> submit(
            Authentication authentication,
            @PathVariable String changeSetId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SubmitRequest request) {
        return Result.success(service.submit(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                changeSetId, idempotencyKey, request.comment()));
    }

    @PostMapping("/v1/quality-usage/change-sets/{changeSetId}/withdraw")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ChangeSetView> withdraw(
            Authentication authentication,
            @PathVariable String changeSetId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReasonRequest request) {
        return Result.success(service.withdraw(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                changeSetId, idempotencyKey, request.reason()));
    }

    @PostMapping("/v1/quality-usage/change-sets/{changeSetId}/cancel")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ChangeSetView> cancel(
            Authentication authentication,
            @PathVariable String changeSetId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReasonRequest request) {
        return Result.success(service.cancel(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                changeSetId, idempotencyKey, request.reason()));
    }

    @PostMapping("/v1/quality-usage/change-sets/{changeSetId}/direct-publish")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<ReviewRequestView> directPublish(
            Authentication authentication,
            @PathVariable String changeSetId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReasonRequest request) {
        return Result.success(service.directPublish(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                changeSetId, idempotencyKey, request.reason()));
    }

    @DeleteMapping("/v1/quality-usage/change-sets/{changeSetId}")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<Void> deleteChangeSet(Authentication authentication, @PathVariable String changeSetId) {
        service.deleteChangeSet(SecurityUser.userId(authentication), SecurityUser.roles(authentication), changeSetId);
        return Result.success();
    }

    @GetMapping("/v1/quality-usage/review-requests")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<PageResponse<ReviewRequestView>> listReviews(
            Authentication authentication,
            @RequestParam(required = false) String buildingId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(service.listReviews(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                buildingId, page, size));
    }

    @GetMapping("/v1/quality-usage/review-requests/{requestId}")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ReviewRequestView> reviewDetail(Authentication authentication, @PathVariable String requestId) {
        return Result.success(service.reviewDetail(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), requestId));
    }

    @PostMapping("/v1/quality-usage/review-requests/{requestId}/approve")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<ReviewRequestView> approve(
            Authentication authentication,
            @PathVariable String requestId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReviewDecisionRequest request) {
        return Result.success(service.approve(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                requestId, idempotencyKey, request.comment()));
    }

    @PostMapping("/v1/quality-usage/review-requests/{requestId}/reject")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<ReviewRequestView> reject(
            Authentication authentication,
            @PathVariable String requestId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReasonRequest request) {
        return Result.success(service.reject(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                requestId, idempotencyKey, request.reason()));
    }

    @GetMapping("/v1/quality-usage/audit-logs")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<PageResponse<AuditView>> listAudits(
            Authentication authentication,
            @RequestParam(required = false) String buildingId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(service.listAudits(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                buildingId, page, size));
    }
}
