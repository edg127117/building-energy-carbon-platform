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
 * HVAC 设备台账的 HTTP 管理入口。
 *
 * <p>列表和详情先以 {@link BuildingScopeService} 执行建筑范围校验，再通过
 * {@link BizEquipmentService} 读取 MySQL；平台管理员写入时，Service 负责设备类型、
 * 系统分组和空间关系以及业务编码分配。该入口不读取设备实时测点或下发控制命令。</p>
 */
@Slf4j
@RestController
@RequestMapping("/equipment")
@RequiredArgsConstructor
public class BizEquipmentController {

    private final BizEquipmentService equipmentService;
    private final BuildingScopeService buildingScopeService;

    /**
     * 在当前用户获权建筑内分页查询设备。
     *
     * <p>指定建筑时先做单建筑权限校验；未指定时仍把完整授权集合传给 Service，
     * 因而普通角色不能通过省略参数绕过范围过滤。</p>
     */
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

    /**
     * 查询设备档案并按其建筑归属校验访问权限。
     *
     * <p>设备不存在返回 404，存在但用户无建筑权限返回 403；不会泄露其他建筑的
     * 设备内容。</p>
     */
    @GetMapping("/detail/{equipId}")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<BizEquipment> detail(@PathVariable String equipId, Authentication authentication) {
        BizEquipment equipment = equipmentService.getById(equipId);
        if (equipment == null) throw new BusinessException(404, "设备不存在");
        buildingScopeService.checkAccess(SecurityUser.userId(authentication), SecurityUser.roles(authentication), equipment.getBuildingId());
        return Result.success(equipment);
    }

    /** 新增设备，由 Service 校验 MySQL 关联并生成不复用的建筑内业务编码。 */
    @PostMapping("/add")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<BizEquipment> add(@Valid @RequestBody BizEquipment equipment) {
        return equipmentService.add(equipment);
    }

    /** 更新设备可编辑档案；设备 ID、建筑、类型和业务编码保持原值。 */
    @PutMapping("/update")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<BizEquipment> update(@Valid @RequestBody BizEquipment equipment) {
        return equipmentService.update(equipment);
    }

    /** 逻辑删除设备台账；本入口不级联删除测点或时序数据。 */
    @DeleteMapping("/delete/{equipId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Void> delete(@PathVariable String equipId) {
        return equipmentService.delete(equipId);
    }
}
