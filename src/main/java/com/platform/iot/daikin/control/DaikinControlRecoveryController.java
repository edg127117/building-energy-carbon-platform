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
@RequestMapping("/v1/hvac-control")
@PreAuthorize("isAuthenticated()")
/** 只读异常恢复入口；TEST 有效账号一次读取后继续，其他环境由管理员核验现场基线。 */
public class DaikinControlRecoveryController {
    private final DaikinControlRecoveryService service;
    public DaikinControlRecoveryController(DaikinControlRecoveryService service) { this.service = service; }

    @GetMapping("/devices/{equipmentId}/recovery")
    public Result<RecoveryCandidate> candidate(Authentication auth, @PathVariable String equipmentId) {
        return Result.success(service.candidate(SecurityUser.userId(auth), equipmentId));
    }
    @PostMapping("/operations/{operationId}/recovery/observe")
    public Result<RecoveryProgress> observe(Authentication auth, @PathVariable String operationId,
                                           @RequestBody RecoveryBaseline body, HttpServletRequest request) {
        return Result.success(service.observe(SecurityUser.userId(auth), operationId, body, TraceContext.from(request)));
    }

    @PostMapping("/operations/{operationId}/recovery/continue")
    public Result<Operation> continueCurrent(Authentication auth, @PathVariable String operationId,
                                             @RequestBody Close body, HttpServletRequest request) {
        return Result.success(service.continueCurrent(SecurityUser.userId(auth), operationId, body, TraceContext.from(request)));
    }
    @PostMapping("/operations/{operationId}/recovery/complete")
    public Result<Operation> complete(Authentication auth, @PathVariable String operationId,
                                      @RequestBody RecoverCurrentBaseline body, HttpServletRequest request) {
        return Result.success(service.complete(SecurityUser.userId(auth), operationId, body, TraceContext.from(request)));
    }
}
