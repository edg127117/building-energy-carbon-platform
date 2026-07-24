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
 * 系统分组管理接口。
 * 列表按建筑范围过滤，系统分组 CRUD 仅允许 PLATFORM_ADMIN。
 */
@Slf4j
@RestController
@RequestMapping("/system-group")
@RequiredArgsConstructor
public class BizSystemGroupController {

    private final BizSystemGroupService systemGroupService;
    private final BuildingScopeService buildingScopeService;

    /** 分页查询系统分组 */
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

    /** 新增系统分组 */
    @PostMapping("/add")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<BizSystemGroup> add(@Valid @RequestBody BizSystemGroup group) {
        return systemGroupService.add(group);
    }

    /** 更新系统分组 */
    @PutMapping("/update")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<BizSystemGroup> update(@Valid @RequestBody BizSystemGroup group) {
        return systemGroupService.update(group);
    }

    /** 删除系统分组 */
    @DeleteMapping("/delete/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Void> delete(@PathVariable String id) {
        return systemGroupService.delete(id);
    }
}
