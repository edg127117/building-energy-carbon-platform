package com.platform.iot.dataquality.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.platform.framework.common.Result;
import com.platform.iot.dataquality.DataQualityFillTaskService;
import com.platform.iot.dataquality.model.FillApplyStatus;
import com.platform.iot.dataquality.model.FillSourceType;
import com.platform.iot.dataquality.model.dto.DataQualityFillDtos;
import com.platform.security.SecurityUser;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 补全任务来源审计、分页查询和失败重试接口。
 *
 * <p>Controller 只负责角色入口、基础参数和登录身份传递；建筑范围与任务状态由
 * {@link DataQualityFillTaskService} 再次校验。异常作废和范围重算将在具备独立审计
 * 任务模型后接入，不在本接口伪装成逐条审批。</p>
 */
@Validated
@RestController
@RequestMapping("/iot/data-quality/fill-tasks")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "data-quality", name = "enabled", havingValue = "true")
public class DataQualityFillController {

    private final DataQualityFillTaskService service;

    /**
     * 建筑业主和能效管理员只读取授权建筑，平台管理员可读取全部；筛选和分页均在 MySQL 完成。
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<IPage<DataQualityFillDtos.Response>> page(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) String pointId,
            @RequestParam(required = false) FillSourceType sourceType,
            @RequestParam(required = false) Integer dataQuality,
            @RequestParam(required = false) FillApplyStatus applyStatus,
            @RequestParam(required = false) Long fromInclusive,
            @RequestParam(required = false) Long toExclusive) {
        return Result.success(service.page(
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication),
                pageNum,
                pageSize,
                buildingId,
                pointId,
                sourceType,
                dataQuality,
                applyStatus,
                fromInclusive,
                toExclusive));
    }

    /** 单条详情仍按记录所属 buildingId 二次校验，不能凭 taskId 绕过建筑范围。 */
    @GetMapping("/{taskId}")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<DataQualityFillDtos.Response> detail(
            Authentication authentication,
            @PathVariable @NotBlank String taskId) {
        return Result.success(service.detail(
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication),
                taskId));
    }

    /**
     * 仅平台管理员可即时重试 FAILED 任务；该操作复用原 taskId 和冻结证据，不创建审批记录。
     */
    @PostMapping("/{taskId}/retry")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<DataQualityFillDtos.Response> retry(
            Authentication authentication,
            @PathVariable @NotBlank String taskId) {
        return Result.success(service.retry(
                SecurityUser.roles(authentication),
                taskId,
                System.currentTimeMillis()));
    }
}
