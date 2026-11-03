package com.platform.carbon;

import com.platform.audit.AuditGovernanceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.RedisConnectionFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DailyTrendSummaryCacheTest {
    @Test @SuppressWarnings("unchecked") void resultCacheFailureDoesNotChangeFactOrAuthenticationStores() {
        var redis=mock(StringRedisTemplate.class);var values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("isolated failure"));
        doThrow(new RedisConnectionFailureException("isolated failure")).when(values).set(anyString(),anyString(),any(java.time.Duration.class));
        var cache=new DailyTrendSummaryCache(redis,new DailyTrendSummaryProperties(),new AuditGovernanceProperties());
        assertThat(cache.get("B","2026-01","version")).isNull();
        assertThatCode(()->cache.put("B","2026-01","version","{}" )).doesNotThrowAnyException();
        verify(redis,never()).delete(anyString());
    }
}
