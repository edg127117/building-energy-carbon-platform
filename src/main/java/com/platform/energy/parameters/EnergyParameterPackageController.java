package com.platform.energy.parameters;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.platform.energy.parameters.EnergyParameterPackageContracts.*;

@Tag(name = "正式参数包目录与待审核导入")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/v1/energy-parameter-packages")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "参数包、来源、适用期或幂等键无效",
                content = @Content(schema = @Schema(implementation = EnergyParameterPackageApiError.class))),
        @ApiResponse(responseCode = "403", description = "角色或治理职责不足",
                content = @Content(schema = @Schema(implementation = EnergyParameterPackageApiError.class))),
        @ApiResponse(responseCode = "409", description = "预检阻断、版本冲突或幂等键冲突",
                content = @Content(schema = @Schema(implementation = EnergyParameterPackageApiError.class)))
})
/** 入口只编排目录、预检和待审核创建；审批、激活及建筑配置仍由既有治理 API 负责。 */
public class EnergyParameterPackageController {
    private final EnergyParameterPackageService service;

    @GetMapping("/catalog")
    public Result<CatalogView> catalog(Authentication authentication,
                                       @RequestParam(required = false) String buildingId) {
        return Result.success(service.catalog(SecurityUser.userId(authentication), SecurityUser.roles(authentication), buildingId));
    }

    @PostMapping("/preview")
    public Result<PreviewView> preview(Authentication authentication, @RequestBody PackageRequest request) {
        return Result.success(service.preview(SecurityUser.userId(authentication), SecurityUser.roles(authentication), request));
    }

    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ImportView> importPackage(Authentication authentication, @RequestBody PackageRequest request) {
        return Result.success(service.importPackage(SecurityUser.userId(authentication), SecurityUser.roles(authentication), request));
    }
}