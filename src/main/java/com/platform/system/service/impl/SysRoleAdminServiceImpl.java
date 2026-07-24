package com.platform.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.cache.MenuCacheService;
import com.platform.framework.exception.BusinessException;
import com.platform.security.FormalRole;
import com.platform.system.mapper.SysMenuMapper;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.mapper.SysRoleMenuMapper;
import com.platform.system.mapper.SysUserRoleMapper;
import com.platform.system.model.dto.RoleAdminDtos;
import com.platform.system.model.entity.SysMenu;
import com.platform.system.model.entity.SysRole;
import com.platform.system.model.entity.SysRoleMenu;
import com.platform.system.service.SysRoleAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 正式角色管理实现。
 *
 * <p>本期角色本身是冻结的四类角色，因此这里只提供查询和菜单分配，不开放任意创建角色。
 * 菜单关系采用全量替换，并清理该角色下所有用户的动态菜单缓存。</p>
 */
@Service
@RequiredArgsConstructor
public class SysRoleAdminServiceImpl implements SysRoleAdminService {
    private final SysRoleMapper roleMapper;
    private final SysMenuMapper menuMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final MenuCacheService menuCacheService;

    @Override public List<RoleAdminDtos.RoleView> list() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>().in(SysRole::getRoleKey, FormalRole.keys())
                .orderByAsc(SysRole::getId)).stream().map(this::view).toList();
    }
    @Override public RoleAdminDtos.RoleView detail(Long id) { return view(requireRole(id)); }
    @Override public List<Long> menuIds(Long id) { requireRole(id); return roleMenuMapper.selectMenuIdsByRoleId(id); }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceMenus(Long id, List<Long> menuIds) {
        requireRole(id);
        // 去重后校验所有菜单 ID，防止角色关联到已删除或不存在的菜单。
        Set<Long> ids = menuIds == null ? Set.of() : new LinkedHashSet<>(menuIds);
        if (!ids.isEmpty() && menuMapper.selectBatchIds(ids).size() != ids.size()) throw new BusinessException(404, "包含不存在的菜单");
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, id));
        for (Long menuId : ids) {
            SysRoleMenu link = new SysRoleMenu(); link.setRoleId(id); link.setMenuId(menuId); roleMenuMapper.insert(link);
        }
        // 角色菜单发生变化后，其下所有用户下一次请求都会重新生成菜单树。
        for (Long userId : userRoleMapper.selectUserIdsByRoleId(id)) menuCacheService.evict(userId);
    }

    private SysRole requireRole(Long id) {
        SysRole role = roleMapper.selectById(id);
        if (role == null || !FormalRole.isFormal(role.getRoleKey())) throw new BusinessException(404, "正式角色不存在");
        return role;
    }
    private RoleAdminDtos.RoleView view(SysRole r) { return new RoleAdminDtos.RoleView(r.getId(), r.getRoleKey(), r.getRoleName(), r.getDataScope(), r.getStatus()); }
}
