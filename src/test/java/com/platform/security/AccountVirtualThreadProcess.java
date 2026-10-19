package com.platform.security;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.platform.cache.MenuCacheService;
import com.platform.cache.TokenCacheService;
import com.platform.framework.exception.BusinessException;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.mapper.SysUserRoleMapper;
import com.platform.system.service.SysRoleService;
import com.platform.system.service.impl.SysUserServiceImpl;
import com.zaxxer.hikari.HikariDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 专用隔离子 JVM：固定四个载体线程，以真实 TLS、连接池和账号锁检出 JDBC 固定导致的饥饿。 */
public final class AccountVirtualThreadProcess {
    public static void main(String[] args) throws Exception {
        assertThat(System.getenv("S01_AUTH_IT_ENABLED")).isEqualTo("true");
        assertThat(System.getenv("S01_AUTH_IT_ISOLATED")).isEqualTo("true");
        assertThat(System.getProperty("jdk.virtualThreadScheduler.parallelism")).isEqualTo("4");
        Path directory = Path.of(args[0]);
        String nonce = args[1];
        assertThat(Files.readString(directory.resolve("owner"))).isEqualTo(nonce);
        String url = System.getenv("S01_MYSQL_URL");
        assertThat(url).startsWith("jdbc:mysql://127.0.0.1:").contains("/s01_auth_it?");
        try (var pool = new HikariDataSource()) {
            pool.setJdbcUrl(url);
            pool.setUsername("root");
            pool.setPassword(System.getenv("S01_MYSQL_PASSWORD"));
            pool.setMaximumPoolSize(30);
            pool.setMinimumIdle(5);
            pool.setConnectionTimeout(5000);
            pool.addDataSourceProperty("sslMode", "REQUIRED");
            pool.addDataSourceProperty("connectionTimeZone", "Asia/Shanghai");
            pool.addDataSourceProperty("sessionVariables", "time_zone='+00:00'");
            pool.addDataSourceProperty("forceConnectionTimeZoneToSession", "false");
            var jdbc = new JdbcTemplate(pool);
            assertThat(jdbc.queryForObject("SELECT @@hostname", String.class)).isEqualTo("s01-auth-mysql");
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("s01_auth_it");
            assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
            assertThat(jdbc.queryForMap("SHOW STATUS LIKE 'Ssl_cipher'").get("Value").toString()).isNotBlank();
            var redisConfig = new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(System.getenv("S01_REDIS_PORT")));
            redisConfig.setDatabase(1);
            var factory = new LettuceConnectionFactory(redisConfig,
                    LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
            factory.afterPropertiesSet();
            try {
                try (var connection = factory.getConnection()) {
                    assertThat(System.getenv("S01_REDIS_RUN_ID")).isNotBlank();
                    assertThat(connection.serverCommands().info("server").getProperty("run_id"))
                            .isEqualTo(System.getenv("S01_REDIS_RUN_ID"));
                }
                var redis = new StringRedisTemplate(factory);
                var first = new AtomicBoolean(true);
                var tokens = new TokenCacheService(redis, 3600) {
                    @Override public void addToWhitelist(Long userId, String token) {
                        if (first.compareAndSet(true, false)) {
                            AccountCleanupProcess.mark(directory, "owner-holds-lock", "held");
                            awaitRelease(directory);
                        }
                        super.addToWhitelist(userId, token);
                    }
                };
                var configuration = new MybatisConfiguration();
                configuration.setMapUnderscoreToCamelCase(true);
                configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
                configuration.addMapper(SysUserMapper.class);
                configuration.addMapper(SysRoleMapper.class);
                configuration.addMapper(SysUserRoleMapper.class);
                var mapperFactory = new MybatisSqlSessionFactoryBean();
                mapperFactory.setDataSource(pool);
                mapperFactory.setConfiguration(configuration);
                var sql = new SqlSessionTemplate(mapperFactory.getObject());
                var users = sql.getMapper(SysUserMapper.class);
                var roles = sql.getMapper(SysRoleMapper.class);
                var tm = new DataSourceTransactionManager(pool);
                var jwt = new JwtService();
                ReflectionTestUtils.setField(jwt, "secret", "test-only-jwt-secret-with-more-than-thirty-two-bytes");
                ReflectionTestUtils.setField(jwt, "expireSeconds", 3600L);
                var cleanup = new AccountCleanupService(jdbc, users, tokens, jwt, redis, tm);
                var sessions = new AccountSessionService(users, roles, jwt, tokens, tm, cleanup);
                var login = new SysUserServiceImpl(new BCryptPasswordEncoder(4), jwt, tokens, mock(SysRoleService.class),
                        roles, sql.getMapper(SysUserRoleMapper.class), mock(MenuCacheService.class),
                        mock(com.platform.system.service.BuildingScopeService.class), sessions);
                ReflectionTestUtils.setField(login, "baseMapper", users);
                var authority = new AuthenticationStateService(tokens, users, tm);
                long before = users.selectSecurityState(7L).getSecurityVersion();
                AccountCleanupProcess.mark(directory, "ready", ProcessHandle.current().pid() + ":" + nonce);
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var futures = new ArrayList<Future<String>>();
                    futures.add(executor.submit(() -> {
                        assertThat(Thread.currentThread().isVirtual()).isTrue();
                        return login.login("s01", "pass").getToken();
                    }));
                    awaitMarker(directory, "owner-holds-lock");
                    var ready = new CountDownLatch(16);
                    var release = new CountDownLatch(1);
                    for (int i = 0; i < 16; i++) futures.add(executor.submit(() -> {
                        assertThat(Thread.currentThread().isVirtual()).isTrue();
                        ready.countDown();
                        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                        return login.login("s01", "pass").getToken();
                    }));
                    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                    release.countDown();
                    var issued = new ArrayList<String>();
                    for (var future : futures) issued.add(future.get(20, TimeUnit.SECONDS));
                    assertThat(issued).hasSize(17).doesNotHaveDuplicates();
                    assertThat(users.selectSecurityState(7L).getSecurityVersion()).isEqualTo(before + 17);
                    var ordered = issued.stream().sorted(Comparator.comparingLong(token -> jwt.getSecurityVersion(jwt.parseToken(token).getPayload()))).toList();
                    String latest = ordered.getLast();
                    assertThat(tokens.getFromWhitelist(7L)).isEqualTo(latest);
                    authority.requireActive(7L, latest, before + 17);
                    for (String old : ordered.subList(0, 16)) {
                        tokens.addToWhitelist(7L, old);
                        assertThatThrownBy(() -> authority.requireActive(7L, old, jwt.getSecurityVersion(jwt.parseToken(old).getPayload())))
                                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getCode()).isEqualTo(401));
                    }
                    tokens.addToWhitelist(7L, latest);
                    authority.requireActive(7L, latest, before + 17);
                    AccountCleanupProcess.mark(directory, "verified", "logins=17;versions=17;latest=active;stale=rejected;tls=true");
                }
            } finally { factory.destroy(); }
        }
    }

    private static void awaitRelease(Path directory) { awaitMarker(directory, "release"); }

    private static void awaitMarker(Path directory, String name) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(directory.resolve(name))) {
            assertThat(System.nanoTime()).as("waiting for %s", name).isLessThan(deadline);
            try { Thread.sleep(20); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
        }
    }
}
