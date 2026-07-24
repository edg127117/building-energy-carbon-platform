package com.platform.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.platform.system.model.entity.SysRole;

public interface SysRoleService extends IService<SysRole> {
    SysRole ensureRole(String roleKey, String roleName);
}
