package com.platform.hvac.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.platform.framework.common.Result;
import com.platform.hvac.model.entity.BizEquipment;
import com.platform.hvac.service.BizEquipmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import com.platform.security.SecurityUser;
import com.platform.system.service.BuildingScopeService;
import com.platform.framework.exception.BusinessException;
import org.springframework.web.bind.annotation.*;

/**
 * HVAC 设备台账管理接口。
 * 建筑业主和能效管理方只能读取获授权建筑设备，设备 CRUD 仅允许 PLATFORM_ADMIN。
 */
@Slf4j
@RestController
@RequestMapping("/equipment")
@RequiredArgsConstructor
public class BizEquipmentController {

    private final BizEquipmentService equipmentService;
    private final BuildingScopeService buildingScopeService;

    /** 分页查询设备列表 */
    @GetMapping("/list")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<IPage<BizEquipment>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) String equipCategory,
            @RequestParam(required = false) String keyword, Authentication authentication) {
        if (buildingId != null && !buildingId.isBlank()) buildingScopeService.checkAccess(SecurityUser.userId(authentication), SecurityUser.roles(authentication), buildingId);
        return equipmentService.list(page, size, buildingId, equipCategory, keyword,
                buildingScopeService.getAccessibleBuildingIds(SecurityUser.userId(authentication), SecurityUser.roles(authentication)));
    }

    /** 查看设备详情 */
    @GetMapping("/detail/{equipId}")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<BizEquipment> detail(@PathVariable String equipId, Authentication authentication) {
        BizEquipment equipment = equipmentService.getById(equipId);
        if (equipment == null) throw new BusinessException(404, "设备不存在");
        buildingScopeService.checkAccess(SecurityUser.userId(authentication), SecurityUser.roles(authentication), equipment.getBuildingId());
        return Result.success(equipment);
    }

    /** 新增设备 */
    @PostMapping("/add")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<BizEquipment> add(@Valid @RequestBody BizEquipment equipment) {
        return equipmentService.add(equipment);
    }

    /** 更新设备 */
    @PutMapping("/update")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<BizEquipment> update(@Valid @RequestBody BizEquipment equipment) {
        return equipmentService.update(equipment);
    }

    /** 删除设备 */
    @DeleteMapping("/delete/{equipId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Void> delete(@PathVariable String equipId) {
        return equipmentService.delete(equipId);
    }
}
