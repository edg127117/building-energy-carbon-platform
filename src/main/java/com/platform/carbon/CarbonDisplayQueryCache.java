package com.platform.carbon;

import com.platform.audit.AuditGovernanceProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;
import java.util.concurrent.ThreadLocalRandom;

/** 业务结果缓存独立命名空间；仅此缓存失败可回读MySQL，鉴权和会话仍遵守原有失败边界。 */
@Component
class CarbonDisplayQueryCache {
    private final StringRedisTemplate redis;
    private final AuditGovernanceProperties audit;
    private final CarbonDisplayQueryProperties properties;
    CarbonDisplayQueryCache(StringRedisTemplate redis,AuditGovernanceProperties audit,CarbonDisplayQueryProperties properties){this.redis=redis;this.audit=audit;this.properties=properties;}
    String get(String query,String version){try{return redis.opsForValue().get(key(query,version));}catch(DataAccessException e){return null;}}
    void put(String query,String version,String payload){try{redis.opsForValue().set(key(query,version),payload,properties.getCacheTtl().plusSeconds(ThreadLocalRandom.current().nextInt(30)));}catch(DataAccessException e){/* 可重建结果已持久化；不把Redis故障升级成核算失败。 */}}
    private String key(String query,String version){return "bec:carbon-display:v1:"+audit.getEnvironmentMode()+":"+query+":"+version;}
}
