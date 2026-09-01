package com.platform.energy.period.api;

import com.platform.framework.common.Result;
import com.platform.energy.period.EnergyPeriodGovernanceService;
import com.platform.energy.period.EnergyPeriodLifecycleService;
import com.platform.energy.period.EnergyPeriodModels.ExceptionPolicyVersion;
import com.platform.energy.period.EnergyPeriodModels.PeriodPolicyVersion;
import com.platform.energy.period.api.EnergyPeriodContracts.*;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "能源周期封账与重算")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/v1/energy-periods")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "周期、策略或请求参数无效",
                content = @Content(schema = @Schema(implementation = EnergyPeriodApiError.class))),
        @ApiResponse(responseCode = "403", description = "角色、职责或建筑范围不足",
                content = @Content(schema = @Schema(implementation = EnergyPeriodApiError.class))),
        @ApiResponse(responseCode = "404", description = "周期策略、投影、快照或批次不存在",
                content = @Content(schema = @Schema(implementation = EnergyPeriodApiError.class))),
        @ApiResponse(responseCode = "409", description = "封账、版本、幂等或重算状态冲突",
                content = @Content(schema = @Schema(implementation = EnergyPeriodApiError.class)))
})
/** 暴露无界面的周期口径、投影、封账和重算后端契约。 */
public class EnergyPeriodController {
    private final EnergyPeriodGovernanceService governanceService;
    private final EnergyPeriodLifecycleService lifecycleService;

    @PostMapping("/policies")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<PeriodPolicyView> createPolicy(
            Authentication authentication,
            @Valid @RequestBody CreatePeriodPolicyRequest request) {
        return Result.success(periodPolicy(governanceService.createPeriodPolicy(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication), request)));
    }

    @PostMapping("/policies/{versionId}/approve")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<PeriodPolicyView> approvePolicy(
            Authentication authentication, @PathVariable String versionId,
            @Valid @RequestBody ApproveRequest request) {
        return Result.success(periodPolicy(governanceService.approvePeriodPolicy(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                versionId, request)));
    }

    @PostMapping("/exception-policies")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ExceptionPolicyView> createExceptionPolicy(
            Authentication authentication,
            @Valid @RequestBody CreateExceptionPolicyRequest request) {
        return Result.success(exceptionPolicy(governanceService.createExceptionPolicy(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication), request)));
    }

    @PostMapping("/exception-policies/{versionId}/approve")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ExceptionPolicyView> approveExceptionPolicy(
            Authentication authentication, @PathVariable String versionId,
            @Valid @RequestBody ApproveRequest request) {
        return Result.success(exceptionPolicy(governanceService.approveExceptionPolicy(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                versionId, request)));
    }

    @PostMapping("/current/refresh")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ProjectionView> refresh(
            Authentication authentication,
            @Valid @RequestBody RefreshProjectionRequest request) {
        return Result.success(lifecycleService.refresh(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }

    @GetMapping("/current/{projectionId}")
    public Result<ProjectionView> current(
            Authentication authentication, @PathVariable String projectionId) {
        return Result.success(lifecycleService.current(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), projectionId));
    }

    @PostMapping("/lock-requests")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<LockRequestView> submitLock(
            Authentication authentication,
            @Valid @RequestBody SubmitLockRequest request) {
        return Result.success(lifecycleService.submitLock(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }

    @PostMapping("/lock-requests/{requestId}/approve")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<SnapshotView> approveLock(
            Authentication authentication, @PathVariable String requestId,
            @Valid @RequestBody ApproveLockRequest request) {
        return Result.success(lifecycleService.approveLock(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), requestId, request));
    }

    @GetMapping("/current/{projectionId}/snapshot")
    public Result<SnapshotView> snapshot(
            Authentication authentication, @PathVariable String projectionId) {
        return Result.success(lifecycleService.snapshot(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), projectionId));
    }

    @PostMapping("/recalculation-batches")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<RecalculationBatchView> submitRecalculation(
            Authentication authentication,
            @Valid @RequestBody SubmitRecalculationRequest request) {
        return Result.success(lifecycleService.submitRecalculation(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication), request));
    }

    @PostMapping("/recalculation-batches/{batchId}/approve")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<RecalculationBatchView> approveRecalculation(
            Authentication authentication, @PathVariable String batchId,
            @Valid @RequestBody ApproveRecalculationRequest request) {
        return Result.success(lifecycleService.approveRecalculation(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                batchId, request));
    }

    @PostMapping("/recalculation-batches/{batchId}/execute")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<RecalculationBatchView> executeRecalculation(
            Authentication authentication, @PathVariable String batchId) {
        return Result.success(lifecycleService.executeRecalculation(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication), batchId));
    }

    @GetMapping("/recalculation-batches/{batchId}")
    public Result<RecalculationBatchView> batch(
            Authentication authentication, @PathVariable String batchId) {
        return Result.success(lifecycleService.batch(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), batchId));
    }

    private static PeriodPolicyView periodPolicy(PeriodPolicyVersion value) {
        return new PeriodPolicyView(value.policyId(), value.versionId(), value.versionNo(),
                value.buildingId(), value.timezoneId(), value.closingDelayHours(), value.lockMode(),
                value.status(), value.sourceType(), value.evidenceReference(), value.effectiveFrom(),
                value.effectiveTo(), value.configRevision(), value.createdBy(), value.createdAt(),
                value.approvedBy(), value.approvedAt());
    }

    private static ExceptionPolicyView exceptionPolicy(ExceptionPolicyVersion value) {
        return new ExceptionPolicyView(value.policyId(), value.versionId(), value.versionNo(),
                value.buildingId(), value.issueCode(), value.severity(), value.lockAction(),
                value.maximumAffectedCount(), value.maximumAffectedRatio(),
                value.minimumCoverageRatio(), value.applicableScope(), value.requiresApproval(),
                value.requiredEvidence(), value.status(), value.sourceType(), value.evidenceReference(),
                value.effectiveFrom(), value.effectiveTo(), value.configRevision(), value.createdBy(),
                value.createdAt(), value.approvedBy(), value.approvedAt());
    }
}
