package com.platform.system.model.dto;

import java.util.List;

/** 平台管理员查看正式角色及分配动态菜单时使用的 DTO。 */
public final class RoleAdminDtos {
    private RoleAdminDtos() {}

    /** 正式角色的只读展示信息。 */
    public record RoleView(Long id, String roleKey, String roleName, String dataScope, Integer status) {}
    /** 全量替换某个角色菜单集合的请求；空列表表示该角色没有内部菜单。 */
    public record MenuAssignmentRequest(List<Long> menuIds) {}
}
