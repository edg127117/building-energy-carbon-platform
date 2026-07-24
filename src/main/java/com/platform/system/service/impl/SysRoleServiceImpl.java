package com.platform.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.model.entity.SysRole;
import com.platform.system.service.SysRoleService;
import org.springframework.stereotype.Service;

@Service
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {

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
