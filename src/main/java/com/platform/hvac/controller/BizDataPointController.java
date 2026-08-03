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
 * HVAC 标准测点档案的 HTTP 管理入口。
 *
 * <p>按设备查询时先从 MySQL 取得设备归属，再校验建筑权限；按建筑查询则直接校验
 * 路径建筑。平台管理员写入时由 {@link BizDataPointService} 校验命名规则、设备与
 * 系统归属及计算单位，并刷新 MQTT/质量链使用的配置快照。本入口不查询 TDengine。</p>
 */
@Slf4j
@RestController
@RequestMapping("/datapoint")
@RequiredArgsConstructor
public class BizDataPointController {

    private final BizDataPointService dataPointService;
    private final BizEquipmentService equipmentService;
    private final BuildingScopeService buildingScopeService;

    /**
     * 返回指定设备的全部测点档案。
     *
     * <p>设备不存在返回 404；设备存在时按其 {@code buildingId} 校验当前用户范围，
     * 防止通过设备 ID 枚举其他建筑测点。</p>
     */
    @GetMapping("/equip/{equipId}")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<List<BizDataPoint>> listByEquip(@PathVariable String equipId, Authentication authentication) {
        var equipment = equipmentService.getById(equipId);
        if (equipment == null) throw new BusinessException(404, "设备不存在");
        buildingScopeService.checkAccess(SecurityUser.userId(authentication), SecurityUser.roles(authentication), equipment.getBuildingId());
        return dataPointService.listByEquip(equipId);
    }

    /** 校验建筑权限后，返回该建筑在 MySQL 中的全部测点档案。 */
    @GetMapping("/building/{buildingId}")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<List<BizDataPoint>> listByBuilding(@PathVariable String buildingId, Authentication authentication) {
        buildingScopeService.checkAccess(SecurityUser.userId(authentication), SecurityUser.roles(authentication), buildingId);
        return dataPointService.listByBuilding(buildingId);
    }

    /** 新增标准测点并刷新运行时配置快照；校验失败时不写入 MySQL。 */
    @PostMapping("/add")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<BizDataPoint> add(@Valid @RequestBody BizDataPoint point) {
        return dataPointService.add(point);
    }

    /** 更新测点可编辑字段并刷新配置快照；标准身份字段不能通过普通编辑改变。 */
    @PutMapping("/update")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<BizDataPoint> update(@Valid @RequestBody BizDataPoint point) {
        return dataPointService.update(point);
    }

    /** 逻辑删除测点并刷新配置快照；既有 TDengine 时序事实不在此删除。 */
    @DeleteMapping("/delete/{pointId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Void> delete(@PathVariable String pointId) {
        return dataPointService.delete(pointId);
    }
}
