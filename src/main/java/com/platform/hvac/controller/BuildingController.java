package com.platform.hvac.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.platform.framework.common.Result;
import com.platform.hvac.model.entity.Building;
import com.platform.hvac.service.BuildingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import com.platform.security.SecurityUser;
import com.platform.system.service.BuildingScopeService;
import org.springframework.web.bind.annotation.*;

/**
 * 楼栋管理接口。
 * 查询按当前用户建筑范围过滤；楼栋新增、修改和删除仅允许 PLATFORM_ADMIN。
 */
@Slf4j
@RestController
@RequestMapping("/building")
@RequiredArgsConstructor
public class BuildingController {

    private final BuildingService buildingService;
    private final BuildingScopeService buildingScopeService;

    /**
     * 分页查询楼栋列表
     */
    @GetMapping("/list")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<IPage<Building>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword, Authentication authentication) {
        return buildingService.list(page, size, keyword,
                buildingScopeService.getAccessibleBuildingIds(SecurityUser.userId(authentication), SecurityUser.roles(authentication)));
    }

    @PostMapping("/add")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Building> add(@Valid @RequestBody Building building) {
        return buildingService.add(building);
    }

    @PutMapping("/update")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Building> update(@Valid @RequestBody Building building) {
        return buildingService.update(building);
    }

    @DeleteMapping("/delete/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Void> delete(@PathVariable String id) {
        return buildingService.delete(id);
    }
}
