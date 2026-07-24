package com.platform.hvac.controller;

import com.platform.framework.common.Result;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.service.BizDataPointService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import com.platform.security.SecurityUser;
import com.platform.system.service.BuildingScopeService;
import com.platform.hvac.service.BizEquipmentService;
import com.platform.framework.exception.BusinessException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 数据测点管理接口。
 * 查询设备或建筑测点前校验建筑范围，测点 CRUD 仅允许 PLATFORM_ADMIN。
 */
@Slf4j
@RestController
@RequestMapping("/datapoint")
@RequiredArgsConstructor
public class BizDataPointController {

    private final BizDataPointService dataPointService;
    private final BizEquipmentService equipmentService;
    private final BuildingScopeService buildingScopeService;

    /** 按设备查所有测点 */
    @GetMapping("/equip/{equipId}")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<List<BizDataPoint>> listByEquip(@PathVariable String equipId, Authentication authentication) {
        var equipment = equipmentService.getById(equipId);
        if (equipment == null) throw new BusinessException(404, "设备不存在");
        buildingScopeService.checkAccess(SecurityUser.userId(authentication), SecurityUser.roles(authentication), equipment.getBuildingId());
        return dataPointService.listByEquip(equipId);
    }

    /** 按建筑查所有测点 */
    @GetMapping("/building/{buildingId}")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<List<BizDataPoint>> listByBuilding(@PathVariable String buildingId, Authentication authentication) {
        buildingScopeService.checkAccess(SecurityUser.userId(authentication), SecurityUser.roles(authentication), buildingId);
        return dataPointService.listByBuilding(buildingId);
    }

    /** 新增测点 */
    @PostMapping("/add")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<BizDataPoint> add(@Valid @RequestBody BizDataPoint point) {
        return dataPointService.add(point);
    }

    /** 更新测点 */
    @PutMapping("/update")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<BizDataPoint> update(@Valid @RequestBody BizDataPoint point) {
        return dataPointService.update(point);
    }

    /** 删除测点 */
    @DeleteMapping("/delete/{pointId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Void> delete(@PathVariable String pointId) {
        return dataPointService.delete(pointId);
    }
}
