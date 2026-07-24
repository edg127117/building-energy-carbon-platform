package com.platform.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 动态菜单缓存服务
 * 冻结书 D-010 + 10.1.7：借鉴 Enjoy-IoT，菜单树存 MySQL → Redis 缓存 → 按需刷新
 *
 * 设计要点：
 * - 全量菜单树：存一份全局菜单 JSON，角色变更时刷新
 * - 用户菜单树：按 userId 缓存个性化菜单，首次登录时从全量树裁剪后写入
 * - TTL：设 30 分钟，角色变更时主动清除对应 Key 触发重建
 * - 降级：Redis 不可用时回退 MySQL 直查（由调用方处理）
 *
 * 当前由 {@code SysMenuServiceImpl} 接入：缓存未命中或 Redis 故障时回源 MySQL，
 * 角色、菜单或用户角色发生变化时主动清理对应用户缓存。
 */
@Service
public class MenuCacheService {

    private static final Logger log = LoggerFactory.getLogger(MenuCacheService.class);
    private static final Duration MENU_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;

    public MenuCacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ──────────── 全量菜单树 ────────────

    /**
     * 缓存全量菜单树 JSON
     */
    public void setFullMenuTree(String menuTreeJson) {
        try {
            redis.opsForValue().set(CacheConstants.MENU_ALL, menuTreeJson, MENU_TTL);
            log.debug("全量菜单树已写入 Redis 缓存 (TTL={})", MENU_TTL);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，全量菜单树缓存写入失败", e);
        }
    }

    /**
     * 获取全量菜单树 JSON
     * @return null=缓存未命中，调用方应回退 MySQL
     */
    public String getFullMenuTree() {
        try {
            return redis.opsForValue().get(CacheConstants.MENU_ALL);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，全量菜单树缓存读取失败", e);
            return null;
        }
    }

    // ──────────── 用户菜单树 ────────────

    /**
     * 缓存用户个性化菜单
     */
    public void setUserMenu(Long userId, String menuJson) {
        try {
            redis.opsForValue().set(CacheConstants.MENU_USER + userId, menuJson, MENU_TTL);
            log.debug("用户菜单已写入 Redis: userId={}", userId);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，用户菜单缓存写入失败: userId={}", userId, e);
        }
    }

    /**
     * 获取用户菜单 JSON
     * @return null=缓存未命中
     */
    public String getUserMenu(Long userId) {
        try {
            return redis.opsForValue().get(CacheConstants.MENU_USER + userId);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，用户菜单缓存读取失败: userId={}", userId, e);
            return null;
        }
    }

    // ──────────── 缓存失效 ────────────

    /**
     * 角色/菜单变更后，清除相关缓存
     * @param userId 受影响的用户ID，传 null 则仅清全量树
     */
    public void evict(Long userId) {
        try {
            redis.delete(CacheConstants.MENU_ALL);
            if (userId != null) {
                redis.delete(CacheConstants.MENU_USER + userId);
            }
            log.info("菜单缓存已失效: userId={}", userId);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，菜单缓存清理失败", e);
        }
    }
}
