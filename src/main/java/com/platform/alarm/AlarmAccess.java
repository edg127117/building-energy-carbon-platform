package com.platform.alarm;

import com.platform.framework.exception.BusinessException;
import com.platform.system.mapper.SysMenuMapper;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Set;

@Service
@RequiredArgsConstructor
/** 菜单只允许对应读取入口；每次读写都重新检查建筑范围，写规则另外要求管理角色。 */
public class AlarmAccess {
    private final BuildingScopeService buildings;
    private final SysMenuMapper menus;

    public void require(Long user, Set<String> roles, String building, String menu, boolean manage) {
        if (building == null || building.isBlank() || building.length() > 32) throw new BusinessException("建筑标识无效");
        requireMenu(user, roles, menu, manage);
        buildings.checkAccess(user, roles, building);
    }

    public void requireMenu(Long user, Set<String> roles, String menu, boolean manage) {
        if (user == null || roles == null) throw new BusinessException(403, "没有报警访问权限");
        if (manage && !roles.contains("ENERGY_MANAGER") && !roles.contains("PLATFORM_ADMIN")) {
            throw new BusinessException(403, "需要能源管理员或平台管理员权限");
        }
        if (!roles.contains("PLATFORM_ADMIN") && menus.selectVisibleMenusByUserId(user).stream()
                .noneMatch(m -> "C".equals(m.getMenuType()) && menu.equals(m.getPath()))) {
            throw new BusinessException(403, "没有该报警页面权限");
        }
    }
}
