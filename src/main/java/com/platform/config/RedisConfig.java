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
 * Redis 字符串缓存与可选 Redisson 客户端的装配入口。
 *
 * <p>{@link StringRedisTemplate} 始终创建，承载 Token、菜单、建筑范围和指标最新状态等可重建
 * 数据；Redis 不是 MySQL/TDengine 的事实数据源。{@link RedissonClient} 仅在
 * {@code redisson.enabled=true} 时创建，供明确注入它的分布式协调逻辑使用；关闭时这些 Bean
 * 不存在，但普通字符串缓存不受影响。</p>
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
        log.info("✅ StringRedisTemplate 已就绪");
        return template;
    }

    // ──────────── RedissonClient（按配置启用，默认关闭）────────────

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${spring.data.redis.database:0}")
    private int redisDatabase;

    /**
     * 根据当前 Redis 单节点配置创建 RedissonClient。
     * 只有 {@code redisson.enabled=true} 时 Bean 才存在；密码为空时不发送 AUTH。关闭该开关会
     * 停用依赖 Redisson Bean 的能力，不会停用 StringRedisTemplate 缓存。
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
