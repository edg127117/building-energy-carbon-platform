package com.platform.energy.conversion.api;

import com.platform.energy.conversion.EnergyConversionService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.energy.conversion.api.EnergyConversionContracts.*;

@Tag(name = "折标参数与确定性 tce 计算")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "公式、量纲或参数硬边界校验失败",
                content = @Content(schema = @Schema(implementation = EnergyConversionApiError.class))),
        @ApiResponse(responseCode = "403", description = "角色、职责或建筑范围不足",
                content = @Content(schema = @Schema(implementation = EnergyConversionApiError.class))),
        @ApiResponse(responseCode = "404", description = "引用的字典、公式或参数版本不存在",
                content = @Content(schema = @Schema(implementation = EnergyConversionApiError.class))),
        @ApiResponse(responseCode = "409", description = "规则缺失、冲突或版本证据失效",
                content = @Content(schema = @Schema(implementation = EnergyConversionApiError.class)))
})
/** HTTP 层只开放版本治理和研发模拟；正式活动量计算留给后续聚合工作流。 */
public class EnergyConversionController {
    private final EnergyConversionService service;

    @GetMapping("/v1/energy-conversion/standard-coal-lhv-versions")
    public Result<List<StandardCoalVersionView>> standardCoalVersions(Authentication authentication) {
        return Result.success(service.listStandardCoalVersions(SecurityUser.roles(authentication)));
    }

    @PostMapping("/v1/energy-conversion/standard-coal-lhv-versions")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<StandardCoalVersionView> createStandardCoal(
            Authentication authentication, @Valid @RequestBody CreateStandardCoalVersionRequest request) {
        return Result.success(service.createStandardCoalVersion(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }

    @PostMapping("/v1/energy-conversion/standard-coal-lhv-versions/{versionId}/approve")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<StandardCoalVersionView> approveStandardCoal(
            Authentication authentication, @PathVariable String versionId,
            @Valid @RequestBody ApproveRequest request) {
        return Result.success(service.approveStandardCoal(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, request));
    }

    @GetMapping("/v1/energy-conversion/formula-versions")
    public Result<List<FormulaVersionView>> formulaVersions(Authentication authentication) {
        return Result.success(service.listFormulaVersions(SecurityUser.roles(authentication)));
    }

    @PostMapping("/v1/energy-conversion/formula-versions")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<FormulaVersionView> createFormula(
            Authentication authentication, @Valid @RequestBody CreateFormulaVersionRequest request) {
        return Result.success(service.createFormulaVersion(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }

    @PostMapping("/v1/energy-conversion/formula-versions/{versionId}/approve")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<FormulaVersionView> approveFormula(
            Authentication authentication, @PathVariable String versionId,
            @Valid @RequestBody ApproveRequest request) {
        return Result.success(service.approveFormula(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, request));
    }

    @GetMapping("/v1/energy-conversion/parameter-versions")
    public Result<List<ParameterVersionView>> parameterVersions(Authentication authentication) {
        return Result.success(service.listParameterVersions(SecurityUser.roles(authentication)));
    }

    @PostMapping("/v1/energy-conversion/parameter-versions")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ParameterVersionView> createParameter(
            Authentication authentication, @Valid @RequestBody CreateParameterVersionRequest request) {
        return Result.success(service.createParameterVersion(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }

    @PostMapping("/v1/energy-conversion/parameter-versions/{versionId}/approve")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<ParameterVersionView> approveParameter(
            Authentication authentication, @PathVariable String versionId,
            @Valid @RequestBody ApproveRequest request) {
        return Result.success(service.approveParameter(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), versionId, request));
    }

    @PostMapping("/v1/energy-conversion/simulations")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<SimulationResultView> simulate(
            Authentication authentication, @Valid @RequestBody SimulationRequest request) {
        return Result.success(service.simulate(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), request));
    }

    @GetMapping("/v1/energy-conversion/options")
    public Result<OptionsView> options(Authentication authentication) {
        return Result.success(service.options(SecurityUser.roles(authentication)));
    }
}
