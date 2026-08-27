package com.platform.system.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    /** 账号开通申请包；初始密码不进入申请，审批执行后签发一次性激活令牌。 */
    public record OpenAccountRequest(
            String username,
            String nickname,
            String phone,
            List<String> roleKeys,
            List<String> buildingIds) {}

    /** 修改人员基本资料，不允许通过该请求修改用户名、密码或角色。 */
    public record UpdateRequest(@Size(max = 50) String nickname, @Size(max = 20) String phone) {}
    /** 启用或禁用账号：1=启用，0=禁用。 */
    public record StatusRequest(@Min(0) @Max(1) Integer status) {}
    /** 全量替换用户角色；列表至少包含一个正式角色。 */
    public record RolesRequest(@NotEmpty List<String> roleKeys) {}
    /** 全量替换用户建筑授权；空列表表示撤销全部建筑。 */
    public record BuildingsRequest(List<String> buildingIds) {}

    /** 人员管理查询视图，包含角色和建筑范围，但刻意不包含密码。 */
    public record UserView(Long id, String username, String nickname, String phone,
                           Integer status, Integer delFlag, List<String> roles,
                           List<String> buildingIds, Date createTime, Date updateTime) {}
}
