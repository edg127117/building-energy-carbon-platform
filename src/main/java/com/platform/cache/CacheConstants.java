package com.platform.cache;

/**
 * 各业务 Redis 键的统一命名入口。
 * 统一 {@code iot:} 前缀便于隔离环境、定向失效和观察缓存；常量只描述键结构，
 * TTL、事实数据来源和降级策略由各缓存服务负责。
 */
public final class CacheConstants {

    private CacheConstants() {}

    /** 项目统一前缀，避免与其他系统 Key 冲突 */
    public static final String PREFIX = "iot:";

    // ──────────── 设备状态缓存 ────────────
    /** 设备在线状态：iot:device:status:{deviceId} → 0/1/2 */
    public static final String DEVICE_STATUS = PREFIX + "device:status:";

    // ──────────── 指标最新状态缓存 ────────────
    /** 指标最新状态：iot:indicator:latest:{indicatorId} → JSON */
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

    // ──────────── Redisson 分布式锁键（仅在对应能力启用时使用） ────────────
    /** COP 定时任务锁：iot:lock:cop-engine */
    public static final String LOCK_COP_ENGINE = PREFIX + "lock:cop-engine";
    /** 设备状态同步锁：iot:lock:device-status:{deviceId} */
    public static final String LOCK_DEVICE_STATUS = PREFIX + "lock:device-status:";
}
