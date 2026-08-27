package com.platform.audit.retention;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
/** 保留策略版本只追加；执行版本按生效时间和版本号计算，未来版本不会造成保留空窗。 */
public class AuditRetentionPolicyRepository {
    private final JdbcTemplate jdbc;

    public int nextVersionForUpdate(String sourceModule) {
        List<Integer> values = jdbc.query("""
                SELECT policy_version FROM sys_audit_retention_policy
                WHERE source_module=? ORDER BY policy_version DESC LIMIT 1 FOR UPDATE
                """, (rs, row) -> rs.getInt(1), sourceModule);
        return values.isEmpty() ? 1 : values.getFirst() + 1;
    }

    public void supersedeCurrent(String sourceModule) {
        jdbc.update("""
                UPDATE sys_audit_retention_policy SET lifecycle_status='SUPERSEDED'
                WHERE source_module=? AND lifecycle_status='CURRENT'
                """, sourceModule);
    }

    public void insert(AuditRetentionPolicy value) {
        jdbc.update("""
                INSERT INTO sys_audit_retention_policy
                (policy_id,data_category,source_module,retention_period,cleanup_enabled,effective_at,
                 policy_version,lifecycle_status,change_reason,request_id,approved_by,approved_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, value.policyId(), value.dataCategory(), value.sourceModule(), value.retentionPeriod(),
                value.cleanupEnabled(), Timestamp.valueOf(value.effectiveAt()), value.policyVersion(),
                value.lifecycleStatus(), value.changeReason(), value.requestId(), value.approvedBy(),
                Timestamp.valueOf(value.approvedAt()));
    }

    public List<AuditRetentionPolicy> findAll() {
        return jdbc.query("""
                SELECT * FROM sys_audit_retention_policy
                ORDER BY source_module,policy_version DESC
                """, this::map);
    }

    public List<AuditRetentionPolicy> findDueCurrent(LocalDateTime now) {
        return jdbc.query("""
                SELECT p.* FROM sys_audit_retention_policy p
                WHERE p.cleanup_enabled=TRUE AND p.effective_at<=?
                  AND NOT EXISTS (
                    SELECT 1 FROM sys_audit_retention_policy newer
                    WHERE newer.source_module=p.source_module AND newer.effective_at<=?
                      AND newer.policy_version>p.policy_version
                  )
                ORDER BY p.source_module
                """, this::map, Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    private AuditRetentionPolicy map(ResultSet rs, int row) throws SQLException {
        return new AuditRetentionPolicy(rs.getString("policy_id"), rs.getString("data_category"),
                rs.getString("source_module"), rs.getString("retention_period"),
                rs.getBoolean("cleanup_enabled"), rs.getTimestamp("effective_at").toLocalDateTime(),
                rs.getInt("policy_version"), rs.getString("lifecycle_status"),
                rs.getString("change_reason"), rs.getString("request_id"), rs.getLong("approved_by"),
                rs.getTimestamp("approved_at").toLocalDateTime());
    }
}
