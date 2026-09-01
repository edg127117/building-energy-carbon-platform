package com.platform.energy.aggregation.api;

import com.platform.energy.aggregation.EnergyAggregationApplicationService;
import com.platform.energy.aggregation.EnergyAggregationGovernanceService;
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
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.platform.energy.aggregation.api.EnergyAggregationContracts.*;

@Tag(name = "活动量聚合输入治理")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "事件、修正、策略或时间边界无效",
                content = @Content(schema = @Schema(implementation = EnergyAggregationApiError.class))),
        @ApiResponse(responseCode = "403", description = "角色、职责或建筑范围不足",
                content = @Content(schema = @Schema(implementation = EnergyAggregationApiError.class))),
        @ApiResponse(responseCode = "404", description = "治理版本不存在",
                content = @Content(schema = @Schema(implementation = EnergyAggregationApiError.class))),
        @ApiResponse(responseCode = "409", description = "证据缺失、冲突或安全拒绝",
                content = @Content(schema = @Schema(implementation = EnergyAggregationApiError.class)))
})
/** HTTP 层仅开放版本治理和研发模拟，不提供正式发布、封账或碳排放。 */
public class EnergyAggregationController {
    private final EnergyAggregationGovernanceService governanceService;
    private final EnergyAggregationApplicationService applicationService;

    @GetMapping("/v1/energy-aggregation/meter-event-versions")
    public Result<List<MeterEventVersionView>> events(
            Authentication authentication, @RequestParam String buildingId,
            @RequestParam String pointId) {
        return Result.success(governanceService.listEvents(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, pointId));
    }

    @PostMapping("/v1/energy-aggregation/meter-event-versions")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<MeterEventVersionView> createEvent(
            Authentication authentication,
            @Valid @RequestBody CreateMeterEventVersionRequest request) {
        return Result.success(governanceService.createEvent(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }

    @PostMapping("/v1/energy-aggregation/meter-event-versions/{versionId}/approve")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<MeterEventVersionView> approveEvent(
            Authentication authentication, @PathVariable String versionId,
            @Valid @RequestBody ApproveEvidenceRequest request) {
        return Result.success(governanceService.approveEvent(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, request));
    }

    @GetMapping("/v1/energy-aggregation/correction-versions")
    public Result<List<CorrectionVersionView>> corrections(
            Authentication authentication, @RequestParam String buildingId,
            @RequestParam String pointId) {
        return Result.success(governanceService.listCorrections(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, pointId));
    }

    @PostMapping("/v1/energy-aggregation/correction-versions")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<CorrectionVersionView> createCorrection(
            Authentication authentication,
            @Valid @RequestBody CreateCorrectionVersionRequest request) {
        return Result.success(governanceService.createCorrection(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }

    @PostMapping("/v1/energy-aggregation/correction-versions/{versionId}/approve")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<CorrectionVersionView> approveCorrection(
            Authentication authentication, @PathVariable String versionId,
            @Valid @RequestBody ApproveEvidenceRequest request) {
        return Result.success(governanceService.approveCorrection(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, request));
    }

    @GetMapping("/v1/energy-aggregation/integration-policy-versions")
    public Result<List<IntegrationPolicyVersionView>> policies(
            Authentication authentication, @RequestParam String buildingId,
            @RequestParam String pointId) {
        return Result.success(governanceService.listPolicies(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, pointId));
    }

    @PostMapping("/v1/energy-aggregation/integration-policy-versions")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<IntegrationPolicyVersionView> createPolicy(
            Authentication authentication,
            @Valid @RequestBody CreateIntegrationPolicyVersionRequest request) {
        return Result.success(governanceService.createPolicy(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }

    @PostMapping("/v1/energy-aggregation/integration-policy-versions/{versionId}/approve")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<IntegrationPolicyVersionView> approvePolicy(
            Authentication authentication, @PathVariable String versionId,
            @Valid @RequestBody ApproveEvidenceRequest request) {
        return Result.success(governanceService.approvePolicy(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, request));
    }

    @PostMapping("/v1/energy-aggregation/simulations")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<AggregationSimulationView> simulate(
            Authentication authentication, @Valid @RequestBody AggregationSimulationRequest request) {
        return Result.success(applicationService.simulate(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }
}
