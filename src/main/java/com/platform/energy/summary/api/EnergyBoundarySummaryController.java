package com.platform.energy.summary.api;

import com.platform.energy.summary.EnergyBoundarySummaryService;
import com.platform.energy.summary.api.EnergySummaryContracts.ApproveBoundaryPolicyRequest;
import com.platform.energy.summary.api.EnergySummaryContracts.BoundaryPolicyView;
import com.platform.energy.summary.api.EnergySummaryContracts.CreateBoundaryPolicyRequest;
import com.platform.energy.summary.api.EnergySummaryContracts.EnergySummaryApiError;
import com.platform.energy.summary.api.EnergySummaryContracts.SummaryQueryRequest;
import com.platform.energy.summary.api.EnergySummaryContracts.SummaryQueryView;
import com.platform.framework.common.Result;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "能源计量边界汇总")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/v1/energy-boundary-summaries")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "口径或查询条件无效",
                content = @Content(schema = @Schema(implementation = EnergySummaryApiError.class))),
        @ApiResponse(responseCode = "403", description = "角色、职责或建筑范围不足",
                content = @Content(schema = @Schema(implementation = EnergySummaryApiError.class))),
        @ApiResponse(responseCode = "404", description = "汇总口径版本不存在",
                content = @Content(schema = @Schema(implementation = EnergySummaryApiError.class))),
        @ApiResponse(responseCode = "409", description = "口径、关系或查询上限阻断",
                content = @Content(schema = @Schema(implementation = EnergySummaryApiError.class)))
})
/** 暴露无界面的计量边界汇总口径治理和多维查询契约。 */
public class EnergyBoundarySummaryController {
    private final EnergyBoundarySummaryService service;

    @PostMapping("/policies")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<BoundaryPolicyView> createPolicy(
            Authentication authentication,
            @Valid @RequestBody CreateBoundaryPolicyRequest request) {
        return Result.success(service.createPolicy(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }

    @PostMapping("/policies/{versionId}/approve")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<BoundaryPolicyView> approvePolicy(
            Authentication authentication, @PathVariable String versionId,
            @Valid @RequestBody ApproveBoundaryPolicyRequest request) {
        return Result.success(service.approvePolicy(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, request));
    }

    @PostMapping("/query")
    public Result<SummaryQueryView> query(
            Authentication authentication,
            @Valid @RequestBody SummaryQueryRequest request) {
        return Result.success(service.query(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }
}
