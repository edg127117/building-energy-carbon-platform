package com.platform.security;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.platform.audit.*;
import com.platform.cache.*;
import com.platform.config.TestRedisConfiguration;
import com.platform.framework.exception.BusinessException;
import com.platform.hvac.mapper.BuildingMapper;
import com.platform.system.mapper.*;
import com.platform.system.model.dto.UserAdminDtos;
import com.platform.system.service.*;
import com.platform.system.service.impl.*;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 同一真实服务契约运行于 H2 替身或显式启用的专用 MySQL/Redis；屏障控制交错，不以睡眠猜测顺序。 */
abstract class AccountSecurityContract {
    protected JdbcTemplate jdbc;
    protected StringRedisTemplate redis;
    protected DataSourceTransactionManager tm;
    protected SysUserMapper users;
    protected TokenCacheService tokens;
    protected AccountSessionService sessions;
    protected SysUserAdminService admin;
    protected PasswordSetupTokenService passwords;
    protected AuthenticationStateService authority;
    protected JwtService jwt;
    protected SysUserServiceImpl login;
    private ExecutorService workers;
    private final List<String> tables = List.of("sys_password_setup_token", "sys_user_building", "sys_user_role", "sys_role", "sys_user");

    protected DataSource dataSource() {
        return new DriverManagerDataSource("jdbc:h2:mem:s01_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false;LOCK_TIMEOUT=5000", "sa", "");
    }

    protected StringRedisTemplate redisTemplate() {
        return ReflectionTestUtils.invokeMethod(new TestRedisConfiguration(), "testStringRedisTemplate");
    }

    @BeforeEach
    void prepare() throws Exception {
        DataSource ds = dataSource();
        jdbc = new JdbcTemplate(ds);
        // 只创建本契约需要的表；对真实引擎的空库与名称检查由子类先执行。
        String schema = Files.readString(Path.of("src/test/resources/schema-test.sql"))
                + Files.readString(Path.of("src/test/resources/audit-governance-schema-test.sql"));
        for (String table : tables.reversed()) {
            var match = Pattern.compile("CREATE TABLE(?: IF NOT EXISTS)? " + table + "\\s*\\([\\s\\S]*?\\);", Pattern.CASE_INSENSITIVE).matcher(schema);
            assertThat(match.find()).as("fixture table %s", table).isTrue();
            jdbc.execute(match.group().replace("  security_version BIGINT DEFAULT 1 NOT NULL,", ""));
        }
        var encoder = new BCryptPasswordEncoder(4);
        jdbc.update("INSERT INTO sys_user(id,username,password,status,del_flag,activation_pending) VALUES(7,'s01',?,1,0,0)", encoder.encode("pass"));
        jdbc.execute(Files.readString(Path.of("src/env/init/V73__mysql_account_security_version.sql")));
        assertThat(jdbc.queryForObject("SELECT security_version FROM sys_user WHERE id=7", Long.class)).isEqualTo(1L);
        jdbc.update("INSERT INTO sys_role(id,role_key,role_name,status) VALUES(1,'BUILDING_OWNER','Owner',1),(2,'ENERGY_MANAGER','Energy',1)");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(7,1)");
        redis = redisTemplate();
        tm = new DataSourceTransactionManager(ds);
        MybatisConfiguration config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLogImpl(NoLoggingImpl.class);
        config.setDefaultStatementTimeout(5);
        config.addMapper(SysUserMapper.class);
        config.addMapper(SysRoleMapper.class);
        config.addMapper(SysUserRoleMapper.class);
        config.addMapper(SysUserBuildingMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds); factory.setConfiguration(config);
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        users = spy(sql.getMapper(SysUserMapper.class));
        SysRoleMapper roles = sql.getMapper(SysRoleMapper.class);
        tokens = spy(new TokenCacheService(redis, 3600));
        MenuCacheService menus = mock(MenuCacheService.class);
        BuildingScopeService buildings = mock(BuildingScopeService.class);
        jwt = new JwtService();
        ReflectionTestUtils.setField(jwt, "secret", "test-only-jwt-secret-with-more-than-thirty-two-bytes");
        ReflectionTestUtils.setField(jwt, "expireSeconds", 3600L);
        sessions = spy(new AccountSessionService(users, roles, jwt, tokens, menus, tm));
        authority = new AuthenticationStateService(tokens, users, tm);
        var audit = mock(AuditEvidenceWriter.class);
        var properties = new AuditGovernanceProperties();
        passwords = transactional(new PasswordSetupTokenService(jdbc, users, encoder, tokens, audit, properties));
        admin = transactional(new SysUserAdminServiceImpl(users, roles, sql.getMapper(SysUserRoleMapper.class),
                sql.getMapper(SysUserBuildingMapper.class), mock(BuildingMapper.class), encoder, tokens, menus,
                buildings, passwords, audit, properties));
        login = new SysUserServiceImpl(encoder, jwt, tokens, mock(SysRoleService.class), roles,
                sql.getMapper(SysUserRoleMapper.class), menus, buildings, sessions);
        ReflectionTestUtils.setField(login, "baseMapper", users);
        workers = Executors.newFixedThreadPool(2);
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(tm, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    @AfterEach
    void close() throws Exception {
        if (workers != null) {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        if (jdbc != null) for (String table : tables) jdbc.execute("DROP TABLE IF EXISTS " + table);
    }

    @Test
    void pausedPasswordCheckCannotPublishAfterDisable() throws Exception {
        Gate gate = beforeSession();
        Future<String> pending = workers.submit(this::loginToken);
        gate.awaitEntered();
        try { admin.updateStatus(-1L, 7L, 0); } finally { gate.open(); }
        assertFutureRejected(pending);
        assertThat(tokens.getFromWhitelist(7L)).isNull();
    }

    @Test
    void pausedLoginReadsNewRoleAfterRoleChange() throws Exception {
        Gate gate = beforeSession();
        Future<String> pending = workers.submit(this::loginToken);
        gate.awaitEntered();
        try { admin.replaceRoles(-1L, 7L, List.of("ENERGY_MANAGER")); } finally { gate.open(); }
        String token = pending.get(10, TimeUnit.SECONDS);
        assertThat(jwt.getRoles(jwt.parseToken(token).getPayload())).containsExactly("ENERGY_MANAGER");
        active(token);
    }

    @Test
    void loginHoldingLockFinishesBeforeDisableAndThenBecomesInvalid() throws Exception {
        Gate gate = beforeWhitelist();
        Future<String> pending = workers.submit(this::loginToken);
        gate.awaitEntered();
        CountDownLatch attempted = new CountDownLatch(1);
        Future<?> disable = workers.submit(() -> { attempted.countDown(); admin.updateStatus(-1L, 7L, 0); });
        assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
        gate.open();
        String token = pending.get(10, TimeUnit.SECONDS);
        disable.get(10, TimeUnit.SECONDS);
        tokens.addToWhitelist(7L, token); // Redis 恢复出旧数据也不得恢复账号权限。
        rejected(token);
    }

    @Test
    void concurrentLoginsLeaveOnlyLastCommittedVersionValid() throws Exception {
        Gate gate = beforeWhitelist();
        Future<String> first = workers.submit(this::loginToken);
        gate.awaitEntered();
        Future<String> second = workers.submit(this::loginToken);
        gate.open();
        String oldToken = first.get(10, TimeUnit.SECONDS);
        String current = second.get(10, TimeUnit.SECONDS);
        assertThat(version(current)).isGreaterThan(version(oldToken));
        active(current);
        tokens.addToWhitelist(7L, oldToken);
        rejected(oldToken);
    }

    @Test
    void delayedLogoutCannotDeleteNewSession() {
        String oldToken = loginToken();
        String current = loginToken();
        assertThatThrownBy(() -> sessions.logout(7L, oldToken)).isInstanceOf(BusinessException.class);
        assertThat(tokens.getFromWhitelist(7L)).isEqualTo(current);
        active(current);
    }

    @Test
    void logoutSurvivesRedisRewind() {
        String token = loginToken();
        sessions.logout(7L, token);
        redis.delete(CacheConstants.TOKEN_BLACKLIST + token);
        tokens.addToWhitelist(7L, token);
        rejected(token);
    }

    @Test
    void enableAndRestoreNeverReactivateOldVersion() {
        String token = loginToken();
        admin.updateStatus(-1L, 7L, 0);
        admin.updateStatus(-1L, 7L, 1);
        tokens.addToWhitelist(7L, token);
        rejected(token);
        String next = loginToken();
        admin.delete(-1L, 7L);
        admin.restore(7L);
        tokens.addToWhitelist(7L, next);
        rejected(next);
        assertThat(users.selectSecurityState(7L).getStatus()).isEqualTo(1);
    }

    @Test
    void passwordResetInvalidatesPausedLoginAndCannotBeConsumedTwice() throws Exception {
        String raw = passwords.issue(7L, "test-request", -1L).token();
        Gate gate = beforeSession();
        Future<String> pending = workers.submit(this::loginToken);
        gate.awaitEntered();
        try { passwords.setupPassword(raw, "new-pass"); } finally { gate.open(); }
        assertFutureRejected(pending);
        assertThatThrownBy(() -> passwords.setupPassword(raw, "another")).isInstanceOf(BusinessException.class);
        String token = login.login("s01", "new-pass").getToken();
        active(token);
    }

    @Test
    void profileUpdateCannotOverwriteConcurrentSecurityChange() throws Exception {
        Gate gate = new Gate();
        doAnswer(call -> { Object user = call.callRealMethod(); gate.pause(); return user; }).when(users).selectById(7L);
        Future<?> profile = workers.submit(() -> admin.update(7L, new UserAdminDtos.UpdateRequest("new-name", "123")));
        gate.awaitEntered();
        try { admin.updateStatus(-1L, 7L, 0); } finally { gate.open(); }
        profile.get(10, TimeUnit.SECONDS);
        assertThat(users.selectSecurityState(7L).getStatus()).isZero();
        assertThat(users.selectSecurityState(7L).getSecurityVersion()).isEqualTo(2L);
    }

    @Test
    void partialRedisWriteRollsBackDatabaseAndDoesNotReturnToken() {
        doAnswer(call -> { call.callRealMethod(); throw AuthenticationStateService.unavailable(); })
                .when(tokens).addToWhitelist(eq(7L), anyString());
        assertThatThrownBy(this::loginToken).isInstanceOf(BusinessException.class);
        assertThat(users.selectSecurityState(7L).getSecurityVersion()).isEqualTo(1L);
        String unpublished = tokens.getFromWhitelist(7L);
        assertThat(unpublished).isNotNull();
        rejected(unpublished);
    }

    @Test
    void failureAfterRedisBeforeCommitDoesNotPublishSuccessfulLogin() {
        doAnswer(call -> {
            call.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { throw new DataAccessResourceFailureException("isolated commit failure"); }
            });
            return null;
        }).when(tokens).addToWhitelist(eq(7L), anyString());
        assertThatThrownBy(this::loginToken).isInstanceOf(BusinessException.class);
        assertThat(users.selectSecurityState(7L).getSecurityVersion()).isEqualTo(1L);
        rejected(tokens.getFromWhitelist(7L));
    }

    @Test
    void revocationFailureRollsBackStatusRolesAndVersion() {
        String token = loginToken();
        long before = version(token);
        doThrow(AuthenticationStateService.unavailable()).when(tokens).revokeActiveToken(7L);
        assertThatThrownBy(() -> admin.updateStatus(-1L, 7L, 0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> admin.replaceRoles(-1L, 7L, List.of("ENERGY_MANAGER"))).isInstanceOf(BusinessException.class);
        assertThat(users.selectSecurityState(7L).getSecurityVersion()).isEqualTo(before);
        assertThat(admin.detail(7L).roles()).containsExactly("BUILDING_OWNER");
        active(token);
    }

    @Test
    void activationIncrementsVersionAndPasswordFailureRollsBackTokenConsumption() {
        jdbc.update("UPDATE sys_user SET status=0,activation_pending=1 WHERE id=7");
        String activation = passwords.issue(7L, "activate", -1L).token();
        passwords.setupPassword(activation, "pass");
        assertThat(users.selectSecurityState(7L).getSecurityVersion()).isEqualTo(2L);
        String token = loginToken();
        String resetToken = passwords.issue(7L, "reset", -1L).token();
        doThrow(AuthenticationStateService.unavailable()).when(tokens).revokeActiveToken(7L);
        assertThatThrownBy(() -> passwords.setupPassword(resetToken, "new-pass")).isInstanceOf(BusinessException.class);
        assertThat(users.selectSecurityState(7L).getSecurityVersion()).isEqualTo(version(token));
        assertThat(jdbc.queryForObject("SELECT status FROM sys_password_setup_token WHERE source_request_id='reset'", String.class)).isEqualTo("ACTIVE");
        active(token);
        doCallRealMethod().when(tokens).revokeActiveToken(7L);
        passwords.setupPassword(resetToken, "new-pass");
        tokens.addToWhitelist(7L, token);
        rejected(token);
    }

    @Test
    void versionOverflowFailsWithoutWrappingOrPublishingSession() {
        jdbc.update("UPDATE sys_user SET security_version=? WHERE id=7", Long.MAX_VALUE);
        assertThatThrownBy(this::loginToken).isInstanceOf(BusinessException.class);
        assertThat(users.selectSecurityState(7L).getSecurityVersion()).isEqualTo(Long.MAX_VALUE);
        assertThat(tokens.getFromWhitelist(7L)).isNull();
    }

    @Test
    void authorityRejectsMysqlFailureMissingVersionAndOuterStaleSnapshot() throws Exception {
        String token = loginToken();
        var outer = new TransactionTemplate(tm);
        outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.execute(status -> {
            users.selectSecurityState(7L);
            try { workers.submit(() -> admin.replaceRoles(-1L, 7L, List.of("ENERGY_MANAGER"))).get(10, TimeUnit.SECONDS); }
            catch (Exception e) { throw new AssertionError(e); }
            redis.delete(CacheConstants.TOKEN_BLACKLIST + token);
            tokens.addToWhitelist(7L, token);
            rejected(token);
            return null;
        });
        assertThatThrownBy(() -> authority.requireActive(7L, token, null)).isInstanceOf(BusinessException.class);
        doThrow(new DataAccessResourceFailureException("do-not-expose-db-details")).when(users).selectSecurityState(7L);
        assertThatThrownBy(() -> authority.requireActive(7L, token, version(token)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(503);
                    assertThat(e.getMessage()).doesNotContain("do-not-expose");
                });
    }

    protected String loginToken() { return login.login("s01", "pass").getToken(); }
    protected long version(String token) { return jwt.getSecurityVersion(jwt.parseToken(token).getPayload()); }
    protected void active(String token) { authority.requireActive(7L, token, version(token)); }
    protected void rejected(String token) {
        assertThatThrownBy(() -> active(token)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(401));
    }
    private void assertFutureRejected(Future<?> future) {
        assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(BusinessException.class);
    }
    private Gate beforeSession() {
        Gate gate = new Gate();
        doAnswer(call -> { gate.pause(); return call.callRealMethod(); }).when(sessions).login(eq(7L), anyString(), nullable(String.class));
        return gate;
    }
    private Gate beforeWhitelist() {
        Gate gate = new Gate();
        doAnswer(call -> { gate.pause(); return call.callRealMethod(); }).when(tokens).addToWhitelist(eq(7L), anyString());
        return gate;
    }
    private static final class Gate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        void pause() throws InterruptedException { entered.countDown(); assertThat(release.await(8, TimeUnit.SECONDS)).isTrue(); }
        void awaitEntered() throws InterruptedException { assertThat(entered.await(8, TimeUnit.SECONDS)).isTrue(); }
        void open() { release.countDown(); }
    }
}
