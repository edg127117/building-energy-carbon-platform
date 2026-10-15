package com.platform.iot.daikin.monitoring;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static com.platform.iot.daikin.monitoring.DaikinFastMonitoringService.*;

@RestController
@RequestMapping("/v1/hvac-monitoring/devices/{equipmentId}/fast-watch")
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name="bearerAuth")
@RequiredArgsConstructor
/** 浏览器只登记本人有权查看的设备关注，不接收厂家身份、目标值或外部地址。 */
public class DaikinFastMonitoringController {
    private final DaikinFastMonitoringService service;

    @Operation(summary="续期只读状态关注；成功仅代表登记，不代表厂家状态已更新")
    @PostMapping
    public Result<WatchStatus> watch(Authentication auth,@PathVariable String equipmentId,@RequestBody WatchRequest request) {
        return Result.success(service.watch(SecurityUser.userId(auth),equipmentId,request.watchId()));
    }

    @DeleteMapping
    public Result<Boolean> unwatch(Authentication auth,@PathVariable String equipmentId,@RequestParam String watchId) {
        service.unwatch(SecurityUser.userId(auth),equipmentId,watchId);
        return Result.success(true);
    }
}
