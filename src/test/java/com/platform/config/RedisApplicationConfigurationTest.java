package com.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class RedisApplicationConfigurationTest {

    @Test
    void bindsTestRedisEnvironmentUnderSpringDataRedis() throws IOException {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("REDIS_HOST", "127.0.0.1")
                .withProperty("REDIS_PORT", "16389")
                .withProperty("REDIS_PASSWORD", "test-password")
                .withProperty("REDIS_DB", "3");
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }

        RedisProperties redis = Binder.get(environment)
                .bind("spring.data.redis", RedisProperties.class)
                .orElseThrow(() -> new IllegalStateException("spring.data.redis is missing"));
        assertThat(redis.getHost()).isEqualTo("127.0.0.1");
        assertThat(redis.getPort()).isEqualTo(16389);
        assertThat(redis.getPassword()).isEqualTo("test-password");
        assertThat(redis.getDatabase()).isEqualTo(3);
        assertThat(environment.containsProperty("scheduling.data.redis.port")).isFalse();
        assertThat(environment.getProperty("scheduling.business-pool-size")).isEqualTo("4");
    }
}
