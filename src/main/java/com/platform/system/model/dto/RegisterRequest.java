package com.platform.system.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 公开注册接口允许提交的最小账号资料。
 * 角色、建筑、状态和管理员属性不在输入中；服务层固定创建启用的 BUILDING_OWNER 且无建筑授权。
 */
@Data
public class RegisterRequest {

    /** 全局唯一登录名，逻辑删除账号仍占用原名称。 */
    @NotBlank
    @Size(min = 3, max = 50)
    private String username;

    /** 6 至 50 字符的原始密码，入库前由服务层编码为 BCrypt。 */
    @NotBlank
    @Size(min = 6, max = 50)
    private String password;

    /** 可空展示名，最长 50 字符，不参与认证和权限判断。 */
    @Size(max = 50)
    private String nickname;
}
