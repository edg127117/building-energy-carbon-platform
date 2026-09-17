package com.platform.config;

import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class TestRedisIsolationTest {

    @Autowired private TokenCacheService tokenCacheService;
    @Autowired private StringRedisTemplate redis;
    @Autowired @Qualifier("testStringRedisTemplate")
    private StringRedisTemplate testRedis;

    @Test
    void preservesSingleAccountAndBlacklistSemanticsWithoutExternalRedis() {
        long userId = 9_000_000_001L;
        String firstToken = "test-first-token";
        String secondToken = "test-second-token";

        tokenCacheService.addToWhitelist(userId, firstToken);
        assertThat(tokenCacheService.validateActiveToken(userId, firstToken))
                .isEqualTo(TokenValidationResult.ACTIVE);

        tokenCacheService.addToWhitelist(userId, secondToken);
        assertThat(tokenCacheService.validateActiveToken(userId, firstToken))
                .isEqualTo(TokenValidationResult.REJECTED);
        assertThat(tokenCacheService.validateActiveToken(userId, secondToken))
                .isEqualTo(TokenValidationResult.ACTIVE);

        tokenCacheService.addToBlacklist(secondToken, 60);
        assertThat(tokenCacheService.validateActiveToken(userId, secondToken))
                .isEqualTo(TokenValidationResult.REJECTED);
    }

    @Test
    void selectsIsolatedTemplateAndExpiresEntries() {
        assertThat(redis).isSameAs(testRedis);

        redis.opsForValue().set("test:expired", "value", Duration.ZERO);
        assertThat(redis.opsForValue().get("test:expired")).isNull();
        assertThat(redis.hasKey("test:expired")).isFalse();
    }
}
