package com.platform.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置层
 * - StringRedisTemplate：Key/Value 均为 String，用于设备状态/COP 值/Token/菜单等纯文本缓存
 * - RedissonClient：仅预留，通过 redisson.enabled=true 开启（多实例部署时启用分布式锁）
 *
 * 冻结书 D-010：Redis 不进入数据写入热路径，数据写入直连 TDengine
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    // ──────────── StringRedisTemplate（主力缓存 Bean）────────────

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);
        // Key 使用 String 序列化，便于人工排查
        template.setKeySerializer(StringRedisSerializer.UTF_8);
        // Value 也是 String，JSON 由业务层手动序列化，保持数据透明
        template.setValueSerializer(StringRedisSerializer.UTF_8);
        template.setHashKeySerializer(StringRedisSerializer.UTF_8);
        template.setHashValueSerializer(StringRedisSerializer.UTF_8);
        // 如果将来需要存对象，可改为以下配置：
        // template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        log.info("✅ StringRedisTemplate 已就绪");
        return template;
    }

    // ──────────── RedissonClient（分布式锁预留，默认关闭）────────────

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${spring.data.redis.database:0}")
    private int redisDatabase;

    /**
     * RedissonClient Bean
     * 仅当 redisson.enabled=true 时创建（多实例部署时启用以保护定时任务）
     * 本期默认 false，不影响单实例正常运行
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "redisson.enabled", havingValue = "true")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.setCodec(new JsonJacksonCodec()); // JSON 序列化，与 StringRedisTemplate 的 String 序列化不冲突
        String address = "redis://" + redisHost + ":" + redisPort;
        config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisDatabase)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(8);
        if (redisPassword != null && !redisPassword.isBlank()) {
            config.useSingleServer().setPassword(redisPassword);
        }
        log.info("✅ RedissonClient 已启用（分布式锁能力已激活）");
        return Redisson.create(config);
    }
}
