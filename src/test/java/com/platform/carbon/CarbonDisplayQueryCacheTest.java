package com.platform.carbon;

import com.platform.audit.AuditGovernanceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.RedisConnectionFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarbonDisplayQueryCacheTest {
    @Test @SuppressWarnings("unchecked") void resultCacheFailureCannotDeleteAuthenticationOrFactData(){
        var redis=mock(StringRedisTemplate.class);var values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("isolated"));
        doThrow(new RedisConnectionFailureException("isolated")).when(values).set(anyString(),anyString(),any(java.time.Duration.class));
        var cache=new CarbonDisplayQueryCache(redis,new AuditGovernanceProperties(),new CarbonDisplayQueryProperties());
        assertThat(cache.get("query","version")).isNull();assertThatCode(()->cache.put("query","version","{}")).doesNotThrowAnyException();
        verify(redis,never()).delete(anyString());
    }
}
