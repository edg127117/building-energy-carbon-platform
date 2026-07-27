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
 * HVAC 冻结分钟数据的只读 HTTP 入口。
 *
 * <p>这里仅声明模块内部路径 {@code /hvac/buildings}；对外统一的
 * {@code /api} 前缀由 {@code server.servlet.context-path} 提供，避免形成
 * {@code /api/api} 重复路径。</p>
 *
 * <p>Controller 只负责接收请求参数、提取当前登录用户身份并包装统一响应；
 * 建筑范围、测点归属、时间跨度和 TDengine 异常转换统一由
 * {@link HvacQueryService} 处理。角色注解是第一层入口限制，Service 中的建筑范围
 * 校验是第二层数据权限限制。</p>
 */
@RestController
@RequestMapping("/hvac/buildings")
@RequiredArgsConstructor
public class HvacQueryController {

    private final HvacQueryService queryService;

    /**
     * 查询建筑内全部在线测点各自最新的冻结分钟数据。
     *
     * <p>各测点不要求来自同一分钟；已配置但尚无分钟数据的测点仍会返回，
     * 并标记为 {@code NO_DATA}。</p>
     *
     * @param buildingId 目标建筑 ID
     * @param authentication 当前登录用户的认证信息
     * @return 建筑测点最新快照
     */
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

    /**
     * 查询用户选定的 1 至 8 个测点的历史趋势。
     *
     * <p>{@code from} 和 {@code to} 使用 Unix 毫秒时间戳，查询范围为
     * {@code [from, to)}，最大跨度 31 天。返回分辨率由 Service 根据跨度自动选择，
     * 调用方不直接指定。</p>
     *
     * @param buildingId 目标建筑 ID
     * @param pointIds 逗号分隔的测点内部 ID
     * @param from 包含的起始时间（Unix 毫秒）
     * @param to 不包含的结束时间（Unix 毫秒）
     * @param authentication 当前登录用户的认证信息
     * @return 按请求测点顺序组织的历史趋势
     */
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
