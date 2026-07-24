package com.platform.system.service.impl;

import com.platform.cache.BuildingScopeCacheService;
import com.platform.framework.exception.BusinessException;
import com.platform.security.FormalRole;
import com.platform.system.mapper.SysUserBuildingMapper;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 建筑范围服务实现：平台管理员直接拥有全量范围，其他角色按用户建筑关联表取值。
 *
 * <p>读取采用 Redis 旁路缓存。授权发生变化时由人员管理或审批服务调用 {@link #evict(Long)}，
 * 保证后续请求重新从 MySQL 加载最新范围。</p>
 */
@Service
@RequiredArgsConstructor
public class BuildingScopeServiceImpl implements BuildingScopeService {
    private final SysUserBuildingMapper mapper;
    private final BuildingScopeCacheService cache;

    @Override
    public Set<String> getAccessibleBuildingIds(Long userId, Collection<String> roles) {
        // null 是服务层约定的“不过滤建筑”，只允许 PLATFORM_ADMIN 获得该返回值。
        if (roles != null && roles.stream().anyMatch(FormalRole.PLATFORM_ADMIN.name()::equalsIgnoreCase)) return null;
        Set<String> cached = cache.get(userId);
        if (cached != null) return cached;
        Set<String> ids = new LinkedHashSet<>(mapper.selectBuildingIdsByUserId(userId));
        // 空集合也要缓存，它表示用户确实没有权限，避免每次请求重复访问 MySQL。
        Set<String> immutable = Set.copyOf(ids);
        cache.set(userId, immutable);
        return immutable;
    }

    @Override
    public boolean canAccess(Long userId, Collection<String> roles, String buildingId) {
        Set<String> ids = getAccessibleBuildingIds(userId, roles);
        return ids == null || ids.contains(buildingId);
    }

    @Override
    public void checkAccess(Long userId, Collection<String> roles, String buildingId) {
        if (buildingId == null || !canAccess(userId, roles, buildingId)) {
            throw new BusinessException(403, "无权访问该建筑");
        }
    }

    @Override public void evict(Long userId) { cache.evict(userId); }
}
