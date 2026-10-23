package com.platform.iot.reliability.api;

import com.platform.framework.common.Result;
import com.platform.framework.web.PageResponse;
import com.platform.iot.reliability.TelemetryConflictService;
import com.platform.iot.reliability.TelemetryConflictService.*;
import com.platform.security.SecurityUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/telemetry-conflicts")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 与成功回执分离，未形成成功回执的冲突也可以按建筑授权查询。 */
public class TelemetryConflictController {
    private final TelemetryConflictService service;
    public TelemetryConflictController(TelemetryConflictService service) { this.service=service; }
    @GetMapping
    public Result<PageResponse<ConflictView>> list(Authentication a,@RequestParam String buildingId,
            @RequestParam(required=false) String state,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return Result.success(service.list(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,state,page,size));
    }
    @GetMapping("/{pointId}/{eventTime}")
    public Result<ConflictView> detail(Authentication a,@PathVariable String pointId,@PathVariable long eventTime) {
        return Result.success(service.detail(SecurityUser.userId(a),SecurityUser.roles(a),pointId,eventTime));
    }
    @GetMapping("/owners")
    public Result<OwnerView> owner(Authentication a,@RequestParam String buildingId) {
        return Result.success(service.owner(SecurityUser.userId(a),SecurityUser.roles(a),buildingId));
    }
    @PutMapping("/owners")
    public Result<OwnerView> assign(Authentication a,@RequestBody OwnerRequest request) {
        return Result.success(service.assign(SecurityUser.userId(a),SecurityUser.roles(a),request));
    }
    @PostMapping("/{pointId}/{eventTime}/resolve")
    public Result<ConflictView> resolve(Authentication a,@PathVariable String pointId,@PathVariable long eventTime,@RequestBody ResolveRequest request) {
        return Result.success(service.resolve(SecurityUser.userId(a),SecurityUser.roles(a),pointId,eventTime,request));
    }
    @GetMapping("/recovery")
    public Result<java.util.List<RecoveryView>> failed(Authentication a,@RequestParam String buildingId) {
        return Result.success(service.failed(SecurityUser.userId(a),SecurityUser.roles(a),buildingId));
    }
    @PostMapping("/recovery/retry")
    public Result<Void> retry(Authentication a,@RequestBody RetryRequest request) {
        service.retry(SecurityUser.userId(a),SecurityUser.roles(a),request);return Result.success();
    }
    @GetMapping("/messages/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<java.util.List<java.util.Map<String,Object>>> messageEvidence(Authentication a,@PathVariable String id) {
        return Result.success(service.messageEvidence(SecurityUser.userId(a),SecurityUser.roles(a),id));
    }
    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public Result<Void> invalidRequest() {return Result.error(400,"冲突请求字段缺失或格式无效");}
}
