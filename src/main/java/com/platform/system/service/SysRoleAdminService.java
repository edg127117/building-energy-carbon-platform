package com.platform.system.service;

import com.platform.system.model.dto.RoleAdminDtos;

import java.util.List;

/** 平台管理员维护四类正式角色及其动态菜单关系的服务。 */
public interface SysRoleAdminService {
    /** 只返回四类正式角色，不返回历史 {@code ADMIN}/{@code USER} 角色。 */
    List<RoleAdminDtos.RoleView> list();
    /** 查询指定正式角色详情。 */
    RoleAdminDtos.RoleView detail(Long id);
    /** 查询角色当前关联的菜单 ID。 */
    List<Long> menuIds(Long id);
    /** 全量替换角色菜单，并清理所有受影响用户的菜单缓存。 */
    void replaceMenus(Long id, List<Long> menuIds);
}
