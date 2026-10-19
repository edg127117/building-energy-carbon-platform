package com.platform.security;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.platform.cache.TokenCacheService;
import com.platform.system.mapper.SysUserMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 仅供隔离 CI 启动的子 JVM；不建库、不清库，真实 Spring 调度生产补偿服务。 */
public final class AccountCleanupProcess {
    @Configuration
    @EnableScheduling
    static class Scheduling {}

    public static void main(String[] args) throws Exception {
        assertThat(System.getenv("S01_AUTH_IT_ENABLED")).isEqualTo("true");
        assertThat(System.getenv("S01_AUTH_IT_ISOLATED")).isEqualTo("true");
        Path directory = Path.of(args[0]);
        String nonce = args[1];
        String mode = args[2];
        assertThat(Files.readString(directory.resolve("owner"))).isEqualTo(nonce);
        String url = System.getenv("S01_MYSQL_URL");
        assertThat(url).startsWith("jdbc:mysql://127.0.0.1:").contains("/s01_auth_it?");
        var ds = new DriverManagerDataSource(url, "root", System.getenv("S01_MYSQL_PASSWORD"));
        var properties = new Properties();
        properties.setProperty("sessionVariables", "time_zone='+00:00'");
        properties.setProperty("connectionTimeZone", "Asia/Shanghai");
        properties.setProperty("forceConnectionTimeZoneToSession", "false");
        ds.setConnectionProperties(properties);
        var jdbc = new ObservedJdbc(ds, directory, mode);
        assertThat(jdbc.queryForObject("SELECT @@hostname", String.class)).isEqualTo("s01-auth-mysql");
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("s01_auth_it");
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
        assertThat(java.time.ZoneId.systemDefault().getId()).isEqualTo("Asia/Shanghai");
        var redisConfig = new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(System.getenv("S01_REDIS_PORT")));
        redisConfig.setDatabase(1);
        var factory = new LettuceConnectionFactory(redisConfig,
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        factory.afterPropertiesSet();
        try (var connection = factory.getConnection()) {
            assertThat(System.getenv("S01_REDIS_RUN_ID")).isNotBlank();
            assertThat(connection.serverCommands().info("server").getProperty("run_id"))
                    .isEqualTo(System.getenv("S01_REDIS_RUN_ID"));
        }
        var redis = new StringRedisTemplate(factory);
        var tokens = new TokenCacheService(redis, 3600) {
            @Override public void removeFromWhitelistIfMatches(Long id, String expected) {
                super.removeFromWhitelistIfMatches(id, expected);
                if (mode.equals("crash")) {
                    // Redis 已生效而补偿事务未提交：父进程在此核对身份后强杀。
                    mark(directory, "partial", Long.toString(id));
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
            }
        };
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(SysUserMapper.class);
        var mapperFactory = new MybatisSqlSessionFactoryBean();
        mapperFactory.setDataSource(ds);
        mapperFactory.setConfiguration(config);
        var users = new SqlSessionTemplate(mapperFactory.getObject()).getMapper(SysUserMapper.class);
        var tm = new DataSourceTransactionManager(ds);
        var jwt = new JwtService();
        ReflectionTestUtils.setField(jwt, "secret", "test-only-jwt-secret-with-more-than-thirty-two-bytes");
        ReflectionTestUtils.setField(jwt, "expireSeconds", 3600L);
        var cleanup = new AccountCleanupService(jdbc, users, tokens, jwt, redis, tm);
        // 仅注册已构造的生产服务，不扫描应用，不运行 Flyway 或初始化夹具。
        var context = new AnnotationConfigApplicationContext();
        context.register(Scheduling.class);
        context.registerBean("cleanup", AccountCleanupService.class, () -> cleanup);
        context.refresh();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { context.close(); factory.destroy(); }));
        mark(directory, "ready", ProcessHandle.current().pid() + ":" + nonce);
        if (mode.equals("crash")) {
            new TransactionTemplate(tm).executeWithoutResult(status -> {
                var user = users.lockById(7L);
                cleanup.enqueue(7L, AccountSessionService.incrementVersion(users, user));
            });
            throw new AssertionError("Crash barrier was not reached");
        }
        new CountDownLatch(1).await();
    }

    static void mark(Path directory, String name, String value) {
        try {
            Path temporary = directory.resolve(name + ".tmp");
            Files.writeString(temporary, value);
            Files.move(temporary, directory.resolve(name), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** 只观察 SQL/提交并设置竞争屏障，不替换生产锁、删除或调度实现。 */
    static final class ObservedJdbc extends JdbcTemplate {
        private final Path directory;
        private final String mode;
        private boolean firstScan = true;

        ObservedJdbc(DriverManagerDataSource ds, Path directory, String mode) {
            super(ds);
            this.directory = directory;
            this.mode = mode;
        }

        @Override public <T> List<T> queryForList(String sql, Class<T> type) {
            List<T> result = super.queryForList(sql, type);
            if (sql.contains("next_attempt_at<=CURRENT_TIMESTAMP") && firstScan) {
                firstScan = false;
                mark(directory, "scan", result.toString());
                if (mode.equals("race")) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                    while (!Files.exists(directory.resolve("release"))) {
                        if (System.nanoTime() > deadline) throw new IllegalStateException("Scan barrier timed out");
                        try { Thread.sleep(25); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    }
                }
            }
            return result;
        }

        @Override public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
            List<T> result = super.queryForList(sql, type, args);
            if (sql.contains("sys_auth_cleanup") && sql.contains("FOR UPDATE")) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status == STATUS_COMMITTED) mark(directory, "visited-" + args[0], "committed");
                    }
                });
            }
            return result;
        }

        @Override public int update(String sql, Object... args) {
            int affected = super.update(sql, args);
            if (sql.startsWith("DELETE FROM sys_auth_cleanup")) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { mark(directory, "deleted-" + args[0], Integer.toString(affected)); }
                });
            }
            return affected;
        }
    }
}
