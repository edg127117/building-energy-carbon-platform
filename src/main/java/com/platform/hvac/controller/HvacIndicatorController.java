package com.platform.hvac.controller;

import com.platform.framework.common.Result;
import com.platform.hvac.model.dto.HvacIndicatorDtos;
import com.platform.hvac.service.HvacIndicatorQueryService;
import com.platform.security.SecurityUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HVAC 公式指标的内部只读 API。 */
@RestController
@RequestMapping("/hvac")
@ConditionalOnProperty(
        prefix = "formula", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class HvacIndicatorController {

    private final HvacIndicatorQueryService queryService;

    public HvacIndicatorController(HvacIndicatorQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/buildings/{buildingId}/indicators/latest")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<HvacIndicatorDtos.LatestResponse> latest(
            @PathVariable String buildingId,
            Authentication authentication) {
        return Result.success(queryService.latest(
                buildingId,
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication)));
    }

    @GetMapping("/indicators/{indicatorId}/history")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<HvacIndicatorDtos.HistoryResponse> history(
            @PathVariable String indicatorId,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            Authentication authentication) {
        return Result.success(queryService.history(
                indicatorId,
                from,
                to,
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication)));
    }

    @GetMapping("/indicators/{indicatorId}/calculations/{minuteStart}")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<HvacIndicatorDtos.CalculationDetail> detail(
            @PathVariable String indicatorId,
            @PathVariable long minuteStart,
            Authentication authentication) {
        return Result.success(queryService.detail(
                indicatorId,
                minuteStart,
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication)));
    }
}
