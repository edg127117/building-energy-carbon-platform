package com.platform.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.platform.system.model.entity.SysRole;

/**
 * 注册流程解析默认角色的窄接口。
 *
 * <p>这里只保证指定角色键在 MySQL 中有可用记录，不负责管理员菜单分配、用户换角
 * 或建筑范围授权；这些变更分别由角色管理和建筑权限服务处理。</p>
 */
public interface SysRoleService extends IService<SysRole> {
    /** 按稳定角色键返回已有角色；不存在时以给定名称创建启用记录。 */
    SysRole ensureRole(String roleKey, String roleName);
}
