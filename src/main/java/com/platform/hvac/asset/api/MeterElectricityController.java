package com.platform.hvac.asset.api;

import com.platform.framework.common.Result;
import com.platform.hvac.asset.service.MeterElectricityService;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/assets")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "单表用电分析")
/** 只读分析入口；管理员权限与电表建筑归属在服务端再次校验。 */
public class MeterElectricityController {
    private final MeterElectricityService service;

    @GetMapping("/equipment/{id}/electricity-analysis")
    public Result<MeterElectricityContracts.View> analyze(@PathVariable String id,
            @RequestParam(required = false) String buildingId,
            @RequestParam(defaultValue = "7") int days, Authentication auth) {
        return Result.success(service.analyze(id, buildingId, days, SecurityUser.roles(auth)));
    }
}
