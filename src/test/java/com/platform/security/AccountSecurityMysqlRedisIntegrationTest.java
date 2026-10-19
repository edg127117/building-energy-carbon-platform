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
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class AccountSecurityMysqlRedisIntegrationTest extends AccountSecurityContract {
    private LettuceConnectionFactory redisFactory;
    private boolean redisVerified;

    @Test
    @org.junit.jupiter.api.Order(1)
    void killedJvmRecoversCommittedOutboxThroughRealScheduler() throws Exception {
        String old = loginToken();
        long revokedVersion = version(old) + 1;
        try (var crashing = new Child("crash")) {
            crashing.awaitFile("partial");
            assertThat(users.selectSecurityState(7L).getSecurityVersion()).isEqualTo(revokedVersion);
            assertThat(tokens.getFromWhitelist(7L)).isNull();
            assertThat(jdbc.queryForObject("SELECT revoked_through_version FROM sys_auth_cleanup WHERE user_id=7", Long.class))
                    .isEqualTo(revokedVersion);
            crashing.killVerified();
        }
        // 同一个数据库、同一条已提交待办；重启前不执行夹具初始化或到期时间更新。
        String deadline = jdbc.queryForObject("SELECT CAST(next_attempt_at AS CHAR) FROM sys_auth_cleanup WHERE user_id=7", String.class);
        redis.delete(CacheConstants.TOKEN_BLACKLIST + old);
        tokens.addToWhitelist(7L, old);
        rejected(old);
        String current = loginToken();
        try (var restarted = new Child("recover")) {
            assertThat(jdbc.queryForObject("SELECT CAST(next_attempt_at AS CHAR) FROM sys_auth_cleanup WHERE user_id=7", String.class))
                    .isEqualTo(deadline);
            restarted.awaitFile("visited-7");
            assertThat(restarted.read("scan")).isEqualTo("[7]");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_auth_cleanup", Integer.class)).isZero();
            assertThat(tokens.getFromWhitelist(7L)).isEqualTo(current);
            active(current);
        }
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void twoJvmSchedulersCompeteForSameBatchWithoutLosingNewTokens() throws Exception {
        String old = loginToken();
        org.mockito.Mockito.doNothing().when(cleanup).attempt(org.mockito.ArgumentMatchers.anyLong());
        admin.replaceRoles(-1L, 7L, java.util.List.of("ENERGY_MANAGER"));
        String current = loginToken();
        jdbc.update("INSERT INTO sys_user(id,username,password,status,del_flag,activation_pending,security_version) VALUES(8,'race-old','unused',1,0,0,10),(9,'race-new','unused',1,0,0,11)");
        String old8 = jwt.generateToken(8L, "race-old", java.util.List.of("ENERGY_MANAGER"), 9L);
        String new9 = jwt.generateToken(9L, "race-new", java.util.List.of("ENERGY_MANAGER"), 11L);
        tokens.addToWhitelist(8L, old8);
        tokens.addToWhitelist(9L, new9);
        new org.springframework.transaction.support.TransactionTemplate(tm).executeWithoutResult(status -> {
            users.lockById(8L); cleanup.enqueue(8L, 10L);
            users.lockById(9L); cleanup.enqueue(9L, 10L);
        });
        long version7 = users.selectSecurityState(7L).getSecurityVersion();
        try (var first = new Child("race"); var second = new Child("race")) {
            first.awaitFile("scan"); second.awaitFile("scan");
            assertThat(first.read("scan")).contains("7", "8", "9");
            assertThat(second.read("scan")).contains("7", "8", "9");
            first.release(); second.release();
            for (long id : new long[]{7, 8, 9}) {
                first.awaitFile("visited-" + id); second.awaitFile("visited-" + id);
                int deleted = first.deleted(id) + second.deleted(id);
                assertThat(deleted).as("committed deletions for user %s", id).isEqualTo(1);
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_auth_cleanup", Integer.class)).isZero();
            assertThat(tokens.getFromWhitelist(7L)).isEqualTo(current);
            assertThat(tokens.getFromWhitelist(8L)).isNull();
            assertThat(tokens.getFromWhitelist(9L)).isEqualTo(new9);
            assertThat(users.selectSecurityState(7L).getSecurityVersion()).isEqualTo(version7);
            assertThat(users.selectSecurityState(8L).getSecurityVersion()).isEqualTo(10L);
            assertThat(users.selectSecurityState(9L).getSecurityVersion()).isEqualTo(11L);
            active(current);
            authority.requireActive(9L, new9, 11L);
            redis.delete(CacheConstants.TOKEN_BLACKLIST + old);
            tokens.addToWhitelist(7L, old);
            rejected(old);
        }
    }

    /** 只持有本测试创建的 Process，身份包括握手 nonce、PID、启动时间和主类。 */
    private static final class Child implements AutoCloseable {
        private final java.nio.file.Path directory;
        private final String nonce = java.util.UUID.randomUUID().toString();
        private final Process process;
        private final java.time.Instant started;

        Child(String mode) throws Exception {
            var root = java.nio.file.Path.of("target", "s01-process-tests");
            java.nio.file.Files.createDirectories(root);
            directory = java.nio.file.Files.createTempDirectory(root, "child-").toAbsolutePath();
            java.nio.file.Files.writeString(directory.resolve("owner"), nonce);
            String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
            // 完整 Maven classpath 可能超过操作系统进程信息读取上限；主类和 nonce 留在短命令行中。
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            var arguments = directory.resolve("java.args");
            java.nio.file.Files.writeString(arguments, "-cp\n\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
            process = new ProcessBuilder(javaExecutable, "-Duser.timezone=Asia/Shanghai", "@" + arguments,
                    AccountCleanupProcess.class.getName(), directory.toString(), nonce, mode)
                    .redirectErrorStream(true).redirectOutput(directory.resolve("process.log").toFile()).start();
            started = process.info().startInstant().orElseThrow();
            try { awaitFile("ready"); verifyIdentity(); }
            catch (Exception | AssertionError failure) {
                try { stopOwnedProcess(); } catch (Exception | AssertionError cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                throw failure;
            }
        }

        void awaitFile(String name) throws Exception {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(75);
            while (!java.nio.file.Files.exists(directory.resolve(name))) {
                assertThat(process.isAlive()).as("child exited; log: %s", directory.resolve("process.log")).isTrue();
                assertThat(System.nanoTime()).as("waiting for %s; log: %s", name, directory.resolve("process.log")).isLessThan(deadline);
                Thread.sleep(50);
            }
        }

        String read(String name) throws Exception { return java.nio.file.Files.readString(directory.resolve(name)); }
        void release() throws Exception { java.nio.file.Files.writeString(directory.resolve("release"), nonce); }
        int deleted(long id) throws Exception {
            return java.nio.file.Files.exists(directory.resolve("deleted-" + id)) ? Integer.parseInt(read("deleted-" + id)) : 0;
        }

        void verifyIdentity() throws Exception {
            assertThat(read("ready")).isEqualTo(process.pid() + ":" + nonce);
            assertThat(process.info().startInstant()).contains(started);
            assertThat(process.info().arguments().orElseThrow()).contains(AccountCleanupProcess.class.getName(), nonce);
        }

        void killVerified() throws Exception {
            verifyIdentity();
            process.destroyForcibly();
            assertThat(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isNotZero();
        }

        private void stopOwnedProcess() throws Exception {
            if (!process.isAlive()) return;
            // 即使握手前启动失败，也只处理保存的直接子进程，且复核启动时间和随机参数。
            assertThat(process.info().startInstant()).contains(started);
            process.info().arguments().ifPresent(args -> assertThat(args).contains(AccountCleanupProcess.class.getName(), nonce));
            process.destroy();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertThat(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
        }

        @Override public void close() throws Exception { stopOwnedProcess(); }
    }

    @Test
    void realRedisCleanupTimeoutCannotRollbackDisableAndCanBeRetried() {
        String token = loginToken();
        redis.execute((RedisCallback<Object>) connection -> connection.execute("CLIENT",
                "PAUSE".getBytes(StandardCharsets.US_ASCII), "3000".getBytes(StandardCharsets.US_ASCII)));
        admin.updateStatus(-1L, 7L, 0);
        assertThat(jdbc.queryForObject("SELECT status FROM sys_user WHERE id=7", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_error FROM sys_auth_cleanup WHERE user_id=7", String.class))
                .isEqualTo("REDIS_CLEANUP_FAILED");
        assertThat(redis.execute((RedisCallback<String>) connection -> connection.ping())).isEqualTo("PONG");
        redis.delete(CacheConstants.TOKEN_BLACKLIST + token);
        tokens.addToWhitelist(7L, token);
        assertThat(tokens.validateActiveToken(7L, token)).isEqualTo(com.platform.cache.TokenValidationResult.ACTIVE);
        rejected(token);
        cleanup.attempt(7L);
        assertThat(tokens.getFromWhitelist(7L)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_auth_cleanup", Integer.class)).isZero();
    }

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
        // 每个连接固定数据库 UTC，显式与 JVM/驱动的上海时区不同，覆盖 DATETIME 的时钟边界。
        var properties = new java.util.Properties();
        properties.setProperty("sessionVariables", "time_zone='+00:00'");
        properties.setProperty("connectionTimeZone", "Asia/Shanghai");
        properties.setProperty("forceConnectionTimeZoneToSession", "false");
        ds.setConnectionProperties(properties);
        var check = new JdbcTemplate(ds);
        assertThat(java.time.ZoneId.systemDefault()).isEqualTo(java.time.ZoneId.of("Asia/Shanghai"));
        assertThat(check.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
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
