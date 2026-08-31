package com.platform.energy.activity;

import com.platform.energy.activity.EnergyActivityDataContracts.EnergyActivityApiError;
import com.platform.energy.activity.EnergyActivityDataContracts.RawActivityDataPage;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "多能源活动数据")
@SecurityRequirement(name = "bearerAuth")
@RestController
@Validated
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "读取范围或游标不合法",
                content = @Content(schema = @Schema(implementation = EnergyActivityApiError.class))),
        @ApiResponse(responseCode = "401", description = "未登录",
                content = @Content(schema = @Schema(implementation = EnergyActivityApiError.class))),
        @ApiResponse(responseCode = "403", description = "角色或建筑范围不足",
                content = @Content(schema = @Schema(implementation = EnergyActivityApiError.class))),
        @ApiResponse(responseCode = "409", description = "能源专业属性缺失或未确认",
                content = @Content(schema = @Schema(implementation = EnergyActivityApiError.class))),
        @ApiResponse(responseCode = "503", description = "质量策略或活动数据源暂不可用",
                content = @Content(schema = @Schema(implementation = EnergyActivityApiError.class)))
})
/** HTTP 层只适配身份和查询参数；建筑、能源属性及质量门禁由 Service 强制执行。 */
public class EnergyActivityDataController {
    private final EnergyActivityDataService service;

    @GetMapping("/v1/energy-activity-data/raw-events")
    @ApiResponse(responseCode = "200", description = "返回通过专业属性和质量门禁的原始活动数据页",
            content = @Content(schema = @Schema(implementation = RawActivityDataPage.class)))
    public Result<RawActivityDataPage> rawEvents(
            Authentication authentication,
            @RequestParam String buildingId,
            @RequestParam List<String> pointIds,
            @RequestParam long fromInclusive,
            @RequestParam long toExclusive,
            @RequestParam(required = false) Long afterEventTime,
            @RequestParam(required = false) String afterPointId,
            @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit) {
        return Result.success(service.rawEvents(
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication),
                buildingId,
                pointIds,
                fromInclusive,
                toExclusive,
                afterEventTime,
                afterPointId,
                limit));
    }
}
