package com.platform.security;

import com.platform.cache.TokenCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** 仅在核验 run_id 的一次性 CI Redis 中调整 ACL；生产模板权限清单直接用于真实客户端验证。 */
@EnabledIfEnvironmentVariable(named = "S01_AUTH_IT_ENABLED", matches = "true")
class RedisAccessIntegrationTest {
    @Test
    void authenticationAndRestrictedAclPreserveS01CasAndRejectDangerousCommands() throws Exception {
        assertThat(System.getenv("S01_AUTH_IT_ISOLATED")).isEqualTo("true");
        String password = UUID.randomUUID().toString();
        String adminPassword = UUID.randomUUID().toString();
        try (var initial = factory(null, null)) {
            try (var connection = initial.getConnection()) {
                assertThat(connection.serverCommands().info("server").getProperty("run_id"))
                        .isEqualTo(System.getenv("S01_REDIS_RUN_ID"));
                assertThat(connection.serverCommands().dbSize()).isZero();
            }
            var bootstrap = new StringRedisTemplate(initial);
            execute(bootstrap, "ACL", "SETUSER", "s02-admin", "reset", "on", ">" + adminPassword, "~*", "+@all");
            try (var admin = factory("s02-admin", adminPassword)) {
                var administrator = new StringRedisTemplate(admin);
                try {
                    var arguments = new ArrayList<>(List.of("SETUSER", "s02-app", "reset", "on", ">" + password));
                    arguments.addAll(List.of(Files.readString(Path.of("src/env/redis-app.permissions")).trim().split("\\s+")));
                    execute(administrator, "ACL", arguments.toArray(String[]::new));
                    execute(administrator, "ACL", "SETUSER", "default", "off");
                    try (var anonymous = factory(null, null); var wrong = factory("s02-app", "incorrect-test-password");
                         var application = factory("s02-app", password)) {
                        assertThatThrownBy(() -> new StringRedisTemplate(anonymous).opsForValue().get("iot:probe"))
                                .isInstanceOf(org.springframework.dao.DataAccessException.class);
                        assertThatThrownBy(() -> new StringRedisTemplate(wrong).opsForValue().get("iot:probe"))
                                .isInstanceOf(org.springframework.dao.DataAccessException.class);
                        var redis = new StringRedisTemplate(application);
                        redis.opsForValue().set("iot:s02:ttl-probe", "milliseconds", Duration.ofMillis(12500));
                        assertThat(redis.opsForValue().get("iot:s02:ttl-probe")).isEqualTo("milliseconds");
                        var tokens = new TokenCacheService(redis, 120);
                        tokens.addToWhitelist(7L, "old-test-token");
                        tokens.addToWhitelist(7L, "new-test-token");
                        tokens.removeFromWhitelistIfMatches(7L, "old-test-token");
                        assertThat(tokens.getFromWhitelist(7L)).isEqualTo("new-test-token");
                        tokens.removeFromWhitelistIfMatches(7L, "new-test-token");
                        assertThat(tokens.getFromWhitelist(7L)).isNull();
                        assertThatThrownBy(() -> redis.opsForValue().get("other-project:probe"))
                                .isInstanceOf(org.springframework.dao.DataAccessException.class).hasStackTraceContaining("NOPERM");
                        for (String command : List.of("FLUSHDB", "FLUSHALL", "KEYS", "ACL", "CONFIG")) {
                            String[] args = switch (command) {
                                case "KEYS" -> new String[]{"*"};
                                case "ACL" -> new String[]{"LIST"};
                                case "CONFIG" -> new String[]{"GET", "requirepass"};
                                default -> new String[0];
                            };
                            assertThatThrownBy(() -> execute(redis, command, args))
                                    .as(command).isInstanceOf(org.springframework.dao.DataAccessException.class).hasStackTraceContaining("NOPERM");
                        }
                    }
                } finally {
                    // 仅恢复本次创建的隔离实例 ACL；不对任何部署实例执行这些命令。
                    execute(administrator, "ACL", "SETUSER", "default", "on", "nopass");
                    administrator.delete(List.of("iot:token:whitelist:7", "iot:s02:ttl-probe"));
                    removeUsers(admin, "s02-app");
                    removeUsers(initial, "s02-admin");
                }
            }
        }
    }

    private static TestFactory factory(String username, String password) {
        var configuration = new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(System.getenv("S01_REDIS_PORT")));
        configuration.setDatabase(1);
        if (username != null) configuration.setUsername(username);
        if (password != null) configuration.setPassword(password);
        var factory = new TestFactory(configuration);
        factory.afterPropertiesSet();
        return factory;
    }

    private static class TestFactory extends LettuceConnectionFactory implements AutoCloseable {
        TestFactory(RedisStandaloneConfiguration configuration) {
            super(configuration, LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        }
        @Override public void close() { destroy(); }
    }

    private static void removeUsers(TestFactory factory, String user) {
        try (var connection = factory.getConnection()) {
            ((org.springframework.data.redis.connection.lettuce.LettuceConnection) connection).execute("ACL",
                    new io.lettuce.core.output.IntegerOutput<>(io.lettuce.core.codec.ByteArrayCodec.INSTANCE),
                    "DELUSER".getBytes(StandardCharsets.UTF_8), user.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Object execute(StringRedisTemplate redis, String command, String... arguments) {
        return redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> connection.execute(
                command, java.util.Arrays.stream(arguments).map(s -> s.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new)));
    }
}
