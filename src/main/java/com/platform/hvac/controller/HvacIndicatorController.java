package com.platform.hvac.controller;

import com.platform.framework.common.Result;
import com.platform.hvac.model.dto.HvacIndicatorDtos;
import com.platform.hvac.service.HvacIndicatorQueryService;
import com.platform.security.SecurityUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HVAC 公式指标的只读 HTTP API 入口。
 *
 * <p>Controller 只提取参数和登录身份，建筑范围、时间区间、缓存回退和 TDengine
 * 异常转换由查询 Service 处理。四个接口仅允许业主、能效管理方和平台管理员，
 * 角色校验之后仍会执行建筑数据范围校验。{@code formula.enabled=false} 时整个入口
 * 不装配，调用方不能把“接口类存在”视为运行环境一定启用了指标查询。</p>
 */
@RestController
@RequestMapping("/hvac")
@ConditionalOnProperty(
        prefix = "formula", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class HvacIndicatorController {

    private final HvacIndicatorQueryService queryService;

    public HvacIndicatorController(HvacIndicatorQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 查询一个建筑全部活动指标的最新成功、失败或无数据状态。
     *
     * <p>Service 先以 MySQL 活动配置确定返回范围，Redis 未命中的指标再回退到
     * TDengine；结果供 HVAC 页面四项指标卡片展示，缓存不能扩大用户的建筑权限。</p>
     */
    @GetMapping("/buildings/{buildingId}/indicators/latest")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<HvacIndicatorDtos.LatestResponse> latest(
            @PathVariable String buildingId,
            Authentication authentication) {
        return Result.success(queryService.latest(
                buildingId,
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication)));
    }

    /**
     * 批量查询建筑内 1 至 4 个指标的图表趋势。
     *
     * <p>Service 校验具体建筑范围并按跨度选择 1/5/30 分钟分辨率；该接口只返回
     * 图表窗口，不替代单指标精确历史，聚合窗口也不返回公式版本。</p>
     */
    @GetMapping("/buildings/{buildingId}/indicators/trends")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<HvacIndicatorDtos.TrendResponse> trends(
            @PathVariable String buildingId,
            @RequestParam(required = false) String indicatorIds,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            Authentication authentication) {
        return Result.success(queryService.trends(
                buildingId,
                indicatorIds,
                from,
                to,
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication)));
    }

    /**
     * 查询单个指标的成功历史。
     *
     * <p>{@code from} 和 {@code to} 是毫秒时间戳，业务层按半开区间处理并限制
     * 最大跨度为 31 天；无效组合返回 400。</p>
     */
    @GetMapping("/indicators/{indicatorId}/history")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<HvacIndicatorDtos.HistoryResponse> history(
            @PathVariable String indicatorId,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            Authentication authentication) {
        return Result.success(queryService.history(
                indicatorId,
                from,
                to,
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication)));
    }

    /**
     * 查询一个来源分钟的输入、步骤或失败原因。
     *
     * <p>历史公式版本无法由当前代码重放时返回 409，不会用新公式解释旧结果。</p>
     */
    @GetMapping("/indicators/{indicatorId}/calculations/{minuteStart}")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<HvacIndicatorDtos.CalculationDetail> detail(
            @PathVariable String indicatorId,
            @PathVariable long minuteStart,
            Authentication authentication) {
        return Result.success(queryService.detail(
                indicatorId,
                minuteStart,
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication)));
    }
}
