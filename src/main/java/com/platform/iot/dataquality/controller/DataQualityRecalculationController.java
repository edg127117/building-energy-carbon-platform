package com.platform.iot.dataquality.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.platform.framework.common.Result;
import com.platform.iot.dataquality.DataQualityRecalculationJobService;
import com.platform.iot.dataquality.model.RecalculationJobStatus;
import com.platform.iot.dataquality.model.RecalculationJobType;
import com.platform.iot.dataquality.model.dto.DataQualityFillDtos;
import com.platform.iot.dataquality.model.dto.DataQualityRecalculationDtos;
import com.platform.security.SecurityUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理员提交异步人工重算并查询持久化进度的管理接口。
 *
 * <p>POST 只受理 MySQL 任务并立即返回，不在 HTTP 线程执行 TDengine 历史读写。
 * 两个质量开关必须同时开启，避免接口可提交但后台执行器未注册。</p>
 */
@Validated
@RestController
@RequestMapping("/iot/data-quality")
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "data-quality",
        name = {"enabled", "recalculation-enabled"},
        havingValue = "true")
public class DataQualityRecalculationController {

    private final DataQualityRecalculationJobService service;

    /** 作废异常补全任务并立即返回异步重算批次，不在请求线程删除 TDengine 数据。 */
    @PostMapping("/fill-tasks/{taskId}/void-and-recalculate")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<DataQualityRecalculationDtos.Response> voidAndRecalculate(
            Authentication authentication,
            @PathVariable @NotBlank String taskId,
            @Valid @RequestBody
            DataQualityFillDtos.VoidAndRecalculateRequest request) {
        return Result.success(service.submitVoid(
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication),
                taskId,
                request.reason(),
                System.currentTimeMillis()));
    }

    /** 提交建筑测点范围重算并立即返回 WAITING 或已复用的批次。 */
    @PostMapping("/recalculate")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<DataQualityRecalculationDtos.Response> recalculate(
            Authentication authentication,
            @Valid @RequestBody
            DataQualityRecalculationDtos.RecalculateRequest request) {
        return Result.success(service.submitRange(
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication),
                request,
                System.currentTimeMillis()));
    }

    /** 平台管理员查询重算批次进度，筛选和分页在 MySQL 中完成。 */
    @GetMapping("/recalculation-jobs")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<IPage<DataQualityRecalculationDtos.Response>> page(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) RecalculationJobType jobType,
            @RequestParam(required = false) RecalculationJobStatus status,
            @RequestParam(required = false) Long fromInclusive,
            @RequestParam(required = false) Long toExclusive) {
        return Result.success(service.page(
                SecurityUser.roles(authentication),
                pageNum, pageSize, buildingId, jobType, status,
                fromInclusive, toExclusive));
    }

    /** 查询批次汇总及其关联的 Q1/Q2 补全任务证据。 */
    @GetMapping("/recalculation-jobs/{jobId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<DataQualityRecalculationDtos.Detail> detail(
            Authentication authentication,
            @PathVariable @NotBlank String jobId) {
        return Result.success(service.detail(
                SecurityUser.roles(authentication), jobId));
    }
}
