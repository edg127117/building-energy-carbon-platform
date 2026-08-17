package com.platform.iot.onboarding.api;

import com.platform.framework.common.Result;
import com.platform.framework.web.PageResponse;
import com.platform.iot.onboarding.DeviceProductService;
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

@Tag(name = "设备产品")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/v1/device-products")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "请求或产品模板校验失败",
                content = @Content(schema = @Schema(implementation = OnboardingApiError.class))),
        @ApiResponse(responseCode = "403", description = "非平台管理员",
                content = @Content(schema = @Schema(implementation = OnboardingApiError.class))),
        @ApiResponse(responseCode = "404", description = "产品不存在",
                content = @Content(schema = @Schema(implementation = OnboardingApiError.class))),
        @ApiResponse(responseCode = "409", description = "状态或唯一键冲突",
                content = @Content(schema = @Schema(implementation = OnboardingApiError.class)))
})
/** 产品模板的版本化管理入口，仅负责 HTTP 契约与身份提取。 */
public class DeviceProductController {
    private final DeviceProductService service;

    @Operation(summary = "分页查询产品模板")
    @GetMapping
    public Result<PageResponse<DeviceProductContracts.ListItemView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            Authentication authentication) {
        return Result.success(service.list(page, size, status, keyword, SecurityUser.roles(authentication)));
    }

    @Operation(summary = "查询产品及测点模板详情")
    @GetMapping("/{productId}")
    public Result<DeviceProductContracts.DetailView> detail(
            @PathVariable String productId, Authentication authentication) {
        return Result.success(service.detail(productId, SecurityUser.roles(authentication)));
    }

    @Operation(summary = "创建产品草稿")
    @PostMapping
    public Result<DeviceProductContracts.DetailView> create(
            @Valid @RequestBody DeviceProductContracts.CreateRequest request,
            Authentication authentication) {
        return Result.success(service.create(
                request, SecurityUser.userId(authentication), SecurityUser.roles(authentication)));
    }

    @Operation(summary = "更新未使用的产品草稿")
    @PutMapping("/{productId}")
    public Result<DeviceProductContracts.DetailView> update(
            @PathVariable String productId,
            @Valid @RequestBody DeviceProductContracts.UpdateRequest request,
            Authentication authentication) {
        return Result.success(service.update(
                productId, request, SecurityUser.userId(authentication), SecurityUser.roles(authentication)));
    }

    @Operation(summary = "复制为新的产品草稿")
    @PostMapping("/{productId}/copy")
    public Result<DeviceProductContracts.DetailView> copy(
            @PathVariable String productId,
            @Valid @RequestBody DeviceProductContracts.CopyRequest request,
            Authentication authentication) {
        return Result.success(service.copy(
                productId, request, SecurityUser.userId(authentication), SecurityUser.roles(authentication)));
    }

    @Operation(summary = "启用产品模板")
    @PostMapping("/{productId}/enable")
    public Result<DeviceProductContracts.DetailView> enable(
            @PathVariable String productId, Authentication authentication) {
        return Result.success(service.enable(
                productId, SecurityUser.userId(authentication), SecurityUser.roles(authentication)));
    }

    @Operation(summary = "停用产品模板")
    @PostMapping("/{productId}/disable")
    public Result<DeviceProductContracts.DetailView> disable(
            @PathVariable String productId, Authentication authentication) {
        return Result.success(service.disable(
                productId, SecurityUser.userId(authentication), SecurityUser.roles(authentication)));
    }
}
