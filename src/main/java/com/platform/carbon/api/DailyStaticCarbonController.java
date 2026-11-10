package com.platform.carbon.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.carbon.DailyStaticCarbonContracts;
import com.platform.carbon.DailyStaticCarbonService;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
@RestController
@RequestMapping("/v1/carbon-management")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 日静态碳计算、历史结果与冻结证据入口；角色校验后由服务核对建筑范围。 */
public class DailyStaticCarbonController {
    private final DailyStaticCarbonService service;
    public DailyStaticCarbonController(DailyStaticCarbonService service){this.service=service;}
    @PostMapping("/daily-static-calculations")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<DailyStaticCarbonContracts.View> calculate(Authentication auth,@Valid @RequestBody DailyStaticCarbonContracts.Request request) {
        return Result.success(service.calculate(SecurityUser.userId(auth),SecurityUser.roles(auth),request));
    }
    @GetMapping("/buildings/{buildingId}/daily-static")
    public Result<DailyStaticCarbonContracts.View> query(Authentication auth,@PathVariable String buildingId,
            @RequestParam LocalDate date,@RequestParam(required=false) Integer revision) {
        return Result.success(service.query(SecurityUser.userId(auth),SecurityUser.roles(auth),buildingId,date,revision));
    }
    @GetMapping("/daily-static-calculations/{batchId}/evidence")
    public Result<JsonNode> evidence(Authentication auth,@PathVariable String batchId) {
        return Result.success(service.evidence(SecurityUser.userId(auth),SecurityUser.roles(auth),batchId));
    }
}
