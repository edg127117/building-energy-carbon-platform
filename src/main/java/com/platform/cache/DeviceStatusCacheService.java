package com.platform.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 设备在线状态缓存服务
 * 对应冻结书 D-010：Redis 用于设备状态缓存
 *
 * 设计要点：
 * - 写入：MQTT 消费感知到设备上下线时，旁路更新 Redis（不参与数据写入热路径）
 * - 读取：前端大屏监测点亮动 / 状态汇总 API 优先读 Redis，大幅降低 MySQL 查询压力
 * - 降级：Redis 不可用时，调用方应回退到 MySQL 直查（由调用方自行处理）
 * - TTL：不设过期，设备状态由 MQTT 事件驱动更新（设备下线不会自动清除缓存，需显式更新）
 */
@Service
public class DeviceStatusCacheService {

    private static final Logger log = LoggerFactory.getLogger(DeviceStatusCacheService.class);

    private final StringRedisTemplate redis;

    public DeviceStatusCacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ──────────── 写操作 ────────────

    /**
     * 更新设备状态缓存
     * @param deviceId 设备ID（如 meter-0001）
     * @param status   状态值（0=离线, 1=在线, 2=故障）
     */
    public void setStatus(String deviceId, int status) {
        String key = CacheConstants.DEVICE_STATUS + deviceId;
        try {
            redis.opsForValue().set(key, String.valueOf(status));
            log.debug("Redis 设备状态已更新: {} → {}", deviceId, status);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，设备状态缓存写入失败: deviceId={}, status={}", deviceId, status, e);
        }
    }

    // ──────────── 读操作 ────────────

    /**
     * 获取设备状态缓存
     * @param deviceId 设备ID
     * @return null=缓存未命中（调用方应回退 MySQL），否则返回状态值
     */
    public Integer getStatus(String deviceId) {
        String key = CacheConstants.DEVICE_STATUS + deviceId;
        try {
            String val = redis.opsForValue().get(key);
            if (val != null) {
                return Integer.parseInt(val);
            }
        } catch (DataAccessException | NumberFormatException e) {
            log.warn("Redis 不可用或数据异常，设备状态缓存读取失败: deviceId={}", deviceId, e);
        }
        return null;
    }

    // ──────────── 批量操作 ────────────

    /**
     * 清除指定设备的状态缓存（设备删除时调用）
     */
    public void removeStatus(String deviceId) {
        try {
            redis.delete(CacheConstants.DEVICE_STATUS + deviceId);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，设备状态缓存删除失败: deviceId={}", deviceId, e);
        }
    }

    /**
     * 设置带过期时间的缓存（用于将来需要 TTL 的场景）
     */
    public void setStatusWithExpire(String deviceId, int status, Duration expire) {
        String key = CacheConstants.DEVICE_STATUS + deviceId;
        try {
            redis.opsForValue().set(key, String.valueOf(status), expire);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，设备状态缓存写入失败: deviceId={}", deviceId, e);
        }
    }
}
