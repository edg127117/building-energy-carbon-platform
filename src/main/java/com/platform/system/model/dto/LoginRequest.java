package com.platform.system.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 登录接口的账号凭据输入；字段只用于本次校验，不写入响应、JWT 或日志。 */
@Data
public class LoginRequest {

    /** MySQL {@code sys_user.username} 中的登录名。 */
    @NotBlank
    private String username;

    /** 原始密码，由服务层与 BCrypt 或受控历史明文值比对。 */
    @NotBlank
    private String password;
}
