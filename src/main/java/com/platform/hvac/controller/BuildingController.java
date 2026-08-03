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
 * 建筑档案的 HTTP 管理入口。
 *
 * <p>列表接口先从 {@link BuildingScopeService} 取得当前用户可访问建筑，再交给
 * {@link BuildingService} 查询 MySQL；HVAC 页面使用该结果提供建筑选择。
 * 新增、修改和逻辑删除只允许平台管理员，本 Controller 不读取 TDengine 时序数据。</p>
 */
@Slf4j
@RestController
@RequestMapping("/building")
@RequiredArgsConstructor
public class BuildingController {

    private final BuildingService buildingService;
    private final BuildingScopeService buildingScopeService;

    /**
     * 按当前用户建筑范围分页查询建筑档案。
     *
     * <p>平台管理员的范围值为 {@code null}，表示不过滤；其他角色即使没有任何授权
     * 也只会得到空页，不能通过关键字搜索越权建筑。</p>
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

    /** 将平台管理员提交的建筑档案写入 MySQL。 */
    @PostMapping("/add")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Building> add(@Valid @RequestBody Building building) {
        return buildingService.add(building);
    }

    /** 按建筑 ID 更新 MySQL 档案；普通业务角色不能调用此入口。 */
    @PutMapping("/update")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Building> update(@Valid @RequestBody Building building) {
        return buildingService.update(building);
    }

    /** 逻辑删除建筑档案，保留数据库记录供关联与审计使用。 */
    @DeleteMapping("/delete/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Result<Void> delete(@PathVariable String id) {
        return buildingService.delete(id);
    }
}
