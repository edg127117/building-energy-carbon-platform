package com.platform.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import com.platform.framework.exception.BusinessException;
import com.platform.cache.CacheConstants;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.assertj.core.api.Assertions.assertThat;

/** 专用 CI 临时服务：显式启用且核对实例身份、空库后才创建或清理夹具，不允许指向部署数据库。 */
@EnabledIfEnvironmentVariable(named = "S01_AUTH_IT_ENABLED", matches = "true")
class AccountSecurityMysqlRedisIntegrationTest extends AccountSecurityContract {
    private LettuceConnectionFactory redisFactory;
    private boolean redisVerified;

    @Test
    void realRedisTimeoutFailsClosedAndRecoveryCannotRestoreDisabledAccount() {
        String token = loginToken();
        admin.updateStatus(-1L, 7L, 0);
        redis.delete(CacheConstants.TOKEN_BLACKLIST + token);
        tokens.addToWhitelist(7L, token);
        // 只暂停已验证的专用实例，制造超过客户端 2 秒期限的真实 Redis 命令超时。
        redis.execute((RedisCallback<Object>) connection -> connection.execute("CLIENT",
                "PAUSE".getBytes(StandardCharsets.US_ASCII), "3000".getBytes(StandardCharsets.US_ASCII)));
        assertThatThrownBy(() -> active(token)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(503));
        assertThat(redis.execute((RedisCallback<String>) connection -> connection.ping())).isEqualTo("PONG");
        rejected(token);
    }

    @Override
    protected DataSource dataSource() {
        assertThat(System.getenv("S01_AUTH_IT_ISOLATED")).isEqualTo("true");
        String url = System.getenv("S01_MYSQL_URL");
        assertThat(url).startsWith("jdbc:mysql://127.0.0.1:").contains("/s01_auth_it?");
        var ds = new DriverManagerDataSource(url, "root", System.getenv("S01_MYSQL_PASSWORD"));
        var check = new JdbcTemplate(ds);
        assertThat(check.queryForObject("SELECT VERSION()", String.class)).startsWith("8.");
        assertThat(check.queryForObject("SELECT @@hostname", String.class)).isEqualTo("s01-auth-mysql");
        assertThat(check.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("s01_auth_it");
        assertThat(check.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class)).isZero();
        return ds;
    }

    @Override
    protected StringRedisTemplate redisTemplate() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration("127.0.0.1",
                Integer.parseInt(System.getenv("S01_REDIS_PORT")));
        configuration.setDatabase(1);
        redisFactory = new LettuceConnectionFactory(configuration,
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        redisFactory.afterPropertiesSet();
        try (var connection = redisFactory.getConnection()) {
            String expected = System.getenv("S01_REDIS_RUN_ID");
            assertThat(expected).isNotBlank();
            assertThat(connection.serverCommands().info("server").getProperty("run_id")).isEqualTo(expected);
            assertThat(connection.serverCommands().dbSize()).isZero();
            redisVerified = true;
        }
        return new StringRedisTemplate(redisFactory);
    }

    @AfterEach
    void releaseRedis() {
        if (redisFactory == null) return;
        try {
            if (redisVerified) try (var connection = redisFactory.getConnection()) {
                assertThat(connection.serverCommands().info("server").getProperty("run_id"))
                        .isEqualTo(System.getenv("S01_REDIS_RUN_ID"));
                connection.serverCommands().flushDb();
            }
        } finally { redisFactory.destroy(); }
    }
}
