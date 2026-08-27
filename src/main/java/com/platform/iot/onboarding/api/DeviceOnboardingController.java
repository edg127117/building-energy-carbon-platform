package com.platform.iot.onboarding.api;

import com.platform.audit.AuditGovernanceErrors;
import com.platform.framework.common.Result;
import com.platform.framework.web.PageResponse;
import com.platform.iot.onboarding.DeviceOnboardingService;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "设备接入")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/v1/device-onboarding")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "绑定参数或归属校验失败",
                content = @Content(schema = @Schema(implementation = OnboardingApiError.class))),
        @ApiResponse(responseCode = "403", description = "非平台管理员",
                content = @Content(schema = @Schema(implementation = OnboardingApiError.class))),
        @ApiResponse(responseCode = "404", description = "待绑定设备或身份不存在",
                content = @Content(schema = @Schema(implementation = OnboardingApiError.class))),
        @ApiResponse(responseCode = "409", description = "状态、身份、测点或别名冲突",
                content = @Content(schema = @Schema(implementation = OnboardingApiError.class))),
        @ApiResponse(responseCode = "503", description = "数据库已提交但运行时配置尚未生效",
                content = @Content(schema = @Schema(implementation = OnboardingApiError.class)))
})
/** 待绑定查询、绑定和身份启停的版本化 HTTP 入口。 */
public class DeviceOnboardingController {
    private final DeviceOnboardingService service;

    @Operation(summary = "分页查询待绑定设备；列表身份值脱敏")
    @GetMapping("/pending")
    public Result<PageResponse<DeviceOnboardingContracts.PendingListItemView>> pending(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String identity,
            @RequestParam(required = false) String profileCode,
            Authentication authentication) {
        return Result.success(service.listPending(
                page, size, status, identity, profileCode, SecurityUser.roles(authentication)));
    }

    @Operation(summary = "查询待绑定设备及规范化最新样例")
    @GetMapping("/pending/{pendingId}")
    public Result<DeviceOnboardingContracts.PendingDetailView> pendingDetail(
            @PathVariable String pendingId, Authentication authentication) {
        return Result.success(service.pendingDetail(pendingId, SecurityUser.roles(authentication)));
    }

    @Operation(summary = "忽略或恢复待绑定设备")
    @PutMapping("/pending/{pendingId}/status")
    public Result<DeviceOnboardingContracts.PendingDetailView> updatePendingStatus(
            @PathVariable String pendingId,
            @Valid @RequestBody DeviceOnboardingContracts.PendingStatusRequest request,
            Authentication authentication) {
        return Result.success(service.updatePendingStatus(
                pendingId, request, SecurityUser.userId(authentication), SecurityUser.roles(authentication)));
    }

    @Operation(summary = "旧正式绑定入口；稳定拒绝并要求创建后台敏感变更申请")
    @PostMapping("/pending/{pendingId}/bind")
    public Result<DeviceOnboardingContracts.BindResultView> bind(
            @PathVariable String pendingId,
            @Valid @RequestBody DeviceOnboardingContracts.BindRequest request,
            Authentication authentication) {
        throw AuditGovernanceErrors.reviewRequired();
    }

    @Operation(summary = "旧身份启用入口；稳定拒绝并要求创建后台敏感变更申请")
    @PostMapping("/identities/{identityId}/activate")
    public Result<DeviceOnboardingContracts.IdentityStatusView> activate(
            @PathVariable String identityId, Authentication authentication) {
        throw AuditGovernanceErrors.reviewRequired();
    }

    @Operation(summary = "旧身份停用入口；稳定拒绝并要求创建后台敏感变更申请")
    @PostMapping("/identities/{identityId}/deactivate")
    public Result<DeviceOnboardingContracts.IdentityStatusView> deactivate(
            @PathVariable String identityId, Authentication authentication) {
        throw AuditGovernanceErrors.reviewRequired();
    }
}
