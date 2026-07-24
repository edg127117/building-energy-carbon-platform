package com.platform.cache;

/**
 * Redis 缓存 Key 前缀常量
 * 冻结书 D-010：Redis 仅用于缓存，Key 前缀统一管理便于后期清理与监控
 */
public final class CacheConstants {

    private CacheConstants() {}

    /** 项目统一前缀，避免与其他系统 Key 冲突 */
    public static final String PREFIX = "iot:";

    // ──────────── 设备状态缓存 ────────────
    /** 设备在线状态：iot:device:status:{deviceId} → 0/1/2 */
    public static final String DEVICE_STATUS = PREFIX + "device:status:";

    // ──────────── COP 最新值缓存 ────────────
    /** COP 指标最新值：iot:indicator:latest:{indicatorCode}:{deviceId} → value */
    public static final String INDICATOR_LATEST = PREFIX + "indicator:latest:";

    // ──────────── Token 缓存 ────────────
    /** 已登录 Token 白名单：iot:token:whitelist:{userId} → token */
    public static final String TOKEN_WHITELIST = PREFIX + "token:whitelist:";
    /** Token 黑名单（登出/踢人）：iot:token:blacklist:{token} → 过期时间戳 */
    public static final String TOKEN_BLACKLIST = PREFIX + "token:blacklist:";

    // ──────────── 菜单缓存 ────────────
    /** 用户菜单树：iot:menu:user:{userId} → JSON */
    public static final String MENU_USER = PREFIX + "menu:user:";
    /** 全量菜单树：iot:menu:all → JSON（角色变更时刷新） */
    public static final String MENU_ALL = PREFIX + "menu:all";

    /** 用户可访问建筑ID集合(JSON)：iot:building-scope:user:{userId} */
    public static final String BUILDING_SCOPE_USER = PREFIX + "building-scope:user:";

    // ──────────── 心跳检测 ────────────
    /** 设备心跳时间戳 Sorted Set：score=最近上报时间(ms)，member=deviceId */
    public static final String HEARTBEAT_TIMESTAMPS = PREFIX + "heartbeat:timestamps";

    // ──────────── 分布式锁（Redisson 预留） ────────────
    /** COP 定时任务锁：iot:lock:cop-engine */
    public static final String LOCK_COP_ENGINE = PREFIX + "lock:cop-engine";
    /** 设备状态同步锁：iot:lock:device-status:{deviceId} */
    public static final String LOCK_DEVICE_STATUS = PREFIX + "lock:device-status:";
}
