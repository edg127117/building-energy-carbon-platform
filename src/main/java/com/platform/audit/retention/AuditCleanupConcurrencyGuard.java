package com.platform.audit.retention;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
/** 在同一 MySQL 事务内串行化保全设置与物理删除，避免刚设置的保全被并发清理越过。 */
public class AuditCleanupConcurrencyGuard {
    private final JdbcTemplate jdbc;

    public void lock() {
        String value = jdbc.queryForObject("""
                SELECT duty_key FROM sys_backend_duty
                WHERE duty_key='AUDIT_EVIDENCE_HOLD_MANAGER' FOR UPDATE
                """, String.class);
        if (!"AUDIT_EVIDENCE_HOLD_MANAGER".equals(value)) {
            throw new IllegalStateException("审计保全并发锁锚点不存在");
        }
    }
}
