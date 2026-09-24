package com.platform.iot.daikin.runtime;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/hvac-monitoring/devices/{equipmentId}/observed-runtime")
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
/** 平台开机时长只读入口，与厂家统计接口保持独立。 */
public class DaikinObservedRuntimeController {
    private final DaikinObservedRuntimeQueryService service;

    @GetMapping
    @Operation(summary = "查询平台观测开机时长及观测覆盖时长")
    public Result<DaikinObservedRuntimeQueryService.Page> values(Authentication authentication,
            @PathVariable String equipmentId, @RequestParam(defaultValue = "DAY") String granularity,
            @RequestParam(required = false) Long before, @RequestParam(defaultValue = "50") int limit) {
        return Result.success(service.values(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), equipmentId, granularity, before, limit));
    }
}
