package com.platform.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.cache.MenuCacheService;
import com.platform.cache.TokenCacheService;
import com.platform.audit.AuditEvidence;
import com.platform.audit.AuditEvidenceWriter;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.mapper.BuildingMapper;
import com.platform.security.FormalRole;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.mapper.SysUserBuildingMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.mapper.SysUserRoleMapper;
import com.platform.system.model.dto.UserAdminDtos;
import com.platform.system.model.entity.SysRole;
import com.platform.system.model.entity.SysUser;
import com.platform.system.model.entity.SysUserBuilding;
import com.platform.system.model.entity.SysUserRole;
import com.platform.system.service.SysUserAdminService;
import com.platform.system.service.BuildingScopeService;
import com.platform.system.service.PasswordSetupTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 平台管理端人员服务实现。
 *
 * <p>上游是 PLATFORM_ADMIN 人员管理接口，下游写入 MySQL 用户、角色和建筑关联表，并协调
 * Token、菜单、建筑范围三类 Redis 状态。除资料维护外，本类还保护当前操作者和系统最后一个
 * 有效平台管理员，避免后台失去管理入口。</p>
 *
 * <p>角色属于 JWT 快照，变更后必须撤销 Token；建筑范围不写入 JWT，只清范围缓存即可动态生效。
 * 逻辑删除会同时清除角色和建筑关系，恢复账号不会恢复旧权限。</p>
 */
@Service
@RequiredArgsConstructor
public class SysUserAdminServiceImpl implements SysUserAdminService {
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysUserBuildingMapper userBuildingMapper;
    private final BuildingMapper buildingMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenCacheService tokenCacheService;
    private final MenuCacheService menuCacheService;
    private final BuildingScopeService buildingScopeService;
    private final PasswordSetupTokenService passwordSetupTokenService;
    private final AuditEvidenceWriter auditWriter;
    private final AuditGovernanceProperties auditProperties;

    /**
     * 分页返回人员及其正式角色、建筑范围。
     *
     * <p>普通列表使用 MyBatis-Plus 分页并自动排除逻辑删除记录；管理员明确要求包含删除记录时，
     * 改用专用 Mapper 绕过 {@code @TableLogic}，再在内存中筛选和分页，避免把“查不到”误当作
     * 已删除账号不存在。每页最多 100 条。</p>
     */
    @Override
    public Page<UserAdminDtos.UserView> page(int page, int size, String keyword, Integer status, boolean includeDeleted) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 1);
        if (includeDeleted) {
            // MyBatis-Plus 的 @TableLogic 会自动排除删除数据，因此包含删除记录时使用专用 SQL 后手工分页。
            String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase() : null;
            List<SysUser> filtered = userMapper.selectAllIncludingDeleted().stream()
                    .filter(user -> status == null || status.equals(user.getStatus()))
                    .filter(user -> normalizedKeyword == null
                            || containsIgnoreCase(user.getUsername(), normalizedKeyword)
                            || containsIgnoreCase(user.getNickname(), normalizedKeyword)
                            || containsIgnoreCase(user.getPhone(), normalizedKeyword))
                    .toList();
            int fromIndex = Math.min((safePage - 1) * safeSize, filtered.size());
            int toIndex = Math.min(fromIndex + safeSize, filtered.size());
            Page<UserAdminDtos.UserView> result = new Page<>(safePage, safeSize, filtered.size());
            result.setRecords(filtered.subList(fromIndex, toIndex).stream().map(this::toView).toList());
            return result;
        }
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getNickname, keyword)
                    .or().like(SysUser::getPhone, keyword));
        }
        if (status != null) wrapper.eq(SysUser::getStatus, status);
        wrapper.orderByDesc(SysUser::getCreateTime);
        Page<SysUser> users = userMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        return (Page<UserAdminDtos.UserView>) users.convert(this::toView);
    }

    @Override
    public UserAdminDtos.UserView detail(Long id) {
        SysUser user = requireAnyUser(id);
        return toView(user);
    }

    /**
     * 执行已批准的账号开通申请包。
     *
     * <p>账号、角色、建筑、令牌哈希和三类业务审计在同一事务提交。新账号保持停用且待激活，
     * 原始激活令牌只由执行响应返回；任一授权或审计失败都会整体回滚。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PasswordSetupTokenService.IssuedToken openAccount(
            UserAdminDtos.OpenAccountRequest request, String sourceRequestId, long operatorId,
            boolean selfApprovalDevMode) {
        // 已删除账号的用户名也保留，避免恢复账号后发生用户名冲突。
        if (userMapper.selectAnyByUsername(request.username()) != null) {
            throw new BusinessException(409, "用户名已存在（包括已删除账号）");
        }
        SysUser user = new SysUser();
        user.setUsername(request.username().trim());
        // 数据库非空约束使用不可知随机值；真正密码只能由一次性激活令牌设置。
        user.setPassword(passwordEncoder.encode(UUID.randomUUID() + UUID.randomUUID().toString()));
        user.setNickname(request.nickname());
        user.setPhone(request.phone());
        user.setStatus(0);
        user.setDelFlag(0);
        user.setActivationPending(1);
        userMapper.insert(user);
        replaceRolesInternal(user.getId(), request.roleKeys());
        replaceBuildingsInternal(user.getId(), request.buildingIds());
        menuCacheService.evict(user.getId());
        PasswordSetupTokenService.IssuedToken token = passwordSetupTokenService.issue(
                user.getId(), sourceRequestId, operatorId);
        appendOpeningEvidence(user.getId(), request.roleKeys(), request.buildingIds(), sourceRequestId,
                operatorId, selfApprovalDevMode);
        return token;
    }

    @Override
    public UserAdminDtos.UserView update(Long id, UserAdminDtos.UpdateRequest request) {
        SysUser user = requireActiveUser(id);
        user.setNickname(request.nickname());
        user.setPhone(request.phone());
        userMapper.updateById(user);
        return toView(user);
    }

    /**
     * 逻辑删除人员并撤销其全部授权和登录态。
     * 禁止删除当前操作者或最后一个有效平台管理员；删除成功后清理角色、建筑关系以及三类缓存，
     * 恢复时必须由管理员重新确认权限。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long currentUserId, Long id) {
        // 自删除和删除最后一个平台管理员都会导致后台失去管理入口，因此必须阻止。
        if (id.equals(currentUserId)) throw new BusinessException(409, "不能删除当前登录管理员");
        SysUser user = requireActiveUser(id);
        protectLastAdmin(user);
        // 用户主体逻辑删除，角色和建筑关联直接清除；恢复时由管理员重新确认权限。
        userMapper.logicalDelete(id);
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
        userBuildingMapper.delete(new LambdaQueryWrapper<SysUserBuilding>().eq(SysUserBuilding::getUserId, id));
        buildingScopeService.evict(id);
        tokenCacheService.revokeActiveToken(id);
        menuCacheService.evict(id);
    }

    @Override
    public UserAdminDtos.UserView restore(Long id) {
        SysUser user = requireAnyUser(id);
        if (user.getDelFlag() == null || user.getDelFlag() == 0) throw new BusinessException(409, "用户未被删除");
        userMapper.restore(id);
        return toView(requireAnyUser(id));
    }

    /**
     * 启用或禁用账号。禁用当前管理员和最后一个有效平台管理员会被拒绝；禁用其他账号后撤销
     * 当前 Token，使已经签发的 JWT 不能继续访问受保护接口。
     */
    @Override
    public void updateStatus(Long currentUserId, Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) throw new BusinessException(400, "状态只能是0或1");
        if (id.equals(currentUserId) && status == 0) throw new BusinessException(409, "不能禁用当前登录管理员");
        SysUser user = requireActiveUser(id);
        if (status == 1 && user.getActivationPending() != null && user.getActivationPending() == 1) {
            throw new BusinessException(409, "账号尚未使用一次性令牌设置初始密码");
        }
        if (status == 0) protectLastAdmin(user);
        user.setStatus(status);
        userMapper.updateById(user);
        if (status == 0) tokenCacheService.revokeActiveToken(id);
    }

    /**
     * 全量替换用户正式角色。
     *
     * <p>先规范化并验证四类角色，再保护当前管理员和最后管理员约束。角色写入 JWT claims，
     * 因此事务完成后撤销旧 Token 并清理菜单缓存，用户重新登录后才能取得新权限。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceRoles(Long currentUserId, Long id, List<String> roleKeys) {
        requireActiveUser(id);
        Set<String> normalized = normalizeRoles(roleKeys);
        if (id.equals(currentUserId) && !normalized.contains(FormalRole.PLATFORM_ADMIN.name())) {
            throw new BusinessException(409, "不能取消当前管理员自己的平台管理员角色");
        }
        if (hasRole(id, FormalRole.PLATFORM_ADMIN.name())
                && !normalized.contains(FormalRole.PLATFORM_ADMIN.name())
                && userMapper.countActivePlatformAdmins() <= 1) {
            throw new BusinessException(409, "系统必须保留至少一个有效平台管理员");
        }
        replaceRolesInternal(id, List.copyOf(normalized));
        // 角色写在 JWT claims 中，变更后必须撤销旧 Token，用户重新登录才能获得新角色。
        tokenCacheService.revokeActiveToken(id);
        menuCacheService.evict(id);
    }

    /** 全量替换 MySQL 用户建筑关系，并清理范围缓存使新范围在下一次请求立即生效。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceBuildings(Long id, List<String> buildingIds) {
        requireActiveUser(id);
        replaceBuildingsInternal(id, buildingIds);
    }

    @Override
    public void revokeBuilding(Long id, String buildingId) {
        requireActiveUser(id);
        userBuildingMapper.delete(new LambdaQueryWrapper<SysUserBuilding>()
                .eq(SysUserBuilding::getUserId, id).eq(SysUserBuilding::getBuildingId, buildingId));
        buildingScopeService.evict(id);
    }

    private void replaceRolesInternal(Long userId, List<String> roleKeys) {
        Set<String> normalized = normalizeRoles(roleKeys);
        List<SysRole> roles = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .in(SysRole::getRoleKey, normalized).eq(SysRole::getStatus, 1));
        if (roles.size() != normalized.size()) throw new BusinessException(400, "包含不存在或停用的正式角色");
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        for (SysRole role : roles) {
            SysUserRole link = new SysUserRole();
            link.setUserId(userId);
            link.setRoleId(role.getId());
            userRoleMapper.insert(link);
        }
    }

    /** 去重并统一角色键大小写；空集合、旧角色和未知角色都在写库前按 400 拒绝。 */
    private Set<String> normalizeRoles(List<String> roleKeys) {
        if (roleKeys == null || roleKeys.isEmpty()) throw new BusinessException(400, "用户至少需要一个正式角色");
        Set<String> result = new LinkedHashSet<>();
        for (String key : roleKeys) {
            String normalized = key == null ? "" : key.trim().toUpperCase();
            if (!FormalRole.isFormal(normalized)) throw new BusinessException(400, "非法角色: " + key);
            result.add(normalized);
        }
        return result;
    }

    /**
     * 实现建筑关系的事务内全量替换：先验证所有建筑，再删除旧关系和写入新关系。
     * 先校验可避免无效建筑导致用户原授权被提前清空。
     */
    private void replaceBuildingsInternal(Long userId, List<String> buildingIds) {
        // 建筑采用全量替换语义：先验证所有目标建筑，再删除旧关系并写入新关系。
        Set<String> ids = buildingIds == null ? Set.of() : new LinkedHashSet<>(buildingIds);
        for (String buildingId : ids) {
            if (buildingMapper.selectById(buildingId) == null) throw new BusinessException(404, "建筑不存在: " + buildingId);
        }
        userBuildingMapper.delete(new LambdaQueryWrapper<SysUserBuilding>().eq(SysUserBuilding::getUserId, userId));
        for (String buildingId : ids) {
            SysUserBuilding link = new SysUserBuilding();
            link.setUserId(userId);
            link.setBuildingId(buildingId);
            userBuildingMapper.insert(link);
        }
        buildingScopeService.evict(userId);
    }

    /** 公共申请只保存脱敏影响摘要；账号、角色和逐建筑授权分别形成可检索业务事实。 */
    private void appendOpeningEvidence(Long userId, List<String> roles, List<String> buildings,
                                       String sourceRequestId, long operatorId, boolean selfApprovalDevMode) {
        LocalDateTime now = LocalDateTime.now();
        appendOpeningEvent(null, operatorId, "CREATE_USER_ACCOUNT", "USER_ACCOUNT", Long.toString(userId),
                "status=PENDING_ACTIVATION", sourceRequestId, now, selfApprovalDevMode);
        for (String role : new LinkedHashSet<>(roles)) {
            appendOpeningEvent(null, operatorId, "GRANT_USER_FORMAL_ROLE", "USER_FORMAL_ROLE",
                    userId + ":" + role, "roleKey=" + role, sourceRequestId, now, selfApprovalDevMode);
        }
        Set<String> buildingIds = buildings == null ? Set.of() : new LinkedHashSet<>(buildings);
        if (buildingIds.isEmpty()) {
            appendOpeningEvent(null, operatorId, "ASSIGN_USER_BUILDING_SCOPE", "USER_BUILDING_SCOPE",
                    Long.toString(userId), "buildingCount=0", sourceRequestId, now, selfApprovalDevMode);
        } else {
            for (String buildingId : buildingIds) {
                appendOpeningEvent(buildingId, operatorId, "GRANT_USER_BUILDING_ACCESS", "USER_BUILDING_SCOPE",
                        userId + ":" + buildingId, "buildingId=" + buildingId,
                        sourceRequestId, now, selfApprovalDevMode);
            }
        }
    }

    private void appendOpeningEvent(String buildingId, long operatorId, String action, String objectType,
                                    String objectId, String afterSummary, String sourceRequestId,
                                    LocalDateTime now, boolean selfApprovalDevMode) {
        auditWriter.append(new AuditEvidence("SYSTEM_SECURITY", buildingId, "USER", operatorId,
                action, objectType, objectId, null, sourceRequestId, null, afterSummary,
                "SUCCESS", null, TraceContext.current(), now, auditProperties.getEnvironmentMode(),
                selfApprovalDevMode));
    }

    private UserAdminDtos.UserView toView(SysUser user) {
        List<String> roles = roleMapper.selectRoleKeysByUserId(user.getId()).stream()
                .filter(FormalRole::isFormal).distinct().toList();
        List<String> buildings = userBuildingMapper.selectBuildingIdsByUserId(user.getId());
        return new UserAdminDtos.UserView(user.getId(), user.getUsername(), user.getNickname(), user.getPhone(),
                user.getStatus(), user.getDelFlag(), roles, buildings, user.getCreateTime(), user.getUpdateTime());
    }

    private SysUser requireActiveUser(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) throw new BusinessException(404, "用户不存在或已删除");
        return user;
    }

    private SysUser requireAnyUser(Long id) {
        SysUser user = userMapper.selectAnyById(id);
        if (user == null) throw new BusinessException(404, "用户不存在");
        return user;
    }

    private boolean hasRole(Long userId, String roleKey) {
        return roleMapper.selectRoleKeysByUserId(userId).stream().anyMatch(roleKey::equalsIgnoreCase);
    }

    private boolean containsIgnoreCase(String value, String normalizedKeyword) {
        return value != null && value.toLowerCase().contains(normalizedKeyword);
    }

    /** 删除、禁用或降级前保护最后一个有效 PLATFORM_ADMIN，保证系统始终保留管理入口。 */
    private void protectLastAdmin(SysUser user) {
        if (hasRole(user.getId(), FormalRole.PLATFORM_ADMIN.name()) && userMapper.countActivePlatformAdmins() <= 1) {
            throw new BusinessException(409, "系统必须保留至少一个有效平台管理员");
        }
    }
}
