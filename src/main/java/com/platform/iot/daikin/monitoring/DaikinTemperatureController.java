package com.platform.iot.daikin.monitoring;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/hvac-monitoring/devices/{equipmentId}/temperatures")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
/** 已授权设备的温度事实查询；游标为上一页末条平台观测毫秒时间，不提供插值或控制入口。 */
public class DaikinTemperatureController {
    private final DaikinTemperatureQueryService service;

    @GetMapping
    public Result<DaikinTemperatureQueryService.History> history(Authentication authentication,
            @PathVariable String equipmentId, @RequestParam String field,
            @RequestParam long from, @RequestParam long to,
            @RequestParam(required = false) Long after, @RequestParam(defaultValue = "500") int limit) {
        return Result.success(service.history(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                equipmentId, field, from, to, after, limit));
    }

    @GetMapping("/current")
    public Result<DaikinTemperatureQueryService.Current> current(Authentication authentication,
            @PathVariable String equipmentId, @RequestParam String field) {
        return Result.success(service.current(SecurityUser.userId(authentication), SecurityUser.roles(authentication), equipmentId, field));
    }
}
