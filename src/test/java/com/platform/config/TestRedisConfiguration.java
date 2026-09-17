package com.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 普通 test profile 的进程内 Redis 替身。
 *
 * <p>每个 Spring 测试上下文持有独立键空间，避免本机服务或并行 Maven 进程覆盖固定的
 * Token、菜单和权限缓存键。替身保留字符串读写、覆盖、删除、存在性和 TTL 语义，使认证
 * 测试仍会执行单账号单 Token 与黑名单校验，而不是绕过缓存。</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("test")
public class TestRedisConfiguration {

    @Bean
    @Primary
    StringRedisTemplate testStringRedisTemplate() {
        Map<String, Entry> entries = new ConcurrentHashMap<>();
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);

        when(template.opsForValue()).thenReturn(values);
        when(values.get(any())).thenAnswer(invocation ->
                get(entries, invocation.getArgument(0, String.class)));
        doAnswer(invocation -> {
            put(entries,
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        doAnswer(invocation -> {
            long timeout = invocation.getArgument(2);
            TimeUnit unit = invocation.getArgument(3);
            put(entries,
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    Duration.ofNanos(unit.toNanos(timeout)));
            return null;
        }).when(values).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(template.hasKey(anyString())).thenAnswer(invocation ->
                get(entries, invocation.getArgument(0)) != null);
        when(template.delete(anyString())).thenAnswer(invocation ->
                entries.remove(invocation.getArgument(0)) != null);
        return template;
    }

    private static void put(
            Map<String, Entry> entries,
            String key,
            String value,
            Duration ttl) {
        entries.put(key, new Entry(value, System.nanoTime() + ttl.toNanos()));
    }

    private static String get(Map<String, Entry> entries, String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (System.nanoTime() - entry.expiresAtNanos() >= 0) {
            entries.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    private record Entry(String value, long expiresAtNanos) {}
}
