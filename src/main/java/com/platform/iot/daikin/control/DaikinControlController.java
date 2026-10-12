package com.platform.iot.daikin.control;

import com.platform.audit.TraceContext;
import com.platform.framework.common.Result;
import com.platform.framework.web.PageResponse;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import static com.platform.iot.daikin.control.DaikinControlContracts.*;

@RestController
@RequestMapping("/v1/hvac-control")
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "空调人工控制")
/** 平台设备级控制入口；请求体不能指定厂家路径、账号或建筑归属。 */
public class DaikinControlController {
    private final DaikinControlService service;
    public DaikinControlController(DaikinControlService service) { this.service = service; }

    @GetMapping("/devices/{equipmentId}/options")
    public Result<Options> options(Authentication auth, @PathVariable String equipmentId) {
        return Result.success(service.options(SecurityUser.userId(auth), equipmentId));
    }
    @PostMapping("/devices/{equipmentId}/operations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<Operation> submit(Authentication auth, @PathVariable String equipmentId, @RequestBody Submit body, HttpServletRequest request) {
        return Result.success(service.submit(SecurityUser.userId(auth), equipmentId, body, TraceContext.from(request)));
    }
    @GetMapping("/operations/{operationId}")
    public Result<Operation> get(Authentication auth, @PathVariable String operationId) {
        return Result.success(service.get(SecurityUser.userId(auth), operationId));
    }
    @GetMapping("/devices/{equipmentId}/operations")
    public Result<PageResponse<Operation>> history(Authentication auth, @PathVariable String equipmentId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return Result.success(service.history(SecurityUser.userId(auth), equipmentId, page, size));
    }
    @PostMapping("/operations/{operationId}/recheck")
    public Result<Operation> recheck(Authentication auth, @PathVariable String operationId) {
        return Result.success(service.recheck(SecurityUser.userId(auth), operationId));
    }
    @PostMapping("/operations/{operationId}/close")
    public Result<Operation> close(Authentication auth, @PathVariable String operationId, @RequestBody Close body) {
        return Result.success(service.close(SecurityUser.userId(auth), operationId, body.reason()));
    }
    @PostMapping("/operations/{operationId}/confirm")
    public Result<Operation> confirm(Authentication auth, @PathVariable String operationId, @RequestBody Close body) {
        return Result.success(service.confirm(SecurityUser.userId(auth), operationId, body.reason()));
    }
    @PostMapping("/operations/{operationId}/release")
    public Result<Operation> release(Authentication auth, @PathVariable String operationId, @RequestBody Close body) {
        return Result.success(service.release(SecurityUser.userId(auth), operationId, body.reason()));
    }
}
