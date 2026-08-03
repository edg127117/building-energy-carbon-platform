package com.platform.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.model.entity.SysRole;
import com.platform.system.service.SysRoleService;
import org.springframework.stereotype.Service;

/**
 * {@link SysRoleService} 的 MySQL 实现，供账号注册解析默认正式角色。
 *
 * <p>查询和创建都落在 {@code sys_role}；返回的角色随后由用户服务写入
 * {@code sys_user_role}。本类不修改角色菜单、用户角色关系或建筑范围。</p>
 */
@Service
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {

    /** 使用角色键查找现有记录，仅在缺失时创建状态为启用的角色。 */
    @Override
    public SysRole ensureRole(String roleKey, String roleName) {
        SysRole existing = this.getOne(new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleKey, roleKey));
        if (existing != null) {
            return existing;
        }
        SysRole role = new SysRole();
        role.setRoleKey(roleKey);
        role.setRoleName(roleName);
        role.setStatus(1);
        this.save(role);
        return role;
    }
}
