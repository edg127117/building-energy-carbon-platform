package com.platform.hvac.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.platform.framework.common.Result;
import com.platform.hvac.model.entity.BizSystemGroup;
import com.platform.hvac.service.BizSystemGroupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import com.platform.security.SecurityUser;
import com.platform.system.service.BuildingScopeService;
import org.springframework.web.bind.annotation.*;

/**
 * 建筑内 HVAC 系统分组的 HTTP 管理入口。
 *
 * <p>查询时先用 {@link BuildingScopeService} 限定当前用户的建筑集合，再由
 * {@link BizSystemGroupService} 访问 MySQL；指定建筑筛选时还会立即校验该建筑权限。
 * 写操作只允许平台管理员，本 Controller 不维护设备或测点档案。</p>
 */
@Slf4j
@RestController
@RequestMapping("/system-group")
@RequiredArgsConstructor
public class BizSystemGroupController {

    private final BizSystemGroupService systemGroupService;
    private final BuildingScopeService buildingScopeService;

    /**
     * 在用户获权建筑内分页查询系统分组。
     *
     * <p>显式传入 {@code buildingId} 时先返回 403 或继续查询，未传时则由 Service
     * 使用完整可访问建筑集合过滤，避免跨建筑枚举系统档案。</p>
     */
    @GetMapping("/list")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<IPage<BizSystemGroup>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) String keyword, Authentication authentication) {
        if (buildingId != null && !buildingId.isBlank()) buildingScopeService.checkAccess(SecurityUser.userId(authentication), SecurityUser.roles(authentication), buildingId);
        return systemGroupService.list(page, size, buildingId, keyword,
                buildingScopeService.getAccessibleBuildingIds(SecurityUser.userId(authentication), SecurityUser.roles(authentication)));
    }

    /** 将平台管理员提交的系统分组写入 MySQL，内部 ID 由后端生成。 */
    @PostMapping("/add")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<BizSystemGroup> add(@Valid @RequestBody BizSystemGroup group) {
        return systemGroupService.add(group);
    }

    /** 更新系统分组的可编辑字段，建筑归属和业务编码由 Service 保持不变。 */
    @PutMapping("/update")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<BizSystemGroup> update(@Valid @RequestBody BizSystemGroup group) {
        return systemGroupService.update(group);
    }

    /** 逻辑删除指定系统分组；本入口不级联删除设备或测点。 */
    @DeleteMapping("/delete/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Void> delete(@PathVariable String id) {
        return systemGroupService.delete(id);
    }
}
