package com.platform.iot.communication;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.platform.iot.communication.CommunicationModels.*;

@RestController
@RequestMapping("/v1/assets/communication")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
/** 通讯规则与历史只对资产管理员开放，不接受浏览器伪造设备在线证据。 */
public class CommunicationController {
    private final DeviceCommunicationService service;
    @GetMapping("/policies")
    public Result<List<Policy>> policies() { return Result.success(service.policies()); }
    @PutMapping("/policies/{scopeKey}")
    public Result<Policy> save(@PathVariable String scopeKey,@Valid @RequestBody PolicyRequest request,Authentication auth) {
        return Result.success(service.savePolicy(scopeKey,request,SecurityUser.userId(auth),SecurityUser.roles(auth)));
    }
    @GetMapping("/identities/{identityId}/history")
    public Result<List<Transition>> history(@PathVariable String identityId,@RequestParam(defaultValue="50") int limit,Authentication auth) {
        return Result.success(service.history(identityId,limit,SecurityUser.roles(auth)));
    }
    @GetMapping("/identities/{identityId}/gateway")
    public Result<java.util.Map<String,Object>> gateway(@PathVariable String identityId,Authentication auth) {
        return Result.success(service.gateway(identityId,SecurityUser.roles(auth)));
    }
    @PutMapping("/identities/{identityId}/gateway")
    public Result<java.util.Map<String,Object>> attachGateway(@PathVariable String identityId,@Valid @RequestBody GatewayRequest request,Authentication auth) {
        return Result.success(service.attachGateway(identityId,request,SecurityUser.userId(auth),SecurityUser.roles(auth)));
    }
}
