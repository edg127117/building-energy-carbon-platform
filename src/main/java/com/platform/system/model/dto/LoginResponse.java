package com.platform.system.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 登录成功后返回给客户端的认证结果。
 * Token 用于后续 Bearer 请求，用户信息只用于界面恢复身份展示；建筑范围不在该响应中。
 */
@Data
@Builder
public class LoginResponse {
    /** 已写入 Redis 单账号白名单的 JWT。 */
    private String token;
    /** 固定为 {@code Bearer}，说明 Authorization 请求头的拼接方式。 */
    private String tokenType;
    /** JWT 从签发时刻起的有效秒数，不是绝对过期时间戳。 */
    private Long expiresIn;
    /** 当前账号的非敏感身份和正式角色快照。 */
    private UserInfo user;

    /** JWT principal 对应的前端展示信息，不包含密码、手机号和建筑授权。 */
    @Data
    @Builder
    public static class UserInfo {
        private Long id;
        private String username;
        private String nickname;
        private List<String> roles;
    }
}
