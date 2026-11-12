package com.platform.carbon.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.carbon.HourlyMonitoringContracts;
import com.platform.carbon.HourlyMonitoringService;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/v1/carbon-management/buildings/{buildingId}/hourly-monitoring")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 近似小时大屏读取及证据入口，授权始终限定到当前建筑。 */
public class HourlyMonitoringController {
    private final HourlyMonitoringService service;
    @GetMapping
    public Result<HourlyMonitoringContracts.View> query(Authentication auth,@PathVariable String buildingId,@RequestParam LocalDate date) {
        return Result.success(service.query(SecurityUser.userId(auth),SecurityUser.roles(auth),buildingId,date));
    }
    @GetMapping("/evidence/{id}")
    public Result<JsonNode> evidence(Authentication auth,@PathVariable String buildingId,@PathVariable String id) {
        return Result.success(service.evidence(SecurityUser.userId(auth),SecurityUser.roles(auth),buildingId,id));
    }
}
