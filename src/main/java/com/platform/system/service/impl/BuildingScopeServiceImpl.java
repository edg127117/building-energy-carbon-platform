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
 * 把当前用户和角色转换成建筑数据过滤范围的服务实现。
 *
 * <p>平台管理员以 {@code null} 表示无需增加建筑过滤条件；其他角色从
 * {@code sys_user_building} 读取明确授权，空集合表示没有任何建筑。读取采用 Redis 旁路缓存，
 * 人员分配、撤销或审批授权后调用 {@link #evict(Long)}，使下一次请求回源 MySQL。</p>
 */
@Service
@RequiredArgsConstructor
public class BuildingScopeServiceImpl implements BuildingScopeService {
    private final SysUserBuildingMapper mapper;
    private final BuildingScopeCacheService cache;

    /**
     * 取得可直接传给 Repository/Service 查询条件的建筑集合。
     * Redis 未命中、损坏或不可用时由缓存适配器返回 {@code null}，普通用户随即回源 MySQL；
     * 只有角色中包含 PLATFORM_ADMIN 时，最终结果才允许为 {@code null}。
     */
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

    /** 判断单个建筑是否落在当前范围内；管理员的全量标记会直接通过。 */
    @Override
    public boolean canAccess(Long userId, Collection<String> roles, String buildingId) {
        Set<String> ids = getAccessibleBuildingIds(userId, roles);
        return ids == null || ids.contains(buildingId);
    }

    /** 在业务查询前强制执行建筑范围校验，空建筑或越权都转换为可见的 HTTP 403。 */
    @Override
    public void checkAccess(Long userId, Collection<String> roles, String buildingId) {
        if (buildingId == null || !canAccess(userId, roles, buildingId)) {
            throw new BusinessException(403, "无权访问该建筑");
        }
    }

    @Override public void evict(Long userId) { cache.evict(userId); }
}
