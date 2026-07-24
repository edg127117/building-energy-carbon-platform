package com.platform.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * 用户建筑权限的 Redis 缓存适配器。
 *
 * <p>只缓存非平台管理员的建筑 ID 集合，缓存未命中或 Redis 故障都返回 {@code null}，
 * 由 {@code BuildingScopeService} 回源 MySQL。空集合会被正常缓存，表示用户当前没有任何建筑权限。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuildingScopeCacheService {
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * 读取用户建筑范围。
     *
     * @return 命中时返回建筑集合；未命中、缓存损坏或 Redis 不可用时返回 {@code null}
     */
    public Set<String> get(Long userId) {
        try {
            String json = redis.opsForValue().get(CacheConstants.BUILDING_SCOPE_USER + userId);
            return json == null ? null : objectMapper.readValue(json, new TypeReference<Set<String>>() {});
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，建筑范围回退 MySQL: userId={}", userId);
            return null;
        } catch (Exception e) {
            log.warn("建筑范围缓存格式异常: userId={}", userId);
            evict(userId);
            return null;
        }
    }

    /** 将用户建筑集合写入 Redis，默认缓存十分钟。 */
    public void set(Long userId, Set<String> ids) {
        try {
            redis.opsForValue().set(CacheConstants.BUILDING_SCOPE_USER + userId,
                    objectMapper.writeValueAsString(ids), Duration.ofMinutes(10));
        } catch (Exception e) {
            log.warn("建筑范围缓存写入失败: userId={}", userId);
        }
    }

    /**
     * 删除指定用户的建筑范围缓存。
     * 用户获得、撤销或替换建筑权限后必须调用，使下一次读取回源数据库。
     */
    public void evict(Long userId) {
        try { redis.delete(CacheConstants.BUILDING_SCOPE_USER + userId); }
        catch (DataAccessException e) { log.warn("建筑范围缓存清理失败: userId={}", userId); }
    }
}
