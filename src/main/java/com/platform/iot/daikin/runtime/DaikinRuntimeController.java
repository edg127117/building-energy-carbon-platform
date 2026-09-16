package com.platform.iot.daikin.runtime;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/hvac-monitoring/devices/{equipmentId}/runtime")
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
/** 读取已持久化的厂家统计及修订；打开页面不会触发厂家请求。 */
public class DaikinRuntimeController {
    private final DaikinRuntimeQueryService service;

    @GetMapping
    @Operation(summary = "按日/月/年独立查询厂家统计；零、缺失、失败及未完成分开返回")
    public Result<DaikinRuntimeQueryService.RuntimePage> values(Authentication authentication, @PathVariable String equipmentId,
            @RequestParam(defaultValue = "DAY") String granularity, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(service.values(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                equipmentId, granularity, cursor, limit));
    }

    @GetMapping("/{valueId}/revisions")
    public Result<DaikinRuntimeQueryService.RevisionPage> revisions(Authentication authentication, @PathVariable String equipmentId,
            @PathVariable String valueId, @RequestParam(defaultValue = "0") int after,
            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(service.revisions(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                equipmentId, valueId, after, limit));
    }
}
