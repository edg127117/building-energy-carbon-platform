package com.platform.hvac.controller;

import com.platform.framework.common.Result;
import com.platform.hvac.model.entity.BizSpace;
import com.platform.hvac.service.BizSpaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import com.platform.security.SecurityUser;
import com.platform.system.service.BuildingScopeService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 空间管理接口。
 * 读取前强制校验所属建筑权限，空间 CRUD 仅允许 PLATFORM_ADMIN。
 */
@Slf4j
@RestController
@RequestMapping("/space")
@RequiredArgsConstructor
public class BizSpaceController {

    private final BizSpaceService spaceService;
    private final BuildingScopeService buildingScopeService;

    /** 按建筑查所有空间 */
    @GetMapping("/building/{buildingId}")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<List<BizSpace>> listByBuilding(@PathVariable String buildingId, Authentication authentication) {
        buildingScopeService.checkAccess(SecurityUser.userId(authentication), SecurityUser.roles(authentication), buildingId);
        return spaceService.listByBuilding(buildingId);
    }

    /** 按建筑查空间树 */
    @GetMapping("/tree/{buildingId}")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<List<BizSpace>> tree(@PathVariable String buildingId, Authentication authentication) {
        buildingScopeService.checkAccess(SecurityUser.userId(authentication), SecurityUser.roles(authentication), buildingId);
        return spaceService.tree(buildingId);
    }

    /** 新增空间 */
    @PostMapping("/add")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<BizSpace> add(@Valid @RequestBody BizSpace space) {
        return spaceService.add(space);
    }

    /** 更新空间 */
    @PutMapping("/update")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<BizSpace> update(@Valid @RequestBody BizSpace space) {
        return spaceService.update(space);
    }

    /** 删除空间 */
    @DeleteMapping("/delete/{spaceId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Void> delete(@PathVariable String spaceId) {
        return spaceService.delete(spaceId);
    }
}
