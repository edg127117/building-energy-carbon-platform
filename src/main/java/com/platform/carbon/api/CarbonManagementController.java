package com.platform.carbon.api;

import com.platform.carbon.CarbonCalculationService;
import com.platform.carbon.CarbonModels.*;
import com.platform.carbon.CarbonRecalculationService;
import com.platform.carbon.CarbonRuleService;
import com.platform.carbon.api.CarbonContracts.*;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "碳管理后端")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/v1/carbon-management")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "碳规则、周期或参数无效",
                content = @Content(schema = @Schema(implementation = CarbonApiError.class))),
        @ApiResponse(responseCode = "403", description = "角色、动态职责或建筑范围不足",
                content = @Content(schema = @Schema(implementation = CarbonApiError.class))),
        @ApiResponse(responseCode = "404", description = "碳规则、结果或重算批次不存在",
                content = @Content(schema = @Schema(implementation = CarbonApiError.class))),
        @ApiResponse(responseCode = "409", description = "版本、因子匹配、幂等或审批状态冲突",
                content = @Content(schema = @Schema(implementation = CarbonApiError.class))),
        @ApiResponse(responseCode = "504", description = "同步碳计算超过约定超时",
                content = @Content(schema = @Schema(implementation = CarbonApiError.class)))
})
/** 暴露不依赖前端页面的碳规则、计算、追溯和自动重算审批契约。 */
public class CarbonManagementController {
    private final CarbonRuleService ruleService;
    private final CarbonCalculationService calculationService;
    private final CarbonRecalculationService recalculationService;

    @PostMapping("/factor-sources")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<FactorSourceView> createSource(Authentication authentication,
                                                 @Valid @RequestBody CreateFactorSourceRequest request) {
        return Result.success(source(ruleService.createSource(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request)));
    }

    @GetMapping("/factor-sources")
    public Result<List<FactorSourceView>> listSources(Authentication authentication) {
        return Result.success(ruleService.listSources(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication)).stream().map(CarbonManagementController::source)
                .toList());
    }

    @PostMapping("/factors")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<FactorVersionView> createFactor(Authentication authentication,
                                                  @Valid @RequestBody CreateFactorVersionRequest request) {
        return Result.success(factor(ruleService.createFactor(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request)));
    }

    @GetMapping("/factors")
    public Result<List<FactorVersionView>> listFactors(Authentication authentication) {
        return Result.success(ruleService.listFactors(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication)).stream().map(CarbonManagementController::factor)
                .toList());
    }

    @PostMapping("/factors/{versionId}/review")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<FactorVersionView> reviewFactor(Authentication authentication,
                                                  @PathVariable String versionId,
                                                  @Valid @RequestBody ReviewRequest request) {
        return Result.success(factor(ruleService.reviewFactor(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, request)));
    }

    @PostMapping("/factors/{versionId}/activate")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<FactorVersionView> activateFactor(Authentication authentication,
                                                    @PathVariable String versionId,
                                                    @Valid @RequestBody LifecycleRequest request) {
        return Result.success(factor(ruleService.activateFactor(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, request)));
    }

    @PostMapping("/factors/{versionId}/disable")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<FactorVersionView> disableFactor(Authentication authentication,
                                                   @PathVariable String versionId,
                                                   @Valid @RequestBody LifecycleRequest request) {
        return Result.success(factor(ruleService.disableFactor(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, request)));
    }

    @PostMapping("/denominators")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<DenominatorVersionView> createDenominator(Authentication authentication,
                                                            @Valid @RequestBody CreateDenominatorRequest request) {
        return Result.success(denominator(ruleService.createDenominator(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication), request)));
    }

    @GetMapping("/denominators")
    public Result<List<DenominatorVersionView>> listDenominators(
            Authentication authentication, @RequestParam String buildingId) {
        return Result.success(ruleService.listDenominators(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId).stream()
                .map(CarbonManagementController::denominator).toList());
    }

    @PostMapping("/denominators/{versionId}/review")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<DenominatorVersionView> reviewDenominator(Authentication authentication,
                                                            @PathVariable String versionId,
                                                            @Valid @RequestBody ReviewRequest request) {
        return Result.success(denominator(ruleService.reviewDenominator(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                versionId, request)));
    }

    @PostMapping("/denominators/{versionId}/activate")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<DenominatorVersionView> activateDenominator(Authentication authentication,
                                                              @PathVariable String versionId,
                                                              @Valid @RequestBody LifecycleRequest request) {
        return Result.success(denominator(ruleService.activateDenominator(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                versionId, request)));
    }

    @PostMapping("/denominators/{versionId}/disable")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<DenominatorVersionView> disableDenominator(Authentication authentication,
                                                             @PathVariable String versionId,
                                                             @Valid @RequestBody LifecycleRequest request) {
        return Result.success(denominator(ruleService.disableDenominator(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                versionId, request)));
    }

    @PostMapping("/calculations")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<CalculationBatchView> runCalculation(Authentication authentication,
                                                       @Valid @RequestBody RunCalculationRequest request) {
        return Result.success(calculation(calculationService.run(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication), request)));
    }

    @GetMapping("/calculations/{batchId}")
    public Result<CalculationBatchView> calculation(Authentication authentication,
                                                     @PathVariable String batchId) {
        return Result.success(calculation(calculationService.detail(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication), batchId)));
    }

    @PostMapping("/recalculations/manual")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ManualRecalculationAcceptedView> manualRecalculation(
            Authentication authentication,
            @Valid @RequestBody ManualRecalculationRequest request) {
        return Result.success(recalculationService.submitManual(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication), request));
    }

    @GetMapping("/recalculations/{batchId}")
    public Result<RecalculationBatchView> recalculation(Authentication authentication,
                                                        @PathVariable String batchId) {
        RecalculationBatch batch = recalculationService.detail(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), batchId);
        return Result.success(recalculation(batch, recalculationService.items(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication), batchId)));
    }

    @PostMapping("/recalculations/{batchId}/approve")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<RecalculationBatchView> approveRecalculation(
            Authentication authentication, @PathVariable String batchId,
            @Valid @RequestBody ApproveRecalculationRequest request) {
        RecalculationBatch batch = recalculationService.approve(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), batchId, request);
        return Result.success(recalculation(batch, recalculationService.items(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication), batchId)));
    }

    @PostMapping("/recalculations/{batchId}/reject")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<RecalculationBatchView> rejectRecalculation(
            Authentication authentication, @PathVariable String batchId,
            @Valid @RequestBody ApproveRecalculationRequest request) {
        RecalculationBatch batch = recalculationService.reject(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), batchId, request);
        return Result.success(recalculation(batch, recalculationService.items(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication), batchId)));
    }

    @PostMapping("/recalculations/items/{itemId}/recover")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ManualRecalculationAcceptedView> recoverDeadItem(
            Authentication authentication, @PathVariable String itemId,
            @Valid @RequestBody RecoverDeadItemRequest request) {
        return Result.success(recalculationService.recoverDead(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                itemId, request));
    }

    private static FactorSourceView source(FactorSourceVersion value) {
        return new FactorSourceView(value.sourceId(), value.sourceCode(), value.sourceVersionId(),
                value.versionNo(), value.sourceName(), value.publisher(), value.documentReference(),
                value.publicationYear(), value.publishedOn(), value.applicabilityNote(),
                value.evidenceReference(), value.usageNature().name(), value.createdBy(),
                value.createdAt());
    }

    private static FactorVersionView factor(FactorVersion value) {
        return new FactorVersionView(value.factorId(), value.factorCode(),
                value.factorVersionId(), value.versionNo(), value.scopeType().name(),
                value.energyItemCode(), value.category().name(), value.resultBasis(),
                value.gasCode(), value.gasCoverage(), value.sourceVersionId(),
                value.applicabilityLevel().name(), value.buildingId(), value.regionCode(),
                value.inputUnitCode(), value.standardConditionCode(), value.usageNature().name(),
                value.status().name(), value.effectiveFrom(), value.effectiveTo(),
                value.formulaVersionId(), value.roundingPolicyVersionId(), value.configRevision(),
                value.createdBy(), value.createdAt(), value.reviewedBy(), value.reviewedAt(),
                value.reviewComment(), value.activatedBy(), value.activatedAt(),
                value.components().stream().map(component -> new FactorComponentView(
                        component.componentId(), component.type().name(), component.value(),
                        component.unit(), component.sourceVersionId(),
                        component.evidenceReference())).toList());
    }

    private static DenominatorVersionView denominator(DenominatorVersion value) {
        return new DenominatorVersionView(value.denominatorId(), value.denominatorVersionId(),
                value.versionNo(), value.buildingId(), value.type().name(), value.value(),
                value.unitCode(), value.sourceReference(), value.evidenceReference(),
                value.usageNature().name(), value.status().name(), value.effectiveFrom(),
                value.effectiveTo(), value.configRevision(), value.createdBy(), value.createdAt(),
                value.reviewedBy(), value.reviewedAt(), value.reviewComment(), value.activatedBy(),
                value.activatedAt());
    }

    private CalculationBatchView calculation(CalculationDetail detail) {
        CalculationBatch batch = detail.batch();
        List<CalculationFailureView> failures = detail.failures()
                .stream().map(value -> new CalculationFailureView(value.activity().snapshotId(),
                        value.activity().energyItemCode(), value.activity().startInclusive(),
                        value.activity().endExclusive(), value.errorCode(), value.errorMessage(),
                        value.activity().evidenceHash())).toList();
        return new CalculationBatchView(batch.batchId(), batch.buildingId(),
                batch.periodType().name(), batch.periodStart(), batch.periodEnd(), batch.timezoneId(),
                batch.resultNature().name(), batch.publicationStatus(), batch.status(),
                batch.idempotencyKey(), batch.supersedesBatchId(), batch.startedAt(),
                batch.completedAt(), batch.durationMs(), batch.snapshotCount(), batch.detailCount(),
                batch.slowCalculation(), batch.safeErrorCode(), batch.safeErrorMessage(),
                detail.items().stream().map(value -> new CalculationItemView(
                        value.calculationItemId(), value.snapshotId(), value.energyItemCode(),
                        value.scopeType().name(), value.activityQuantity(), value.activityUnitCode(),
                        value.factorVersionId(), value.formulaVersionId(), value.gwpVersionId(),
                        value.emissionKgCo2e(), value.matchReason(), value.evidenceHash())).toList(),
                failures, detail.summaries().stream().map(value -> new SummaryView(
                        value.metricCode(), value.dimensionCode(), value.rawValue(),
                        value.finalValue(), value.unitCode(), value.denominatorVersionId(),
                        value.unavailableReason(), value.evidenceHash())).toList());
    }

    private static RecalculationBatchView recalculation(
            RecalculationBatch batch, List<RecalculationItem> items) {
        return new RecalculationBatchView(batch.batchId(), batch.triggerReason(),
                batch.organizationBoundary(), batch.resultNature().name(), batch.status(),
                batch.scopeFrozen(), batch.itemCount(), batch.eligibleItemCount(),
                batch.initiatedBy(), batch.approvedBy(), batch.approvedAt(), batch.reviewComment(),
                batch.safeErrorCode(), items.stream().map(value -> new RecalculationItemView(
                        value.itemId(), value.buildingId(), value.accountingYear(),
                        value.oldCalculationBatchId(), value.candidateCalculationBatchId(),
                        value.status(), value.approvalEligible(), value.retryCount(),
                        value.nextAttemptAt(), value.safeErrorCode(), value.safeErrorMessage()))
                        .toList());
    }
}
