package com.platform.carbon;

import com.platform.audit.AuditGovernanceProperties;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.ThreadLocalRandom;

/** Redis只缓存可重建的精简结果；故障回读MySQL，不修改登录及其它模块的缓存策略。 */
@Component
public class DailyTrendSummaryCache {
    private final StringRedisTemplate redis;
    private final DailyTrendSummaryProperties properties;
    private final AuditGovernanceProperties audit;
    public DailyTrendSummaryCache(StringRedisTemplate redis,DailyTrendSummaryProperties properties,AuditGovernanceProperties audit){this.redis=redis;this.properties=properties;this.audit=audit;}
    public String get(String building,String month,String version) {
        try{return redis.opsForValue().get(key(building,month,version));}
        catch(org.springframework.dao.DataAccessException e){return null;}
    }
    public void put(String building,String month,String version,String value) {
        try {redis.opsForValue().set(key(building,month,version),value,properties.getCacheTtl().plusSeconds(ThreadLocalRandom.current().nextInt(30)));}
        catch(org.springframework.dao.DataAccessException e){LoggerFactory.getLogger(getClass()).debug("Daily trend result cache unavailable");}
    }
    private String key(String building,String month,String version){return properties.getNamespace()+":"+audit.getEnvironmentMode()+":"+building+":"+month+":"+version;}
}
