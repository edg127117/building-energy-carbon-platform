package com.platform.audit;

import com.platform.audit.duty.BackendDutyAssignmentService;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** 同一授权时间契约用于隔离 H2 和显式启用的专用 MySQL；到期只推进真实时间，不改写存量时间字段。 */
abstract class BackendDutyTimeContract {
    protected JdbcTemplate jdbc;
    private BackendDutyAssignmentService assignments;
    private JdbcBackendDutyService duties;
    private com.platform.security.OperationsDutyService operations;
    private DataSourceTransactionManager transactions;

    protected DataSource dataSource() {
        return new DriverManagerDataSource("jdbc:h2:mem:s02_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;INIT=SET TIME ZONE 'UTC'", "sa", "");
    }

    @BeforeEach
    void prepare() {
        var source = dataSource();
        jdbc = new JdbcTemplate(source);
        assertThat(ZoneId.systemDefault().getId()).isEqualTo("Asia/Shanghai");
        jdbc.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO sys_user(id) VALUES(7)");
        jdbc.execute("CREATE TABLE sys_backend_duty(duty_key VARCHAR(64) PRIMARY KEY,duty_name VARCHAR(100),description VARCHAR(500),status VARCHAR(20),risk_level VARCHAR(20),version INT)");
        jdbc.execute("""
                CREATE TABLE sys_user_backend_duty (
                  assignment_id VARCHAR(32) PRIMARY KEY,user_id BIGINT NOT NULL,duty_key VARCHAR(64) NOT NULL,
                  status VARCHAR(20) NOT NULL,effective_at DATETIME NOT NULL,expires_at DATETIME,
                  source_request_id VARCHAR(32),created_by BIGINT NOT NULL,created_at DATETIME NOT NULL,
                  revoked_by BIGINT,revoked_at DATETIME,revoke_request_id VARCHAR(32),
                  UNIQUE(user_id,duty_key),FOREIGN KEY(duty_key) REFERENCES sys_backend_duty(duty_key))
                """);
        new ResourceDatabasePopulator(new FileSystemResource("src/env/init/V76__mysql_operations_observer_duty.sql")).execute(source);
        transactions = new DataSourceTransactionManager(source);
        duties = new JdbcBackendDutyService(jdbc, new AuditGovernanceProperties());
        var proxy = new ProxyFactory(new com.platform.security.OperationsDutyService(duties));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        operations = (com.platform.security.OperationsDutyService) proxy.getProxy();
        assignments = new BackendDutyAssignmentService(jdbc, duties);
    }

    @AfterEach
    void dropFixtures() {
        if (jdbc == null) return;
        for (String table : java.util.List.of("sys_security_audit_event", "sys_audit_cleanup_run",
                "sys_audit_evidence_hold", "sys_audit_retention_policy", "sys_audit_export_job",
                "sys_password_setup_token", "sys_sensitive_change_request", "sys_user_backend_duty",
                "sys_backend_duty", "sys_user")) jdbc.execute("DROP TABLE IF EXISTS " + table);
    }

    @Test
    void committedRevocationCannotBeHiddenByCallersRepeatableReadSnapshot() {
        assignments.grant(7, BackendDuty.OPERATIONS_OBSERVER, null, null, "grant", 1);
        var caller = new TransactionTemplate(transactions);
        caller.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        caller.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT status FROM sys_user_backend_duty WHERE user_id=7", String.class)).isEqualTo("ACTIVE");
            java.util.concurrent.CompletableFuture.runAsync(() -> assignments.revoke(7,
                    BackendDuty.OPERATIONS_OBSERVER, "revoke", 1)).join();
            assertThat(jdbc.queryForObject("SELECT status FROM sys_user_backend_duty WHERE user_id=7", String.class)).isEqualTo("ACTIVE");
            assertThat(operations.canObserve(7)).isFalse();
        });
    }

    @Test
    void defaultGrantIsImmediatelyEffectiveInDifferentDatabaseTimezoneAndRevokeCannotReviveIt() {
        LocalDateTime before = databaseNow();
        assignments.grant(7, BackendDuty.OPERATIONS_OBSERVER, null, null, "grant", 1);
        LocalDateTime effective = stored("effective_at");
        assertThat(effective).isBetween(before.minusSeconds(1), databaseNow().plusSeconds(1));
        assertThat(duties.hasDuty(7, BackendDuty.OPERATIONS_OBSERVER)).isTrue();
        assignments.revoke(7, BackendDuty.OPERATIONS_OBSERVER, "revoke", 1);
        assertThat(stored("revoked_at")).isBetween(before.minusSeconds(1), databaseNow().plusSeconds(1));
        assertThat(duties.hasDuty(7, BackendDuty.OPERATIONS_OBSERVER)).isFalse();
        assignments.grant(7, BackendDuty.OPERATIONS_OBSERVER, null, null, "regrant", 1);
        assertThat(duties.hasDuty(7, BackendDuty.OPERATIONS_OBSERVER)).isTrue();
        assertThat(stored("revoked_at")).isNull();
    }

    @Test
    void futureGrantBecomesEffectiveThenExpiresWithoutRewritingDates() throws Exception {
        LocalDateTime effective = databaseNow().plusSeconds(2).withNano(0);
        LocalDateTime expires = effective.plusSeconds(2);
        assignments.grant(7, BackendDuty.OPERATIONS_OBSERVER, effective, expires, "timed", 1);
        assertThat(duties.hasDuty(7, BackendDuty.OPERATIONS_OBSERVER)).isFalse();
        awaitDatabaseTime(effective.plusNanos(1));
        assertThat(duties.hasDuty(7, BackendDuty.OPERATIONS_OBSERVER)).isTrue();
        awaitDatabaseTime(expires.plusNanos(1));
        assertThat(duties.hasDuty(7, BackendDuty.OPERATIONS_OBSERVER)).isFalse();
        assertThat(stored("effective_at")).isEqualTo(effective);
        assertThat(stored("expires_at")).isEqualTo(expires);
    }

    @Test
    void alreadyExpiredDefaultGrantIsRejectedAgainstDatabaseTime() {
        assertThatThrownBy(() -> assignments.grant(7, BackendDuty.OPERATIONS_OBSERVER,
                null, databaseNow().minusSeconds(1), "expired", 1))
                .isInstanceOf(com.platform.framework.exception.BusinessException.class);
        assertThat(duties.hasDuty(7, BackendDuty.OPERATIONS_OBSERVER)).isFalse();
    }

    private LocalDateTime stored(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM sys_user_backend_duty WHERE user_id=7",
                (result, row) -> result.getObject(1, LocalDateTime.class));
    }

    private LocalDateTime databaseNow() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", (result, row) -> result.getObject(1, LocalDateTime.class));
    }

    private void awaitDatabaseTime(LocalDateTime threshold) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(6);
        while (databaseNow().isBefore(threshold) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(databaseNow()).isAfterOrEqualTo(threshold);
    }
}
