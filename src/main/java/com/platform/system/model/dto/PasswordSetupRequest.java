package com.platform.system.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 使用一次性令牌设置初始密码或完成管理员批准的密码重置。 */
public record PasswordSetupRequest(
        @NotBlank @Size(max = 128) String token,
        @NotBlank @Size(min = 6, max = 50) String password
) {
}
