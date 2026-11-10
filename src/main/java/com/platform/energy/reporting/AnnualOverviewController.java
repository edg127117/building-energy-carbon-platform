package com.platform.energy.reporting;

import com.platform.carbon.AnnualOverviewService;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/energy-reporting/buildings/{buildingId}/annual-overview")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 建筑授权后的只读概览；不会触发核算、封账或发布。 */
public class AnnualOverviewController {
    private final AnnualOverviewService service;
    @GetMapping
    public Result<AnnualOverviewService.Response> overview(Authentication authentication,@PathVariable String buildingId,
            @RequestParam int year,@RequestParam String mode,@RequestParam(required=false) Integer throughMonth,
            @RequestParam(defaultValue="Asia/Shanghai") String timezoneId) {
        return Result.success(service.overview(SecurityUser.userId(authentication),SecurityUser.roles(authentication),buildingId,year,mode,throughMonth,timezoneId));
    }
}
