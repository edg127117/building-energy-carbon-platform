package com.platform.energy.reporting;

import com.platform.carbon.MonthlyComparisonService;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/energy-reporting/buildings/{buildingId}/monthly-comparison")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 同一建筑的月度已确认结果比较；查询不创建、封账或重算任何批次。 */
public class MonthlyComparisonController {
    private final MonthlyComparisonService service;

    @GetMapping
    public Result<MonthlyComparisonService.Response> compare(Authentication authentication,
            @PathVariable String buildingId, @RequestParam LocalDate month,
            @RequestParam(defaultValue = "Asia/Shanghai") String timezoneId) {
        return Result.success(service.compare(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                buildingId, month, timezoneId));
    }
}
