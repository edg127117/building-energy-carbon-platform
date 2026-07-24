package com.platform.system.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Date;
import java.util.List;

/**
 * 平台管理员维护人员账号时使用的请求与响应模型。
 *
 * <p>集中定义 DTO 可以避免把数据库实体直接暴露给管理接口。特别是 {@link UserView}
 * 不包含密码字段，防止密码哈希通过查询接口返回。</p>
 */
public final class UserAdminDtos {
    private UserAdminDtos() {}

    /** 创建人员账号；未传角色时由业务层默认分配 {@code BUILDING_OWNER}。 */
    public record CreateRequest(
            @NotBlank @Size(min = 3, max = 50) String username,
            @NotBlank @Size(min = 6, max = 50) String password,
            @Size(max = 50) String nickname,
            @Size(max = 20) String phone,
            List<String> roleKeys,
            List<String> buildingIds) {}

    /** 修改人员基本资料，不允许通过该请求修改用户名、密码或角色。 */
    public record UpdateRequest(@Size(max = 50) String nickname, @Size(max = 20) String phone) {}
    /** 启用或禁用账号：1=启用，0=禁用。 */
    public record StatusRequest(@Min(0) @Max(1) Integer status) {}
    /** 管理员重置用户密码的请求。 */
    public record PasswordRequest(@NotBlank @Size(min = 6, max = 50) String password) {}
    /** 全量替换用户角色；列表至少包含一个正式角色。 */
    public record RolesRequest(@NotEmpty List<String> roleKeys) {}
    /** 全量替换用户建筑授权；空列表表示撤销全部建筑。 */
    public record BuildingsRequest(List<String> buildingIds) {}

    /** 人员管理查询视图，包含角色和建筑范围，但刻意不包含密码。 */
    public record UserView(Long id, String username, String nickname, String phone,
                           Integer status, Integer delFlag, List<String> roles,
                           List<String> buildingIds, Date createTime, Date updateTime) {}
}
