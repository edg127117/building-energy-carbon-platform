package com.platform.iot.controller;


import com.platform.framework.common.Result;
import com.platform.iot.service.impl.ControlCommandServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
/**
 * 设备控制 API
 */
@RestController
@RequestMapping("/control")
public class ControlController {

    @Autowired
    private ControlCommandServiceImpl controlCommandService;

    /**
     * 下发控制指令
     * 注意：使用了你搭建的鉴权骨架 @PreAuthorize
     */
    @PreAuthorize("@controlFeature.isEnabled() and hasRole('PLATFORM_ADMIN')")
    @PostMapping("/issue")
    public Result<String> issueControlCommand(
            @RequestParam String deviceId,
            @RequestParam Integer commandType,
            @RequestBody Map<String, Object> params) {

        String commandId = controlCommandService.issueCommand(deviceId, commandType, params);
        return Result.success("指令已下发，追踪号: " + commandId);
    }
}
