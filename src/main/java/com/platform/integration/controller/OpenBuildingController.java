package com.platform.integration.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.platform.framework.common.Result;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.model.entity.BizEquipment;
import com.platform.hvac.model.entity.Building;
import com.platform.hvac.service.BizDataPointService;
import com.platform.hvac.service.BizEquipmentService;
import com.platform.hvac.service.BuildingService;
import com.platform.security.SecurityUser;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 面向第三方系统的建筑只读开放接口。
 *
 * <p>与内部管理接口分开使用 {@code /open-api/**} 路径，便于后续独立限流、审计和编写接口文档。
 * THIRD_PARTY 只能读取管理员已授权的建筑；PLATFORM_ADMIN 调用时可读取全部建筑。</p>
 */
@RestController
@RequestMapping("/open-api/buildings")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('THIRD_PARTY','PLATFORM_ADMIN')")
public class OpenBuildingController {
    private final BuildingService buildingService;
    private final BizEquipmentService equipmentService;
    private final BizDataPointService dataPointService;
    private final BuildingScopeService scopeService;

    /** 分页查询调用方有权访问的建筑。 */
    @GetMapping
    public Result<IPage<Building>> buildings(@RequestParam(defaultValue="1") int page,
                                             @RequestParam(defaultValue="20") int size,
                                             Authentication authentication) {
        return buildingService.list(page, Math.min(size, 100), null, ids(authentication));
    }

    /** 查询建筑详情，先执行建筑范围校验。 */
    @GetMapping("/{buildingId}")
    public Result<Building> detail(@PathVariable String buildingId, Authentication authentication) {
        check(authentication, buildingId);
        Building building = buildingService.getById(buildingId);
        if (building == null) throw new BusinessException(404, "建筑不存在");
        return Result.success(building);
    }

    /** 分页查询指定获授权建筑下的设备。 */
    @GetMapping("/{buildingId}/equipment")
    public Result<IPage<BizEquipment>> equipment(@PathVariable String buildingId,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size,
            Authentication authentication) {
        check(authentication, buildingId);
        return equipmentService.list(page, Math.min(size, 100), buildingId, null, null, Set.of(buildingId));
    }

    /** 查询指定获授权建筑下的测点定义。 */
    @GetMapping("/{buildingId}/datapoints")
    public Result<List<BizDataPoint>> datapoints(@PathVariable String buildingId, Authentication authentication) {
        check(authentication, buildingId);
        return dataPointService.listByBuilding(buildingId);
    }

    /** 取得当前调用方建筑集合；{@code null} 仅代表平台管理员的全量范围。 */
    private Set<String> ids(Authentication authentication) {
        return scopeService.getAccessibleBuildingIds(SecurityUser.userId(authentication), SecurityUser.roles(authentication));
    }
    /** 对单建筑资源执行服务端强制校验，不能依赖第三方自行过滤。 */
    private void check(Authentication authentication, String buildingId) {
        scopeService.checkAccess(SecurityUser.userId(authentication), SecurityUser.roles(authentication), buildingId);
    }
}
