package com.platform.hvac.controller;

import com.platform.framework.common.Result;
import com.platform.hvac.model.dto.HvacQueryDtos;
import com.platform.hvac.service.HvacQueryService;
import com.platform.security.SecurityUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HVAC 冻结分钟数据查询接口。
 */
@RestController
@RequestMapping("/api/hvac/buildings")
@RequiredArgsConstructor
public class HvacQueryController {

    private final HvacQueryService queryService;

    @GetMapping("/{buildingId}/snapshot")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<HvacQueryDtos.SnapshotResponse> snapshot(
            @PathVariable String buildingId,
            Authentication authentication) {
        return Result.success(queryService.snapshot(
                buildingId,
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication)));
    }

    @GetMapping("/{buildingId}/history")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<HvacQueryDtos.HistoryResponse> history(
            @PathVariable String buildingId,
            @RequestParam(required = false) String pointIds,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            Authentication authentication) {
        return Result.success(queryService.history(
                buildingId,
                pointIds,
                from,
                to,
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication)));
    }
}
