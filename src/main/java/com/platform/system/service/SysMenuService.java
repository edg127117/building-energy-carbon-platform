package com.platform.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.platform.framework.common.Result;
import com.platform.system.model.dto.MenuAdminDtos;
import com.platform.system.model.entity.SysMenu;

import java.util.List;

/**
 * 菜单业务接口
 */
public interface SysMenuService extends IService<SysMenu> {

    /**
     * 查所有菜单，递归构建树形结构（5级），根节点 parentId=0L，status=1 的才返回
     */
    Result<List<SysMenu>> buildTree();

    /** 查询完整维护树，包含隐藏和停用节点，不改变当前用户导航的过滤语义。 */
    Result<List<SysMenu>> adminTree();

    /**
     * 通过 sys_role → sys_role_menu → sys_menu 三表联查，返回某角色有权限的菜单树
     */
    Result<List<SysMenu>> listByRole(String roleKey);

    /**
     * 查询当前用户所有正式角色的菜单并集，补齐父目录后生成菜单树并写入 Redis。
     * THIRD_PARTY 默认没有内部菜单，因此可正常返回空列表。
     */
    Result<List<SysMenu>> currentMenu(Long userId);

    /**
     * 新增菜单
     */
    Result<SysMenu> add(MenuAdminDtos.CreateRequest request);

    /**
     * 更新菜单
     */
    Result<SysMenu> update(MenuAdminDtos.UpdateRequest request);

    /**
     * 删除菜单
     */
    Result<Void> delete(Long menuId);
}
