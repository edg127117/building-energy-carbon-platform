package com.platform.iot.energymetadata.api;

import com.platform.framework.common.Result;
import com.platform.framework.web.PageResponse;
import com.platform.iot.energymetadata.EnergyPointProfileService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.platform.iot.energymetadata.api.EnergyPointProfileContracts.*;

@Tag(name = "能源测点专业属性")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "专业属性校验失败",
                content = @Content(schema = @Schema(implementation = EnergyMetadataApiError.class))),
        @ApiResponse(responseCode = "403", description = "角色或建筑范围不足",
                content = @Content(schema = @Schema(implementation = EnergyMetadataApiError.class))),
        @ApiResponse(responseCode = "404", description = "测点或能源属性不存在",
                content = @Content(schema = @Schema(implementation = EnergyMetadataApiError.class))),
        @ApiResponse(responseCode = "409", description = "重复配置或并发修订冲突",
                content = @Content(schema = @Schema(implementation = EnergyMetadataApiError.class)))
})
/** Controller 只接收身份和 DTO；权限、归属、并发及审计在 Service 事务内复核。 */
public class EnergyPointProfileController {
    private final EnergyPointProfileService service;

    @GetMapping("/v1/energy-point-profiles")
    public Result<PageResponse<ProfileView>> list(
            Authentication authentication,
            @RequestParam String buildingId,
            @RequestParam(required = false) String pointId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(service.list(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, pointId, page, size));
    }

    @GetMapping("/v1/energy-point-profiles/{profileId}")
    public Result<ProfileView> detail(Authentication authentication, @PathVariable String profileId) {
        return Result.success(service.detail(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), profileId));
    }

    @PostMapping("/v1/energy-point-profiles")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ProfileView> create(Authentication authentication,
                                      @Valid @RequestBody CreateRequest request) {
        return Result.success(service.create(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }

    @PutMapping("/v1/energy-point-profiles/{profileId}")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ProfileView> update(Authentication authentication,
                                      @PathVariable String profileId,
                                      @Valid @RequestBody UpdateRequest request) {
        return Result.success(service.update(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), profileId, request));
    }

    @GetMapping("/v1/energy-point-profiles/options")
    public Result<OptionsView> options(Authentication authentication) {
        return Result.success(service.options(SecurityUser.roles(authentication)));
    }

    @GetMapping("/v1/energy-point-profiles/collection-context")
    public Result<CollectionContextView> collectionContext(
            Authentication authentication,
            @RequestParam String sourceId,
            @RequestParam String aliasId) {
        return Result.success(service.collectionContext(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), sourceId, aliasId));
    }
}
