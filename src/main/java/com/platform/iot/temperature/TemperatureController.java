package com.platform.iot.temperature;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.platform.iot.temperature.TemperatureContracts.*;

@RestController
@RequestMapping("/v1/operations/hvac-temperature-bindings")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
/** 登录态温度接入入口；服务端按菜单、建筑和提交职责校验每个设备，前端不能直接执行配置。 */
public class TemperatureController {
    private final TemperatureBindingService service;
    @PostMapping("/initialization/preview")
    public Result<InitializationPlan> previewInitialization(Authentication auth, @Valid @RequestBody InitializationInput input) {
        return Result.success(service.previewInitialization(SecurityUser.userId(auth), SecurityUser.roles(auth), input));
    }
    @PostMapping("/initialization/jobs")
    public Result<JobView> initialize(Authentication auth, @Valid @RequestBody InitializationRequest request) {
        return Result.success(service.initialize(SecurityUser.userId(auth), SecurityUser.roles(auth), request));
    }
    @GetMapping("/pending/{pendingId}/options")
    public Result<Options> options(Authentication auth, @PathVariable String pendingId) {
        return Result.success(service.options(SecurityUser.userId(auth), SecurityUser.roles(auth), pendingId));
    }
    @PostMapping("/preview")
    public Result<List<PlanView>> preview(Authentication auth, @Valid @RequestBody PreviewRequest request) {
        return Result.success(service.preview(SecurityUser.userId(auth), SecurityUser.roles(auth), request.items()));
    }
    @PostMapping("/rule-requests")
    public Result<Application> rule(Authentication auth, @Valid @RequestBody RuleRequest request) {
        return Result.success(service.ruleRequest(SecurityUser.userId(auth), SecurityUser.roles(auth), request));
    }
    @PostMapping("/batch-jobs")
    public Result<JobView> create(Authentication auth, @Valid @RequestBody BatchRequest request) {
        return Result.success(service.createJob(SecurityUser.userId(auth), SecurityUser.roles(auth), request));
    }
    @GetMapping("/batch-jobs/{jobId}")
    public Result<JobView> get(Authentication auth, @PathVariable String jobId) {
        return Result.success(service.job(SecurityUser.userId(auth), SecurityUser.roles(auth), jobId));
    }
    @GetMapping("/batch-jobs/latest")
    public Result<JobView> latest(Authentication auth, @RequestParam String pendingId) {
        return Result.success(service.latestJob(SecurityUser.userId(auth), SecurityUser.roles(auth), pendingId));
    }
    @PostMapping("/batch-jobs/{jobId}/retry")
    public Result<JobView> retry(Authentication auth, @PathVariable String jobId, @Valid @RequestBody RetryRequest request) {
        return Result.success(service.retry(SecurityUser.userId(auth), SecurityUser.roles(auth), jobId, request.pendingIds()));
    }
}
