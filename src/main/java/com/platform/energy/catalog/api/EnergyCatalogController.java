package com.platform.energy.catalog.api;

import com.platform.energy.catalog.EnergyCatalogService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

import static com.platform.energy.catalog.api.EnergyCatalogContracts.*;

@Tag(name = "能源字典与测点品种绑定")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "字典或单位硬边界校验失败",
                content = @Content(schema = @Schema(implementation = EnergyCatalogApiError.class))),
        @ApiResponse(responseCode = "403", description = "角色、职责或建筑范围不足",
                content = @Content(schema = @Schema(implementation = EnergyCatalogApiError.class))),
        @ApiResponse(responseCode = "404", description = "字典版本、单位或测点不存在",
                content = @Content(schema = @Schema(implementation = EnergyCatalogApiError.class))),
        @ApiResponse(responseCode = "409", description = "审核状态、版本或有效期冲突",
                content = @Content(schema = @Schema(implementation = EnergyCatalogApiError.class)))
})
/** HTTP 层只传递身份和 DTO；专业门禁、版本串行化及审计均在 Service 事务内执行。 */
public class EnergyCatalogController {
    private final EnergyCatalogService service;

    @GetMapping("/v1/energy-catalog/items")
    public Result<List<ItemVersionView>> items(Authentication authentication) {
        return Result.success(service.listItems(SecurityUser.roles(authentication)));
    }

    @PostMapping("/v1/energy-catalog/item-versions")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ItemVersionView> createItem(Authentication authentication,
                                              @Valid @RequestBody CreateItemVersionRequest request) {
        return Result.success(service.createItemVersion(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }

    @PostMapping("/v1/energy-catalog/item-versions/{versionId}/approve")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ItemVersionView> approveItem(Authentication authentication,
                                               @PathVariable String versionId,
                                               @Valid @RequestBody ApproveRequest request) {
        return Result.success(service.approveItem(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, request));
    }

    @GetMapping("/v1/energy-catalog/units")
    public Result<List<UnitVersionView>> units(Authentication authentication) {
        return Result.success(service.listUnits(SecurityUser.roles(authentication)));
    }

    @PostMapping("/v1/energy-catalog/unit-versions")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<UnitVersionView> createUnit(Authentication authentication,
                                              @Valid @RequestBody CreateUnitVersionRequest request) {
        return Result.success(service.createUnitVersion(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }

    @PostMapping("/v1/energy-catalog/unit-versions/{versionId}/approve")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<UnitVersionView> approveUnit(Authentication authentication,
                                               @PathVariable String versionId,
                                               @Valid @RequestBody ApproveRequest request) {
        return Result.success(service.approveUnit(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, request));
    }

    @GetMapping("/v1/energy-catalog/compatibilities")
    public Result<List<CompatibilityVersionView>> compatibilities(Authentication authentication) {
        return Result.success(service.listCompatibilities(SecurityUser.roles(authentication)));
    }

    @PostMapping("/v1/energy-catalog/compatibility-versions")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<CompatibilityVersionView> createCompatibility(
            Authentication authentication,
            @Valid @RequestBody CreateCompatibilityVersionRequest request) {
        return Result.success(service.createCompatibilityVersion(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }

    @PostMapping("/v1/energy-catalog/compatibility-versions/{versionId}/approve")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<CompatibilityVersionView> approveCompatibility(
            Authentication authentication, @PathVariable String versionId,
            @Valid @RequestBody ApproveRequest request) {
        return Result.success(service.approveCompatibility(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, request));
    }

    @GetMapping("/v1/energy-catalog/point-bindings")
    public Result<List<BindingVersionView>> bindings(
            Authentication authentication,
            @RequestParam String buildingId,
            @RequestParam(required = false) String pointId) {
        return Result.success(service.listBindings(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, pointId));
    }

    @GetMapping("/v1/energy-catalog/point-bindings/effective")
    public Result<BindingVersionView> effectiveBinding(
            Authentication authentication,
            @RequestParam String buildingId,
            @RequestParam String pointId,
            @RequestParam LocalDateTime effectiveAt) {
        return Result.success(service.effectiveBinding(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, pointId, effectiveAt));
    }

    @PostMapping("/v1/energy-catalog/point-binding-versions")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<BindingVersionView> createBinding(
            Authentication authentication,
            @Valid @RequestBody CreateBindingVersionRequest request) {
        return Result.success(service.createBindingVersion(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }

    @PostMapping("/v1/energy-catalog/point-binding-versions/{versionId}/approve")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<BindingVersionView> approveBinding(
            Authentication authentication, @PathVariable String versionId,
            @Valid @RequestBody ApproveRequest request) {
        return Result.success(service.approveBinding(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, request));
    }

    @GetMapping("/v1/energy-catalog/options")
    public Result<OptionsView> options(Authentication authentication) {
        return Result.success(service.options(SecurityUser.roles(authentication)));
    }
}
