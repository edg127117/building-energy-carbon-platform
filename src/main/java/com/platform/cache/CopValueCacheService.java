package com.platform.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * COP 指标最新值缓存服务
 * 对应冻结书 D-010：COP 最新值缓存
 *
 * 设计要点：
 * - 写入：COP 算法引擎 @Scheduled 每分钟计算完成后，旁路写入 Redis
 * - 读取：WebSocket 推送 / 前端弹窗点击时优先读 Redis，无需每次查 TDengine
 * - TTL：设 120 秒（2 分钟），容忍一次 COP 引擎故障导致的短时过期
 * - 降级：Redis 不可用时，调用方回退 TDengine 查询 st_indicator_minute
 *
 * 本期 COP 引擎尚未实现，本服务作为预留接口，与冻结书 6.1 节对齐
 */
@Service
public class CopValueCacheService {

    private static final Logger log = LoggerFactory.getLogger(CopValueCacheService.class);
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(120); // 2 分钟 TTL

    private final StringRedisTemplate redis;

    public CopValueCacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ──────────── 写操作 ────────────

    /**
     * 写入指标最新值
     * @param indicatorCode 指标编码（WCR_COP / TOWER_EFF / PUMP_EFF / AHU_POW_EFF）
     * @param deviceId      设备ID
     * @param value         指标值
     * @param calcTime      计算时间戳（毫秒），前端可据此判断数据新鲜度
     */
    public void setLatest(String indicatorCode, String deviceId, double value, long calcTime) {
        String key = CacheConstants.INDICATOR_LATEST + indicatorCode + ":" + deviceId;
        // 格式：value|timestamp，前端自行解析
        String payload = value + "|" + calcTime;
        try {
            redis.opsForValue().set(key, payload, DEFAULT_TTL);
            log.debug("COP 缓存写入: {}@{} = {} (ts={})", indicatorCode, deviceId, value, calcTime);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，COP 缓存写入失败: {}@{}", indicatorCode, deviceId, e);
        }
    }

    // ──────────── 读操作 ────────────

    /**
     * 读取指标最新值
     * @param indicatorCode 指标编码
     * @param deviceId      设备ID
     * @return null=缓存未命中，非 null 格式为 "value|timestamp"
     */
    public String getLatest(String indicatorCode, String deviceId) {
        String key = CacheConstants.INDICATOR_LATEST + indicatorCode + ":" + deviceId;
        try {
            return redis.opsForValue().get(key);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，COP 缓存读取失败: {}@{}", indicatorCode, deviceId, e);
            return null;
        }
    }

    /**
     * 读取指标最新纯数值（不含时间戳）
     */
    public Double getLatestValue(String indicatorCode, String deviceId) {
        String raw = getLatest(indicatorCode, deviceId);
        if (raw == null) return null;
        try {
            String[] parts = raw.split("\\|");
            return Double.parseDouble(parts[0]);
        } catch (NumberFormatException e) {
            log.warn("COP 缓存数据格式异常: raw={}", raw, e);
            return null;
        }
    }

    // ──────────── 预留扩展 ────────────

    /**
     * 自定义 TTL 写入（用于不同指标设置不同过期时间）
     */
    public void setLatest(String indicatorCode, String deviceId, double value, long calcTime, Duration ttl) {
        String key = CacheConstants.INDICATOR_LATEST + indicatorCode + ":" + deviceId;
        String payload = value + "|" + calcTime;
        try {
            redis.opsForValue().set(key, payload, ttl);
        } catch (DataAccessException e) {
            log.warn("Redis 不可用，COP 缓存写入失败: {}@{}", indicatorCode, deviceId, e);
        }
    }
}
