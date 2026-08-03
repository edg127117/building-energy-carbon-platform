package com.platform.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.platform.system.model.dto.LoginResponse;
import com.platform.system.model.dto.RegisterRequest;
import com.platform.system.model.entity.SysUser;

/**
 * 公开注册、登录接口使用的账号用例边界。
 *
 * <p>实现负责 MySQL 账号与默认角色关系、密码校验、JWT 签发和 Redis Token 白名单；
 * 建筑查看范围由独立权限服务维护，不随注册请求直接授予。</p>
 */
public interface SysUserService extends IService<SysUser> {
    /** 创建账号并绑定固定的建筑业主角色；用户名冲突以业务异常返回。 */
    void register(RegisterRequest request);

    /** 校验账号、密码和正式角色后返回登录 Token 与用户摘要。 */
    LoginResponse login(String username, String password);
}
