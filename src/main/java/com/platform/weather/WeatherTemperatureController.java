package com.platform.weather;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 独立只读趋势入口；沿用天气模块的实时角色及建筑范围检查，不触发来源任务。 */
@RestController
@SecurityRequirement(name="bearerAuth")
@RequestMapping("/v1/weather/buildings/{building}/temperature-trend")
public class WeatherTemperatureController {
    private final WeatherTemperatureTrendService service;
    public WeatherTemperatureController(WeatherTemperatureTrendService service) {this.service=service;}
    @GetMapping
    public Result<WeatherTemperatureContracts.View> query(Authentication authentication,@PathVariable String building,
            @RequestParam String periodType,@RequestParam String period) {
        return Result.success(service.query(SecurityUser.userId(authentication),SecurityUser.roles(authentication),building,periodType,period));
    }
}
