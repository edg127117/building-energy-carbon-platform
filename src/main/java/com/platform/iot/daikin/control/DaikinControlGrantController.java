package com.platform.iot.daikin.control;

import com.platform.audit.TraceContext;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import static com.platform.iot.daikin.control.DaikinControlContracts.*;

@RestController
@RequestMapping("/v1/hvac-control/device-grants")
@PreAuthorize("isAuthenticated()")
/** 平台批量开放入口；只能提供平台设备编号，不接受厂家路径、建筑归属或任意动作取值。 */
public class DaikinControlGrantController {
    private final DaikinControlGrantService service;
    public DaikinControlGrantController(DaikinControlGrantService service) { this.service = service; }

    @GetMapping("/access")
    public Result<GrantAccess> access(Authentication auth) { return Result.success(service.permissions(SecurityUser.userId(auth))); }

    @PostMapping("/batch")
    public Result<BatchGrantResult> open(Authentication auth, @RequestBody BatchGrant body, HttpServletRequest request) {
        return Result.success(service.open(SecurityUser.userId(auth), body, TraceContext.from(request)));
    }
}
