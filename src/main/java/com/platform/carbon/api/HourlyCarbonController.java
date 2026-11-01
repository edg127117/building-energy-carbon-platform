package com.platform.carbon.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.carbon.HourlyCarbonContracts;
import com.platform.carbon.HourlyCarbonService;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@Tag(name="小时电力碳监测")
@SecurityRequirement(name="bearerAuth")
@RestController
@RequestMapping("/v1/carbon-management")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 小时监测与正式月度核算分开发布；浏览器只读查询不具备隐式计算副作用。 */
public class HourlyCarbonController {
    private final HourlyCarbonService service;
    @PostMapping("/hourly-calculations")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<HourlyCarbonContracts.View> calculate(Authentication auth,@Valid @RequestBody HourlyCarbonContracts.Request request) {
        return Result.success(service.calculate(SecurityUser.userId(auth),SecurityUser.roles(auth),request));
    }
    @GetMapping("/buildings/{buildingId}/hourly")
    public Result<HourlyCarbonContracts.View> query(Authentication auth,@PathVariable String buildingId,
            @RequestParam LocalDate date,@RequestParam(required=false) Integer revision) {
        return Result.success(service.query(SecurityUser.userId(auth),SecurityUser.roles(auth),buildingId,date,revision));
    }
    @GetMapping("/hourly-calculations/{batchId}/evidence")
    public Result<JsonNode> evidence(Authentication auth,@PathVariable String batchId) {
        return Result.success(service.evidence(SecurityUser.userId(auth),SecurityUser.roles(auth),batchId));
    }
}
