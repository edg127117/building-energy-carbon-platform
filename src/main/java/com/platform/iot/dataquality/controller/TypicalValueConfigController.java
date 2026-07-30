package com.platform.iot.dataquality.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.platform.framework.common.Result;
import com.platform.iot.dataquality.TypicalValueConfigService;
import com.platform.iot.dataquality.model.TypicalValueStatus;
import com.platform.iot.dataquality.model.dto.TypicalValueDtos;
import com.platform.security.SecurityUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 典型值配置的查询、版本维护和平台审批接口。
 *
 * <p>Controller 只负责请求校验、角色入口和当前身份传递；建筑范围、时间转换、状态机和
 * MySQL 事务均由 {@link TypicalValueConfigService} 处理。质量补全功能关闭时整组接口不注册。</p>
 */
@RestController
@RequestMapping("/iot/data-quality/typical-values")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "data-quality", name = "enabled", havingValue = "true")
public class TypicalValueConfigController {

    private final TypicalValueConfigService service;

    /** 分页读取配置；建筑业主和能效管理员只能读取授权建筑，平台管理员可读取全部。 */
    @GetMapping
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<IPage<TypicalValueDtos.Response>> page(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) String pointId,
            @RequestParam(required = false) TypicalValueStatus status,
            @RequestParam(required = false) Long validFrom,
            @RequestParam(required = false) Long validTo) {
        return Result.success(service.page(
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication),
                pageNum,
                pageSize,
                buildingId,
                pointId,
                status,
                validFrom,
                validTo));
    }

    /** 查询单条配置，Service 会再次按其 buildingId 校验建筑权限。 */
    @GetMapping("/{configId}")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<TypicalValueDtos.Response> detail(
            Authentication authentication,
            @PathVariable @NotBlank String configId) {
        return Result.success(service.detail(
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication),
                configId));
    }

    /** 能效管理员或平台管理员创建草稿，单位、版本和创建人由服务端确定。 */
    @PostMapping
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<TypicalValueDtos.Response> create(
            Authentication authentication,
            @Valid @RequestBody TypicalValueDtos.CreateRequest request) {
        return Result.success(service.createView(
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication),
                request));
    }

    /** 修改草稿内容；待审、批准、拒绝或停用配置不能直接修改。 */
    @PutMapping("/{configId}")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<TypicalValueDtos.Response> update(
            Authentication authentication,
            @PathVariable @NotBlank String configId,
            @Valid @RequestBody TypicalValueDtos.UpdateRequest request) {
        return Result.success(service.updateView(
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication),
                configId,
                request));
    }

    /** 提交草稿并冻结审核内容。 */
    @PostMapping("/{configId}/submit")
    @PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<TypicalValueDtos.Response> submit(
            Authentication authentication,
            @PathVariable @NotBlank String configId) {
        return Result.success(service.submitView(
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication),
                configId));
    }

    /** 平台管理员批准待审配置；创建人不能批准自己创建的版本。 */
    @PostMapping("/{configId}/approve")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<TypicalValueDtos.Response> approve(
            Authentication authentication,
            @PathVariable @NotBlank String configId,
            @Valid @RequestBody TypicalValueDtos.ReviewRequest request) {
        return Result.success(service.approveView(
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication),
                configId,
                request));
    }

    /** 平台管理员拒绝待审配置，拒绝意见不可为空。 */
    @PostMapping("/{configId}/reject")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<TypicalValueDtos.Response> reject(
            Authentication authentication,
            @PathVariable @NotBlank String configId,
            @Valid @RequestBody TypicalValueDtos.ReviewRequest request) {
        return Result.success(service.rejectView(
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication),
                configId,
                request));
    }

    /** 平台管理员停用批准配置；停用不删除历史补全证据。 */
    @PostMapping("/{configId}/disable")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<TypicalValueDtos.Response> disable(
            Authentication authentication,
            @PathVariable @NotBlank String configId,
            @Valid @RequestBody TypicalValueDtos.DisableRequest request) {
        return Result.success(service.disableView(
                SecurityUser.userId(authentication),
                SecurityUser.roles(authentication),
                configId,
                request));
    }
}
