package com.platform.iot.daikin.monitoring.query;

import com.platform.framework.common.Result;
import com.platform.framework.web.PageResponse;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
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

import static com.platform.iot.daikin.monitoring.query.DaikinMonitoringQueryDtos.*;

@RestController
@RequestMapping("/v1/hvac-monitoring")
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "大金暖通只读监测")
@RequiredArgsConstructor
/** 只返回平台已绑定设备的范围受控状态；温度沿独立质量门禁接口查询。 */
public class DaikinMonitoringQueryController {
    private final DaikinMonitoringQueryService service;

    @GetMapping("/buildings/{buildingId}/devices")
    public Result<PageResponse<DeviceListItem>> devices(Authentication authentication,
            @PathVariable String buildingId, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String spaceId,
            @RequestParam(required = false) String kind) {
        return Result.success(service.devices(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, page, size, spaceId, kind));
    }

    @Operation(summary = "查询设备非温度当前状态；温度由独立temperatures/current接口返回")
    @GetMapping({"/devices/{equipmentId}", "/devices/{equipmentId}/current"})
    public Result<DeviceCurrentView> current(Authentication authentication,
            @PathVariable String equipmentId) {
        return Result.success(service.current(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), equipmentId));
    }

    @GetMapping("/devices/{equipmentId}/state-events")
    public Result<CursorPage<StateEventView>> stateEvents(Authentication authentication,
            @PathVariable String equipmentId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(service.stateEvents(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), equipmentId, cursor, limit));
    }

    @GetMapping("/buildings/{buildingId}/exceptions/current")
    public Result<CursorPage<ExceptionView>> currentExceptions(Authentication authentication,
            @PathVariable String buildingId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(service.currentExceptions(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, cursor, limit));
    }

    @GetMapping("/buildings/{buildingId}/exceptions/history")
    public Result<CursorPage<ExceptionView>> exceptionHistory(Authentication authentication,
            @PathVariable String buildingId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(service.exceptionHistory(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, cursor, limit));
    }
}
