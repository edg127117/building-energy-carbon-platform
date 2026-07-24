package com.platform.system.service;

import java.util.Collection;
import java.util.Set;

/**
 * 统一的建筑数据范围服务。
 *
 * <p>所有按建筑隔离的数据接口都应通过本服务取得或校验范围，不能只依赖前端传参。
 * 返回值约定非常重要：{@code null} 表示平台管理员可访问全部建筑，空集合表示普通用户没有建筑权限。</p>
 */
public interface BuildingScopeService {
    /**
     * 查询用户可访问的建筑集合。
     *
     * @return {@code null}=全部建筑；空集合=无建筑；非空集合=只能访问集合中的建筑
     */
    Set<String> getAccessibleBuildingIds(Long userId, Collection<String> roles);

    /** 判断用户是否可以访问指定建筑。 */
    boolean canAccess(Long userId, Collection<String> roles, String buildingId);

    /**
     * 强制校验指定建筑权限。
     *
     * @throws com.platform.framework.exception.BusinessException 无权限时抛出 HTTP 403 业务异常
     */
    void checkAccess(Long userId, Collection<String> roles, String buildingId);

    /** 建筑授权发生变化后清理该用户缓存。 */
    void evict(Long userId);
}
