package com.platform.audit.retention;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "AUDIT_MYSQL_IT_URL", matches = ".+")
class AuditRetentionMysqlIntegrationTest {
    @Test
    void migratesV30AndDeletesByIndexedBatchesWithoutCrossingProtection() {
        String url = System.getenv("AUDIT_MYSQL_IT_URL");
        assertThat(System.getenv("AUDIT_MYSQL_IT_ISOLATED"))
                .as("集成测试必须显式确认使用一次性隔离 MySQL").isEqualTo("true");
        assertThat(url).contains("/iot_platform");
        DataSource dataSource = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("AUDIT_MYSQL_IT_USER", "root"),
                System.getenv().getOrDefault("AUDIT_MYSQL_IT_PASSWORD", "test-root"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",
                Integer.class)).isZero();

        Flyway flyway = Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/env/init").load();
        assertThat(flyway.migrate().migrationsExecuted).isPositive();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(tableExists(jdbc, "sys_audit_retention_policy")).isTrue();
        assertThat(tableExists(jdbc, "sys_audit_evidence_hold")).isTrue();
        assertThat(tableExists(jdbc, "sys_audit_cleanup_run")).isTrue();

        LocalDateTime old = LocalDateTime.of(2025, 1, 1, 0, 0);
        audit(jdbc, "DELETE", old, null);
        audit(jdbc, "HELD", old.plusSeconds(1), null);
        request(jdbc, "PENDING");
        audit(jdbc, "PENDING", old.plusSeconds(2), "PENDING");
        jdbc.update("""
                INSERT INTO sys_audit_evidence_hold
                (hold_id,source_module,audit_id,reason,legal_basis,starts_at,review_at,status,created_by)
                VALUES ('HOLD1','SYSTEM_SECURITY','HELD','investigation','case',?,?,'ACTIVE',1)
                """, Timestamp.valueOf(old), Timestamp.valueOf(old.plusYears(2)));

        var definition = new JdbcAuditRetentionHandler.Definition(
                "SYSTEM_SECURITY", "sys_security_audit_event", "audit_id", "building_id",
                "action_type", "object_type", "object_id", "operation_time",
                "EXISTS (SELECT 1 FROM sys_sensitive_change_request p "
                        + "WHERE p.request_id=sys_security_audit_event.review_request_id "
                        + "AND p.status IN ('DRAFT','PENDING_REVIEW','APPROVED'))");
        AuditRetentionHandler handler = new JdbcAuditRetentionHandler(jdbc, definition);
        AuditRetentionScope scope = AuditRetentionScope.expiredBefore(old.plusYears(1));
        AuditRetentionPreview preview = handler.preview(scope);
        assertThat(preview.candidateCount()).isEqualTo(3);
        assertThat(preview.eligibleCount()).isEqualTo(1);
        assertThat(preview.heldCount()).isEqualTo(1);
        assertThat(preview.protectedCount()).isEqualTo(1);
        assertThat(handler.deleteNextBatch(scope, 100).deletedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_security_audit_event", Integer.class))
                .isEqualTo(2);
    }

    private static void audit(JdbcTemplate jdbc, String id, LocalDateTime time, String requestId) {
        jdbc.update("""
                INSERT INTO sys_security_audit_event
                (audit_id,source_module,actor_type,action_type,object_type,object_id,review_request_id,
                 result,trace_id,operation_time,environment_mode,self_approval_dev_mode)
                VALUES (?,'SYSTEM_SECURITY','SYSTEM','TEST','TEST',?,?,
                        'SUCCESS','mysql-test',?,'TEST',FALSE)
                """, id, id, requestId, Timestamp.valueOf(time));
    }

    private static void request(JdbcTemplate jdbc, String id) {
        jdbc.update("""
                INSERT INTO sys_sensitive_change_request
                (request_id,operation_code,status,target_type,target_id,command_json,request_sha256,
                 impact_summary,submitted_by,idempotency_key,trace_id,environment_mode,self_approval_dev_mode)
                VALUES (?,'TEST','PENDING_REVIEW','TEST','TEST',JSON_OBJECT(),?,
                        'pending',1,'pending-key','mysql-test','TEST',FALSE)
                """, id, "0".repeat(64));
    }

    private static boolean tableExists(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name=?
                """, Integer.class, table) == 1;
    }
}
