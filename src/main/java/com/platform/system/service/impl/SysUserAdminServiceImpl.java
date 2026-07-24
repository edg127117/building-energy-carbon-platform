package com.platform.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.cache.MenuCacheService;
import com.platform.cache.TokenCacheService;
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
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 平台管理端人员服务实现。
 *
 * <p>除基础 CRUD 外，本类还承担三项安全职责：只接受四类正式角色；保护当前管理员和
 * 系统最后一个有效平台管理员；在角色、密码、状态或删除变化后撤销旧 Token 和相关缓存。</p>
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserAdminDtos.UserView create(UserAdminDtos.CreateRequest request) {
        // 已删除账号的用户名也保留，避免恢复账号后发生用户名冲突。
        if (userMapper.selectAnyByUsername(request.username()) != null) {
            throw new BusinessException(409, "用户名已存在（包括已删除账号）");
        }
        SysUser user = new SysUser();
        user.setUsername(request.username().trim());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname());
        user.setPhone(request.phone());
        user.setStatus(1);
        user.setDelFlag(0);
        userMapper.insert(user);
        // 管理员创建账号未指定角色时，使用最低权限 BUILDING_OWNER，且不会自动授予建筑。
        List<String> roles = request.roleKeys() == null || request.roleKeys().isEmpty()
                ? List.of(FormalRole.BUILDING_OWNER.name()) : request.roleKeys();
        replaceRolesInternal(user.getId(), roles);
        replaceBuildingsInternal(user.getId(), request.buildingIds());
        menuCacheService.evict(user.getId());
        return toView(user);
    }

    @Override
    public UserAdminDtos.UserView update(Long id, UserAdminDtos.UpdateRequest request) {
        SysUser user = requireActiveUser(id);
        user.setNickname(request.nickname());
        user.setPhone(request.phone());
        userMapper.updateById(user);
        return toView(user);
    }

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

    @Override
    public void updateStatus(Long currentUserId, Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) throw new BusinessException(400, "状态只能是0或1");
        if (id.equals(currentUserId) && status == 0) throw new BusinessException(409, "不能禁用当前登录管理员");
        SysUser user = requireActiveUser(id);
        if (status == 0) protectLastAdmin(user);
        user.setStatus(status);
        userMapper.updateById(user);
        if (status == 0) tokenCacheService.revokeActiveToken(id);
    }

    @Override
    public void resetPassword(Long id, String password) {
        SysUser user = requireActiveUser(id);
        user.setPassword(passwordEncoder.encode(password));
        userMapper.updateById(user);
        tokenCacheService.revokeActiveToken(id);
    }

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

    private void protectLastAdmin(SysUser user) {
        if (hasRole(user.getId(), FormalRole.PLATFORM_ADMIN.name()) && userMapper.countActivePlatformAdmins() <= 1) {
            throw new BusinessException(409, "系统必须保留至少一个有效平台管理员");
        }
    }
}
