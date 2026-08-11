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
import com.platform.system.model.dto.MenuAdminDtos;
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
import java.util.HashSet;
import java.util.Set;

/**
 * 动态菜单服务实现。
 *
 * <p>上游是菜单 Controller，下游是 MySQL 菜单/角色关系和 Redis 菜单缓存。用户菜单由
 * “用户 → 正式角色 → 角色菜单”关系合并，直接授权到叶子时补齐父目录并按
 * {@code sortOrder/id} 构树。菜单只决定前端导航内容，不能替代接口上的后端角色校验。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysMenuServiceImpl extends ServiceImpl<SysMenuMapper, SysMenu> implements SysMenuService {
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserMapper userMapper;
    private final MenuCacheService menuCacheService;
    private final ObjectMapper objectMapper;

    /** 查询全部启用菜单并构造成平台管理员使用的完整树，不读取个人菜单缓存。 */
    @Override
    public Result<List<SysMenu>> buildTree() {
        List<SysMenu> menus = list(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getStatus, 1).orderByAsc(SysMenu::getSortOrder).orderByAsc(SysMenu::getId));
        return Result.success(buildTree(menus));
    }

    /** 完整维护树不按可见性和启停状态过滤，避免隐藏配置失去恢复入口。 */
    @Override
    public Result<List<SysMenu>> adminTree() {
        return Result.success(buildTree(list(new LambdaQueryWrapper<SysMenu>()
                .orderByAsc(SysMenu::getSortOrder).orderByAsc(SysMenu::getId))));
    }

    /** 按角色读取 MySQL 授权菜单，补齐父目录后构树，供平台管理员检查角色配置。 */
    @Override
    public Result<List<SysMenu>> listByRole(String roleKey) {
        return Result.success(buildTree(withAncestors(roleMenuMapper.selectMenusByRoleKey(roleKey))));
    }

    /**
     * 返回当前用户的导航树。
     * 缓存命中时直接反序列化；未命中、损坏或 Redis 故障时查询 MySQL 角色菜单关系，
     * 构树后尽力写回缓存。空树是合法结果，例如没有内部菜单的第三方账号。
     */
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
    public Result<SysMenu> add(MenuAdminDtos.CreateRequest request) {
        validateEnums(request.menuType(), request.visible(), request.status());
        Long parentId = defaultParent(request.parentId());
        requireParent(parentId);
        SysMenu menu = new SysMenu();
        apply(menu, parentId, request.menuName(), request.menuType(), request.path(), request.component(),
                request.perms(), request.icon(), defaultFlag(request.visible()), defaultFlag(request.status()),
                request.sortOrder() == null ? 0 : request.sortOrder());
        save(menu);
        evictAllUsers();
        return Result.success(menu);
    }

    @Override
    public Result<SysMenu> update(MenuAdminDtos.UpdateRequest request) {
        validateEnums(request.menuType(), request.visible(), request.status());
        SysMenu menu = getById(request.id());
        if (menu == null) throw new BusinessException(404, "菜单不存在");
        Long parentId = defaultParent(request.parentId());
        validateParentChain(request.id(), parentId);
        apply(menu, parentId, request.menuName(), request.menuType(), request.path(), request.component(),
                request.perms(), request.icon(), defaultFlag(request.visible()), defaultFlag(request.status()),
                request.sortOrder() == null ? 0 : request.sortOrder());
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

    /**
     * 校验更新后的完整父链。除禁止自引用外，也拒绝把节点挂到自己的后代或已有异常环中，
     * 避免构树时节点永久丢失。数据库层仍保存平面关系，不依赖前端候选项过滤保证正确性。
     */
    private void validateParentChain(Long menuId, Long parentId) {
        if (menuId.equals(parentId)) {
            throw new BusinessException(409, "菜单不能选择自身作为父级");
        }
        Set<Long> visited = new HashSet<>();
        Long current = parentId;
        while (current != null && current != 0L) {
            if (!visited.add(current) || menuId.equals(current)) {
                throw new BusinessException(409, "父菜单层级存在循环");
            }
            SysMenu parent = getById(current);
            if (parent == null) throw new BusinessException(404, "父菜单不存在");
            current = parent.getParentId();
        }
    }

    private void requireParent(Long parentId) {
        if (parentId != 0L && getById(parentId) == null) {
            throw new BusinessException(404, "父菜单不存在");
        }
    }

    private void validateEnums(String menuType, Integer visible, Integer status) {
        if (menuType == null || !Set.of("M", "C", "F").contains(menuType.trim())) {
            throw new BusinessException("菜单类型不合法");
        }
        if ((visible != null && visible != 0 && visible != 1)
                || (status != null && status != 0 && status != 1)) {
            throw new BusinessException("菜单可见性或状态不合法");
        }
    }

    private void apply(SysMenu menu, Long parentId, String menuName, String menuType, String path,
                       String component, String perms, String icon, Integer visible, Integer status,
                       Integer sortOrder) {
        menu.setParentId(parentId);
        menu.setMenuName(menuName.trim());
        menu.setMenuType(menuType.trim());
        menu.setPath(normalize(path));
        menu.setComponent(normalize(component));
        menu.setPerms(normalize(perms));
        menu.setIcon(normalize(icon));
        menu.setVisible(visible);
        menu.setStatus(status);
        menu.setSortOrder(sortOrder);
    }

    private String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Long defaultParent(Long parentId) {
        return parentId == null ? 0L : parentId;
    }

    private Integer defaultFlag(Integer value) {
        return value == null ? 1 : value;
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
