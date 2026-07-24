package com.platform.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.cache.MenuCacheService;
import com.platform.framework.common.Result;
import com.platform.framework.exception.BusinessException;
import com.platform.system.mapper.SysMenuMapper;
import com.platform.system.mapper.SysRoleMenuMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.model.entity.SysMenu;
import com.platform.system.model.entity.SysRoleMenu;
import com.platform.system.service.SysMenuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态菜单服务实现。
 *
 * <p>用户菜单由“用户 → 正式角色 → 角色菜单”关系合并生成。直接授权到子菜单时会自动补齐
 * 所有父级目录，再按 {@code sortOrder/id} 构造成树。生成结果写入 Redis，菜单配置变化时统一失效。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysMenuServiceImpl extends ServiceImpl<SysMenuMapper, SysMenu> implements SysMenuService {
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserMapper userMapper;
    private final MenuCacheService menuCacheService;
    private final ObjectMapper objectMapper;

    @Override
    public Result<List<SysMenu>> buildTree() {
        List<SysMenu> menus = list(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getStatus, 1).orderByAsc(SysMenu::getSortOrder).orderByAsc(SysMenu::getId));
        return Result.success(buildTree(menus));
    }

    @Override
    public Result<List<SysMenu>> listByRole(String roleKey) {
        return Result.success(buildTree(withAncestors(roleMenuMapper.selectMenusByRoleKey(roleKey))));
    }

    @Override
    public Result<List<SysMenu>> currentMenu(Long userId) {
        // 优先读取用户菜单缓存；缓存不存在或反序列化失败时回源角色菜单关系。
        String cached = menuCacheService.getUserMenu(userId);
        if (cached != null) {
            try {
                return Result.success(objectMapper.readValue(cached, new TypeReference<List<SysMenu>>() {}));
            } catch (Exception e) {
                log.warn("用户菜单缓存格式异常，将回源 MySQL: userId={}", userId);
                menuCacheService.evict(userId);
            }
        }
        List<SysMenu> tree = buildTree(withAncestors(baseMapper.selectVisibleMenusByUserId(userId)));
        try {
            menuCacheService.setUserMenu(userId, objectMapper.writeValueAsString(tree));
        } catch (Exception e) {
            log.warn("用户菜单序列化失败: userId={}", userId, e);
        }
        return Result.success(tree);
    }

    @Override
    public Result<SysMenu> add(SysMenu menu) {
        if (menu.getParentId() == null) menu.setParentId(0L);
        save(menu);
        evictAllUsers();
        return Result.success(menu);
    }

    @Override
    public Result<SysMenu> update(SysMenu menu) {
        if (menu.getId() == null || getById(menu.getId()) == null) throw new BusinessException(404, "菜单不存在");
        updateById(menu);
        evictAllUsers();
        return Result.success(menu);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> delete(Long menuId) {
        if (count(new LambdaQueryWrapper<SysMenu>().eq(SysMenu::getParentId, menuId)) > 0) {
            throw new BusinessException(409, "请先删除子菜单");
        }
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getMenuId, menuId));
        if (!removeById(menuId)) throw new BusinessException(404, "菜单不存在");
        evictAllUsers();
        return Result.success();
    }

    private List<SysMenu> withAncestors(List<SysMenu> granted) {
        // 动态菜单必须包含完整父链，否则前端无法把获授权的叶子菜单挂到导航树中。
        Map<Long, SysMenu> selected = new LinkedHashMap<>();
        for (SysMenu menu : granted) selected.put(menu.getId(), menu);
        Map<Long, SysMenu> all = new LinkedHashMap<>();
        for (SysMenu menu : list(new LambdaQueryWrapper<SysMenu>().eq(SysMenu::getStatus, 1))) all.put(menu.getId(), menu);
        List<SysMenu> seed = new ArrayList<>(selected.values());
        for (SysMenu menu : seed) {
            Long parentId = menu.getParentId();
            while (parentId != null && parentId != 0L) {
                SysMenu parent = all.get(parentId);
                if (parent == null) break;
                selected.putIfAbsent(parent.getId(), parent);
                parentId = parent.getParentId();
            }
        }
        return new ArrayList<>(selected.values());
    }

    private List<SysMenu> buildTree(List<SysMenu> menus) {
        // 先重置 children，避免缓存反序列化对象或重复构树时残留旧子节点。
        Map<Long, SysMenu> map = new LinkedHashMap<>();
        menus.stream().sorted(Comparator.comparing(SysMenu::getSortOrder,
                        Comparator.nullsLast(Integer::compareTo)).thenComparing(SysMenu::getId))
                .forEach(menu -> { menu.setChildren(new ArrayList<>()); map.put(menu.getId(), menu); });
        List<SysMenu> roots = new ArrayList<>();
        for (SysMenu menu : map.values()) {
            SysMenu parent = map.get(menu.getParentId());
            if (parent == null || menu.getParentId() == null || menu.getParentId() == 0L) roots.add(menu);
            else parent.getChildren().add(menu);
        }
        return roots;
    }

    private void evictAllUsers() {
        // 菜单结构本身发生变化会影响所有角色，因此清理全部活跃用户缓存。
        menuCacheService.evict(null);
        for (Long userId : userMapper.selectActiveUserIds()) menuCacheService.evict(userId);
    }
}
