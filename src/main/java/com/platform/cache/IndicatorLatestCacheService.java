package com.platform.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.formula.model.IndicatorLatestState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 在 Redis 中保存每个指标的最新计算状态，供最新值 API 快速读取。
 *
 * <p>缓存不是历史真相来源：读取、反序列化或写入失败时统一降级为未命中，
 * 调用方应回查 TDengine。缓存同时保存成功和失败状态，使缺失输入不会继续
 * 向前端展示上一分钟的旧成功值。</p>
 */
@Service
public class IndicatorLatestCacheService {

    private static final Logger log =
            LoggerFactory.getLogger(IndicatorLatestCacheService.class);
    private static final Duration TTL = Duration.ofSeconds(120);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public IndicatorLatestCacheService(
            StringRedisTemplate redis,
            ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取指标最新状态。
     *
     * @return Redis 不可用、内容损坏或键不存在时返回空，由查询服务回退 TDengine
     */
    public Optional<IndicatorLatestState> get(String indicatorId) {
        try {
            String payload = redis.opsForValue().get(key(indicatorId));
            if (payload == null) {
                return Optional.empty();
            }
            return Optional.of(
                    objectMapper.readValue(payload, IndicatorLatestState.class));
        } catch (DataAccessException | JsonProcessingException e) {
            log.warn("Unable to read latest indicator cache state: indicatorId={}",
                    indicatorId, e);
            return Optional.empty();
        }
    }

    /**
     * 仅当状态分钟不早于缓存时更新，并返回是否应继续推送 WebSocket。
     *
     * <p>同一分钟已经成功时拒绝后到的失败状态，避免补算重试把正确值覆盖。
     * 当前同步只保证单实例比较和写入原子顺序；多实例部署必须改用 Redis
     * Lua/CAS 或分布式锁。</p>
     */
    public synchronized boolean setIfNotOlder(IndicatorLatestState state) {
        String key = key(state.indicatorId());
        try {
            String payload = redis.opsForValue().get(key);
            if (payload != null) {
                IndicatorLatestState current =
                        objectMapper.readValue(payload, IndicatorLatestState.class);
                if (current.minuteStart() > state.minuteStart()) {
                    return false;
                }
                if (current.minuteStart() == state.minuteStart()
                        && current.status() == FormulaCalculation.Status.SUCCESS
                        && state.status() != FormulaCalculation.Status.SUCCESS) {
                    return false;
                }
            }

            // 单实例内串行化比较和写入；多实例必须改用 Redis Lua/CAS 或 Redisson 锁。
            redis.opsForValue().set(
                    key, objectMapper.writeValueAsString(state), TTL);
            return true;
        } catch (DataAccessException | JsonProcessingException e) {
            log.warn("Unable to update latest indicator cache state: indicatorId={}",
                    state.indicatorId(), e);
            return false;
        }
    }

    private static String key(String indicatorId) {
        return CacheConstants.INDICATOR_LATEST + indicatorId;
    }
}
