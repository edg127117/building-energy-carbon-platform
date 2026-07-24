package com.platform.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.platform.system.model.dto.LoginResponse;
import com.platform.system.model.dto.RegisterRequest;
import com.platform.system.model.entity.SysUser;

public interface SysUserService extends IService<SysUser> {
    void register(RegisterRequest request);

    LoginResponse login(String username, String password);
}
