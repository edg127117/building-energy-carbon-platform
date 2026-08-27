package com.platform.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.platform.system.model.dto.UserAdminDtos;

import java.util.List;

/**
 * PLATFORM_ADMIN 专用的人员账号生命周期服务。
 *
 * <p>负责人员查询、创建、逻辑删除、恢复、状态、密码、角色和建筑范围管理。
 * 角色或账号安全状态变化时会撤销当前 Token，防止旧 JWT 继续持有过期权限。</p>
 */
public interface SysUserAdminService {
    /** 分页查询人员；{@code includeDeleted=true} 时包含逻辑删除账号。 */
    IPage<UserAdminDtos.UserView> page(int page, int size, String keyword, Integer status, boolean includeDeleted);
    /** 查询人员详情，允许查看逻辑删除账号。 */
    UserAdminDtos.UserView detail(Long id);
    /** 审批执行时原子创建账号、角色和建筑授权，并签发一次性激活令牌。 */
    PasswordSetupTokenService.IssuedToken openAccount(
            UserAdminDtos.OpenAccountRequest request, String sourceRequestId, long operatorId,
            boolean selfApprovalDevMode);
    /** 修改昵称、手机等非安全资料。 */
    UserAdminDtos.UserView update(Long id, UserAdminDtos.UpdateRequest request);
    /** 逻辑删除人员，同时撤销角色、建筑、Token 和相关缓存。 */
    void delete(Long currentUserId, Long id);
    /** 恢复逻辑删除账号；原角色和建筑不会自动恢复，需要管理员重新分配。 */
    UserAdminDtos.UserView restore(Long id);
    /** 启用或禁用账号；禁止管理员禁用自己或系统最后一个平台管理员。 */
    void updateStatus(Long currentUserId, Long id, Integer status);
    /** 全量替换正式角色，并保护当前管理员及最后一个平台管理员。 */
    void replaceRoles(Long currentUserId, Long id, List<String> roleKeys);
    /** 全量替换用户建筑范围。 */
    void replaceBuildings(Long id, List<String> buildingIds);
    /** 撤销一个指定建筑授权。 */
    void revokeBuilding(Long id, String buildingId);
}
