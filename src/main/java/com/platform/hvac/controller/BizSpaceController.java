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
 * 建筑空间档案的 HTTP 管理入口。
 *
 * <p>列表和树形查询在进入 {@link BizSpaceService} 前校验建筑数据范围，Service
 * 再从 MySQL 读取空间；新增、修改和逻辑删除只允许平台管理员。该入口不维护
 * 设备台账，也不根据空间推导用户权限。</p>
 */
@Slf4j
@RestController
@RequestMapping("/space")
@RequiredArgsConstructor
public class BizSpaceController {

    private final BizSpaceService spaceService;
    private final BuildingScopeService buildingScopeService;

    /** 校验建筑权限后，返回该建筑在 MySQL 中的全部空间档案。 */
    @GetMapping("/building/{buildingId}")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<List<BizSpace>> listByBuilding(@PathVariable String buildingId, Authentication authentication) {
        buildingScopeService.checkAccess(SecurityUser.userId(authentication), SecurityUser.roles(authentication), buildingId);
        return spaceService.listByBuilding(buildingId);
    }

    /** 校验建筑权限后，返回按父子关系组装的空间树。 */
    @GetMapping("/tree/{buildingId}")
    @PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
    public Result<List<BizSpace>> tree(@PathVariable String buildingId, Authentication authentication) {
        buildingScopeService.checkAccess(SecurityUser.userId(authentication), SecurityUser.roles(authentication), buildingId);
        return spaceService.tree(buildingId);
    }

    /** 新增空间，并由 Service 校验父空间与当前空间属于同一建筑。 */
    @PostMapping("/add")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<BizSpace> add(@Valid @RequestBody BizSpace space) {
        return spaceService.add(space);
    }

    /** 更新空间可编辑字段；建筑归属不能通过请求迁移。 */
    @PutMapping("/update")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<BizSpace> update(@Valid @RequestBody BizSpace space) {
        return spaceService.update(space);
    }

    /** 逻辑删除空间；当前操作不级联处理子空间或设备。 */
    @DeleteMapping("/delete/{spaceId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Void> delete(@PathVariable String spaceId) {
        return spaceService.delete(spaceId);
    }
}
