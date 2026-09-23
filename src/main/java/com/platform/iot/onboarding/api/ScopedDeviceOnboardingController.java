package com.platform.iot.onboarding.api;

import com.platform.framework.common.Result;
import com.platform.framework.web.PageResponse;
import com.platform.iot.onboarding.ScopedDeviceOnboardingService;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/operations/device-onboarding")
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "运维厂家设备接入")
@RequiredArgsConstructor
/** 只开放范围受控的目录查询和申请；审核执行仍留在原管理员敏感变更入口。 */
public class ScopedDeviceOnboardingController {
    private final ScopedDeviceOnboardingService service;

    @Operation(summary = "分页查询授权建筑的厂家待接入设备")
    @GetMapping("/pending")
    public Result<PageResponse<DeviceOnboardingContracts.PendingListItemView>> list(Authentication authentication,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return Result.success(service.list(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                page, size, status));
    }

    @GetMapping("/pending/{pendingId}")
    public Result<ScopedDeviceOnboardingService.DirectoryDetail> detail(Authentication authentication,
            @PathVariable String pendingId) {
        return Result.success(service.directoryDetail(SecurityUser.userId(authentication), SecurityUser.roles(authentication), pendingId));
    }

    @PutMapping("/pending/{pendingId}/status")
    public Result<DeviceOnboardingContracts.PendingDetailView> status(Authentication authentication,
            @PathVariable String pendingId, @Valid @RequestBody DeviceOnboardingContracts.PendingStatusRequest request) {
        return Result.success(service.updateStatus(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                pendingId, request));
    }

    @GetMapping("/pending/{pendingId}/products")
    public Result<PageResponse<DeviceProductContracts.ListItemView>> products(Authentication authentication,
            @PathVariable String pendingId, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(service.products(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                pendingId, page, size));
    }

    @GetMapping("/pending/{pendingId}/products/{productId}")
    public Result<DeviceProductContracts.DetailView> product(Authentication authentication,
            @PathVariable String pendingId, @PathVariable String productId) {
        return Result.success(service.product(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                pendingId, productId));
    }

    @GetMapping("/pending/{pendingId}/numeric-sources")
    public Result<List<ScopedDeviceOnboardingService.NumericSource>> numericSources(Authentication authentication,
            @PathVariable String pendingId) {
        return Result.success(service.numericSources(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), pendingId));
    }

    @GetMapping("/pending/{pendingId}/binding-options")
    public Result<ScopedDeviceOnboardingService.BindingOptions> bindingOptions(Authentication authentication,
            @PathVariable String pendingId, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String spaceId,
            @RequestParam(required = false) String systemGroupId,
            @RequestParam(required = false) String productId) {
        return Result.success(service.bindingOptions(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), pendingId, page, size, spaceId, systemGroupId, productId));
    }

    @GetMapping("/pending/{pendingId}/connection")
    public Result<DeviceOnboardingContracts.ConnectionView> connection(Authentication authentication,
            @PathVariable String pendingId) {
        return Result.success(service.connection(SecurityUser.userId(authentication), SecurityUser.roles(authentication), pendingId));
    }

    @Operation(summary = "提交类型化设备绑定申请；不直接绑定或启用")
    @PostMapping("/pending/{pendingId}/binding-requests")
    public Result<ScopedDeviceOnboardingService.BindingApplication> apply(Authentication authentication,
            @PathVariable String pendingId, @Valid @RequestBody BindingRequest request) {
        return Result.success(service.apply(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                pendingId, request.binding(), request.idempotencyKey()));
    }

    @Operation(summary = "按设备独立提交绑定申请；整批参数校验后返回逐项结果")
    @PostMapping("/binding-requests/batch")
    public Result<List<ScopedDeviceOnboardingService.BindingApplication>> batch(Authentication authentication,
            @Valid @RequestBody BatchRequest request) {
        return Result.success(service.applyBatch(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                request.items().stream().map(item -> new ScopedDeviceOnboardingService.BindingItem(
                        item.pendingId(), item.binding(), item.idempotencyKey())).toList()));
    }

    @PostMapping("/pending/{pendingId}/identity-status-requests")
    public Result<ScopedDeviceOnboardingService.BindingApplication> identityStatus(Authentication authentication,
            @PathVariable String pendingId, @Valid @RequestBody IdentityStatusRequest request) {
        return Result.success(service.requestIdentityStatus(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), pendingId, request.targetStatus(), request.idempotencyKey()));
    }

    @Schema(name = "ScopedOnboardingBindingRequest", description = "幂等绑定申请；重试同一请求必须复用幂等键")
    public record BindingRequest(@NotNull @Valid DeviceOnboardingContracts.TypedBindRequest binding,
                                 @NotBlank @Size(max = 100) String idempotencyKey) { }
    @Schema(name = "ScopedOnboardingBatchItem")
    public record BatchItem(@NotBlank @Size(max = 32) String pendingId,
                            @NotNull @Valid DeviceOnboardingContracts.TypedBindRequest binding,
                            @NotBlank @Size(max = 100) String idempotencyKey) { }
    @Schema(name = "ScopedOnboardingBatchRequest")
    public record BatchRequest(@NotEmpty @Size(max = 50) List<@NotNull @Valid BatchItem> items) { }
    public record IdentityStatusRequest(@NotBlank String targetStatus,
                                        @NotBlank @Size(max = 100) String idempotencyKey) { }
}
