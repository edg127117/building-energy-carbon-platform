package com.platform.security;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 系统当前认可的四类正式业务角色。
 *
 * <p>这里是角色键的统一来源。JWT 过滤、人员管理和接口鉴权只接受这些角色，
 * 历史数据中的 {@code ADMIN}/{@code USER} 可以保留在数据库中，但不会再被当作正式权限使用。</p>
 */
public enum FormalRole {
    /** 建筑业主：只读查看已授权建筑的拓扑、设备、测点和指标。 */
    BUILDING_OWNER,
    /** 能效管理方：管理已授权建筑的数据接入和能效配置。 */
    ENERGY_MANAGER,
    /** 第三方接口账号：通过 {@code /open-api/**} 读取获授权建筑数据。 */
    THIRD_PARTY,
    /** 平台管理员：人员、角色、菜单和全部建筑的后台管理权限。 */
    PLATFORM_ADMIN;

    private static final Set<String> KEYS = Arrays.stream(values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    /**
     * 判断角色键是否属于四类正式角色，比较时忽略大小写。
     *
     * @param roleKey 数据库角色键或 JWT 中的角色键
     * @return 正式角色返回 {@code true}，空值、旧角色或未知角色返回 {@code false}
     */
    public static boolean isFormal(String roleKey) {
        return roleKey != null && KEYS.contains(roleKey.toUpperCase());
    }

    /**
     * 返回全部正式角色键的只读集合，常用于数据库查询条件和角色参数校验。
     */
    public static Set<String> keys() {
        return KEYS;
    }
}
