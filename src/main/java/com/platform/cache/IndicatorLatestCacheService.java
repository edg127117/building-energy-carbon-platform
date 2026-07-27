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

    public boolean setIfNotOlder(IndicatorLatestState state) {
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

            // This compare/set pair is safe only for a single application instance.
            // Multi-instance deployments must use a Lua script or Redisson lock.
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
