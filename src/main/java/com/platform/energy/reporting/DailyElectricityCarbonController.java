package com.platform.energy.reporting;

import com.platform.carbon.DailyElectricityCarbonContracts;
import com.platform.carbon.DailyElectricityCarbonService;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/energy-reporting/buildings/{buildingId}/daily-trends")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 整月日序列只读入口；保留既有角色和建筑范围，不包含计算、导入或配置命令。 */
public class DailyElectricityCarbonController {
    private final DailyElectricityCarbonService service;
    public DailyElectricityCarbonController(DailyElectricityCarbonService service) {this.service=service;}
    @GetMapping
    public Result<DailyElectricityCarbonContracts.View> query(Authentication authentication,@PathVariable String buildingId,@RequestParam String month) {
        return Result.success(service.query(SecurityUser.userId(authentication),SecurityUser.roles(authentication),buildingId,month));
    }
}
