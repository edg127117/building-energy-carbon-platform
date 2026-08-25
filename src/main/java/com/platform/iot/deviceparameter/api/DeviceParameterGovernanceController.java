package com.platform.iot.deviceparameter.api;

import com.platform.framework.common.Result;
import com.platform.iot.deviceparameter.DeviceParameterAuthorization;
import com.platform.iot.deviceparameter.DeviceParameterCandidateService;
import com.platform.iot.deviceparameter.DeviceParameterGovernanceService;
import com.platform.iot.deviceparameter.DeviceParameterModels.Candidate;
import com.platform.iot.deviceparameter.DeviceParameterModels.Conflict;
import com.platform.iot.deviceparameter.DeviceParameterModels.Impact;
import com.platform.iot.deviceparameter.DeviceParameterModels.ParameterVersion;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

import static com.platform.iot.deviceparameter.api.DeviceParameterContracts.*;

@Tag(name = "设备参数候选与版本治理")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "候选、快照或时间范围校验失败",
                content = @Content(schema = @Schema(implementation = DeviceParameterApiError.class))),
        @ApiResponse(responseCode = "403", description = "角色或建筑范围不足",
                content = @Content(schema = @Schema(implementation = DeviceParameterApiError.class))),
        @ApiResponse(responseCode = "404", description = "设备、候选、版本或有效时间线不存在",
                content = @Content(schema = @Schema(implementation = DeviceParameterApiError.class))),
        @ApiResponse(responseCode = "409", description = "冲突、职责分离或并发修订失败",
                content = @Content(schema = @Schema(implementation = DeviceParameterApiError.class)))
})
/** HTTP 只接收身份和 DTO；建筑归属、职责分离、状态机与发布事务由 Service 再次校验。 */
public class DeviceParameterGovernanceController {
    private final DeviceParameterCandidateService candidateService;
    private final DeviceParameterGovernanceService governanceService;
    private final DeviceParameterAuthorization authorization;

    @PostMapping("/v1/device-parameters/candidates/manual")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<AllowedView<Candidate>> createManualCandidate(
            Authentication authentication, @Valid @RequestBody CandidateCreateRequest request) {
        Candidate value = candidateService.createManualCandidate(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request);
        return Result.success(new AllowedView<>(value, List.of("SELECT_FOR_DRAFT")));
    }

    @GetMapping("/v1/device-parameters/equipment/{equipmentId}/candidates")
    public Result<List<AllowedView<Candidate>>> listCandidates(
            Authentication authentication,
            @PathVariable String equipmentId,
            @RequestParam(defaultValue = "true") boolean currentOnly) {
        List<String> actions = canMaintain(authentication)
                ? List.of("SELECT_FOR_DRAFT") : List.of();
        return Result.success(candidateService.listCandidates(SecurityUser.userId(authentication),
                        SecurityUser.roles(authentication), equipmentId, currentOnly).stream()
                .map(value -> new AllowedView<>(value, actions)).toList());
    }

    @GetMapping("/v1/device-parameters/equipment/{equipmentId}/conflicts")
    public Result<List<AllowedView<Conflict>>> listConflicts(
            Authentication authentication, @PathVariable String equipmentId) {
        List<String> actions = canMaintain(authentication) ? List.of("RESOLVE") : List.of();
        return Result.success(candidateService.listConflicts(SecurityUser.userId(authentication),
                        SecurityUser.roles(authentication), equipmentId).stream()
                .map(value -> new AllowedView<>(value, actions)).toList());
    }

    @PostMapping("/v1/device-parameter-conflicts/{conflictId}/resolve")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<AllowedView<Conflict>> resolveConflict(
            Authentication authentication,
            @PathVariable String conflictId,
            @Valid @RequestBody ConflictResolutionRequest request) {
        Conflict value = candidateService.resolveConflict(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), conflictId, request);
        return Result.success(new AllowedView<>(value, List.of()));
    }

    @PostMapping("/v1/device-parameters/equipment/{equipmentId}/versions/drafts")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<AllowedView<ParameterVersion>> createDraft(
            Authentication authentication,
            @PathVariable String equipmentId,
            @Valid @RequestBody DraftCreateRequest request) {
        ParameterVersion value = governanceService.createDraft(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), equipmentId, request);
        return Result.success(new AllowedView<>(value, List.of("UPDATE", "SUBMIT")));
    }

    @PutMapping("/v1/device-parameter-versions/{versionId}")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<AllowedView<ParameterVersion>> updateDraft(
            Authentication authentication,
            @PathVariable String versionId,
            @Valid @RequestBody DraftUpdateRequest request) {
        ParameterVersion value = governanceService.updateDraft(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, request);
        return Result.success(new AllowedView<>(value, List.of("UPDATE", "SUBMIT")));
    }

    @PostMapping("/v1/device-parameter-versions/{versionId}/submit")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ReviewView> submit(
            Authentication authentication,
            @PathVariable String versionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SubmitRequest request) {
        return Result.success(governanceService.submit(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, idempotencyKey, request));
    }

    @GetMapping("/v1/device-parameter-reviews")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<List<ReviewView>> listReviews(
            Authentication authentication, @RequestParam String buildingId) {
        return Result.success(governanceService.listReviews(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId));
    }

    @PostMapping("/v1/device-parameter-reviews/{requestId}/approve")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<ReviewView> approve(
            Authentication authentication,
            @PathVariable String requestId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReviewDecisionRequest request) {
        return Result.success(governanceService.approve(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), requestId, idempotencyKey, request));
    }

    @PostMapping("/v1/device-parameter-reviews/{requestId}/withdraw")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ReviewView> withdraw(
            Authentication authentication,
            @PathVariable String requestId,
            @Valid @RequestBody ReasonRequest request) {
        return Result.success(governanceService.withdraw(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), requestId, request.reason()));
    }

    @PostMapping("/v1/device-parameter-reviews/{requestId}/reject")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<ReviewView> reject(
            Authentication authentication,
            @PathVariable String requestId,
            @Valid @RequestBody ReasonRequest request) {
        return Result.success(governanceService.reject(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), requestId, request.reason()));
    }

    @GetMapping("/v1/device-parameters/equipment/{equipmentId}/retroactive-impact")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Impact> previewRetroactiveImpact(
            Authentication authentication,
            @PathVariable String equipmentId,
            @RequestParam String versionId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime effectiveFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime effectiveTo) {
        return Result.success(governanceService.previewRetroactiveImpact(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                equipmentId, versionId, effectiveFrom, effectiveTo));
    }

    @PostMapping("/v1/device-parameters/equipment/{equipmentId}/versions/rollback")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<AllowedView<ParameterVersion>> createRollbackDraft(
            Authentication authentication,
            @PathVariable String equipmentId,
            @Valid @RequestBody RollbackRequest request) {
        ParameterVersion value = governanceService.createRollbackDraft(
                SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                equipmentId, request);
        return Result.success(new AllowedView<>(value, List.of("UPDATE", "SUBMIT")));
    }

    @GetMapping("/v1/device-parameters/equipment/{equipmentId}/effective")
    public Result<AllowedView<EffectiveParameterView>> effective(
            Authentication authentication,
            @PathVariable String equipmentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime businessAt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime knowledgeAt) {
        EffectiveParameterView value = governanceService.effectiveView(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), equipmentId, businessAt, knowledgeAt);
        return Result.success(new AllowedView<>(value, List.of("COPY", "COMPARE")));
    }

    @GetMapping("/v1/device-parameters/equipment/{equipmentId}/versions")
    public Result<List<AllowedView<ParameterVersion>>> listVersions(
            Authentication authentication, @PathVariable String equipmentId) {
        return Result.success(governanceService.listVersions(SecurityUser.userId(authentication),
                        SecurityUser.roles(authentication), equipmentId).stream()
                .map(value -> new AllowedView<>(value, List.of("COMPARE", "COPY"))).toList());
    }

    @GetMapping("/v1/device-parameters/equipment/{equipmentId}/versions/diff")
    public Result<List<DifferenceView>> diff(
            Authentication authentication,
            @PathVariable String equipmentId,
            @RequestParam String beforeVersionId,
            @RequestParam String afterVersionId) {
        return Result.success(governanceService.diff(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), equipmentId, beforeVersionId, afterVersionId));
    }

    private boolean canMaintain(Authentication authentication) {
        try {
            authorization.requireMaintainer(SecurityUser.roles(authentication));
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
