package com.platform.cache;

import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TokenCacheFailureTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final TokenCacheService service = new TokenCacheService(redis, 3600);
    private final DataAccessResourceFailureException failure =
            new DataAccessResourceFailureException("isolated redis failure");

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void distinguishesDependencyFailureFromAnInactiveToken() {
        when(redis.hasKey(CacheConstants.TOKEN_BLACKLIST + "token")).thenThrow(failure);
        assertThat(service.validateActiveToken(7L, "token"))
                .isEqualTo(TokenValidationResult.CACHE_UNAVAILABLE);
        doReturn(false).when(redis).hasKey(CacheConstants.TOKEN_BLACKLIST + "token");
        assertThat(service.validateActiveToken(7L, "token")).isEqualTo(TokenValidationResult.REJECTED);
    }

    @Test
    void doesNotReportSuccessfulLoginWhenWhitelistWriteFails() {
        doThrow(failure).when(values).set(CacheConstants.TOKEN_WHITELIST + 7L,
                "token", Duration.ofSeconds(3600));
        assertUnavailable(() -> service.addToWhitelist(7L, "token"));
    }

    @Test
    void doesNotReportSuccessfulLogoutWhenEitherWriteFails() {
        doThrow(failure).when(values).set(eq(CacheConstants.TOKEN_BLACKLIST + "token"),
                anyString(), eq(60L), eq(TimeUnit.SECONDS));
        assertUnavailable(() -> service.addToBlacklist("token", 60));
        when(redis.delete(CacheConstants.TOKEN_WHITELIST + 7L)).thenThrow(failure);
        assertUnavailable(() -> service.removeFromWhitelist(7L));
    }

    @Test
    void propagatesRevocationFailureAndKeepsSuccessfulRevocationAfterRecovery() {
        when(values.get(CacheConstants.TOKEN_WHITELIST + 7L)).thenThrow(failure);
        assertUnavailable(() -> service.revokeActiveToken(7L));
        doReturn("token").when(values).get(CacheConstants.TOKEN_WHITELIST + 7L);
        service.revokeActiveToken(7L);
        verify(redis).delete(CacheConstants.TOKEN_WHITELIST + 7L);
        when(redis.hasKey(CacheConstants.TOKEN_BLACKLIST + "token")).thenReturn(true);
        assertThat(service.validateActiveToken(7L, "token")).isEqualTo(TokenValidationResult.REJECTED);
    }

    private void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(503));
    }
}
