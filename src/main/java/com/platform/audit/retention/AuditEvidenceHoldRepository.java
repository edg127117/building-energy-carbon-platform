package com.platform.audit.retention;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
/** 保全范围只追加或受审解除，不提供物理删除和普通更新入口。 */
public class AuditEvidenceHoldRepository {
    private final JdbcTemplate jdbc;

    public void insert(AuditEvidenceHold value) {
        jdbc.update("""
                INSERT INTO sys_audit_evidence_hold
                (hold_id,source_module,audit_id,building_id,action_type,object_type,object_id,
                 from_time,to_time,investigation_id,reason,legal_basis,starts_at,review_at,status,
                 created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, value.holdId(), value.sourceModule(), value.auditId(), value.buildingId(),
                value.actionType(), value.objectType(), value.objectId(), timestamp(value.fromTime()),
                timestamp(value.toTime()), value.investigationId(), value.reason(), value.legalBasis(),
                timestamp(value.startsAt()), timestamp(value.reviewAt()), value.status(), value.createdBy(),
                timestamp(value.createdAt()));
    }

    public Optional<AuditEvidenceHold> find(String holdId, boolean lock) {
        List<AuditEvidenceHold> values = jdbc.query(
                "SELECT * FROM sys_audit_evidence_hold WHERE hold_id=?" + (lock ? " FOR UPDATE" : ""),
                this::map, holdId);
        return values.stream().findFirst();
    }

    public List<AuditEvidenceHold> findAll() {
        return jdbc.query("""
                SELECT * FROM sys_audit_evidence_hold ORDER BY created_at DESC,hold_id DESC
                """, this::map);
    }

    public int release(String holdId, long releasedBy, String requestId, String reason, LocalDateTime now) {
        return jdbc.update("""
                UPDATE sys_audit_evidence_hold
                SET status='RELEASED',released_by=?,released_at=?,release_request_id=?,release_reason=?
                WHERE hold_id=? AND status='ACTIVE'
                """, releasedBy, timestamp(now), requestId, reason, holdId);
    }

    private AuditEvidenceHold map(ResultSet rs, int row) throws SQLException {
        long released = rs.getLong("released_by");
        return new AuditEvidenceHold(rs.getString("hold_id"), rs.getString("source_module"),
                rs.getString("audit_id"), rs.getString("building_id"), rs.getString("action_type"),
                rs.getString("object_type"), rs.getString("object_id"), local(rs.getTimestamp("from_time")),
                local(rs.getTimestamp("to_time")), rs.getString("investigation_id"), rs.getString("reason"),
                rs.getString("legal_basis"), local(rs.getTimestamp("starts_at")),
                local(rs.getTimestamp("review_at")), rs.getString("status"), rs.getLong("created_by"),
                local(rs.getTimestamp("created_at")), rs.wasNull() ? null : released,
                local(rs.getTimestamp("released_at")), rs.getString("release_request_id"),
                rs.getString("release_reason"));
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime local(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
