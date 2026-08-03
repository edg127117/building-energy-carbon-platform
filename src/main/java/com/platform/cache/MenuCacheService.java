package com.platform.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 动态菜单 JSON 的 Redis 读写适配器。
 *
 * <p>{@code SysMenuServiceImpl} 的主流程按 userId 缓存已经合并角色、补齐父级并排好序的菜单树，
 * TTL 为 30 分钟。类中同时保留全量菜单树键的显式读写接口，但用户菜单生成不会从该键裁剪，
 * 仍以 MySQL 用户角色和角色菜单关系为事实来源。</p>
 *
 * <p>缓存未命中和 Redis 故障都向调用方返回 {@code null}，由菜单服务回源 MySQL；角色、菜单
 * 或用户角色变化时主动删除受影响键。缓存只影响导航生成性能，不是接口权限边界。</p>
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

    /** 写入全量菜单树 JSON；该键与用户个性化菜单键相互独立。 */
    public void setFullMenuTree(String menuTreeJson) {
        try {
            redis.opsForValue().set(CacheConstants.MENU_ALL, menuTreeJson, MENU_TTL);
            log.debug("全量菜单树已写入 Redis 缓存 (TTL={})", MENU_TTL);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，全量菜单树缓存写入失败", e);
        }
    }

    /** @return 全量树 JSON；未命中或 Redis 不可用时返回 {@code null} */
    public String getFullMenuTree() {
        try {
            return redis.opsForValue().get(CacheConstants.MENU_ALL);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，全量菜单树缓存读取失败", e);
            return null;
        }
    }

    // ──────────── 用户菜单树 ────────────

    /** 缓存已经按用户角色生成的完整菜单树，不在缓存层重新裁剪权限。 */
    public void setUserMenu(Long userId, String menuJson) {
        try {
            redis.opsForValue().set(CacheConstants.MENU_USER + userId, menuJson, MENU_TTL);
            log.debug("用户菜单已写入 Redis: userId={}", userId);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，用户菜单缓存写入失败: userId={}", userId, e);
        }
    }

    /** @return 用户菜单 JSON；未命中或 Redis 不可用时返回 {@code null}，调用方应回源 MySQL */
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
     * 清理全量树和指定用户树；{@code userId=null} 时只清全量键。
     * 角色菜单批量变化由调用方枚举受影响用户，避免使用 Redis {@code KEYS *} 扫描。
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
