package com.platform.audit.retention;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.sql.ResultSet;
import java.sql.SQLException;

@Repository
@RequiredArgsConstructor
/** 清理证据表不属于任何审计源清理处理器，因此不会被同一次到期任务删除。 */
public class AuditCleanupRunRepository {
    private final JdbcTemplate jdbc;

    public void insert(AuditCleanupRun value) {
        insert(value, null);
    }

    public void insert(AuditCleanupRun value, String scopeJson) {
        jdbc.update("""
                INSERT INTO sys_audit_cleanup_run
                (run_id,trigger_type,policy_id,policy_version,data_category,source_module,building_id,
                 cutoff_time,scope_json,candidate_count,deleted_count,held_count,protected_count,
                 earliest_operation_time,latest_operation_time,status,error_code,system_actor,
                 triggered_by,trigger_request_id,trace_id,manifest_sha256,started_at,completed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, value.runId(), value.triggerType(), value.policyId(), value.policyVersion(),
                value.dataCategory(), value.sourceModule(), value.buildingId(), timestamp(value.cutoffTime()),
                scopeJson, value.candidateCount(), value.deletedCount(), value.heldCount(), value.protectedCount(),
                timestamp(value.earliestOperationTime()), timestamp(value.latestOperationTime()), value.status(),
                value.errorCode(), value.systemActor(), value.triggeredBy(), value.triggerRequestId(),
                value.traceId(), value.manifestSha256(), timestamp(value.startedAt()),
                timestamp(value.completedAt()));
    }

    public java.util.List<PendingExceptionRun> findPendingExceptions(int limit) {
        return jdbc.query("""
                SELECT run_id,source_module,scope_json,candidate_count,deleted_count,triggered_by,
                       trigger_request_id,trace_id,data_category,earliest_operation_time,
                       latest_operation_time,manifest_sha256
                FROM sys_audit_cleanup_run
                WHERE trigger_type='EXCEPTION' AND status IN ('PENDING','PARTIAL')
                ORDER BY started_at,run_id LIMIT ?
                """, (rs, row) -> new PendingExceptionRun(rs.getString("run_id"),
                rs.getString("source_module"), rs.getString("scope_json"),
                rs.getLong("candidate_count"), rs.getLong("deleted_count"), rs.getLong("triggered_by"),
                rs.getString("trigger_request_id"), rs.getString("trace_id"),
                rs.getString("data_category"),
                rs.getTimestamp("earliest_operation_time") == null ? null
                        : rs.getTimestamp("earliest_operation_time").toLocalDateTime(),
                rs.getTimestamp("latest_operation_time") == null ? null
                        : rs.getTimestamp("latest_operation_time").toLocalDateTime(),
                rs.getString("manifest_sha256")), limit);
    }

    public boolean claim(String runId) {
        return jdbc.update("""
                UPDATE sys_audit_cleanup_run SET status='RUNNING',started_at=?
                WHERE run_id=? AND status IN ('PENDING','PARTIAL')
                """, timestamp(LocalDateTime.now()), runId) == 1;
    }

    public java.util.List<AuditCleanupRun> findRecent(int limit) {
        return jdbc.query("""
                SELECT * FROM sys_audit_cleanup_run
                ORDER BY started_at DESC,run_id DESC LIMIT ?
                """, this::map, limit);
    }

    public void finish(String runId, String status, long deletedCount,
                       LocalDateTime earliest, LocalDateTime latest, String manifest,
                       String errorCode, LocalDateTime completedAt) {
        int changed = jdbc.update("""
                UPDATE sys_audit_cleanup_run
                SET status=?,deleted_count=?,earliest_operation_time=?,latest_operation_time=?,
                    manifest_sha256=?,error_code=?,completed_at=?
                WHERE run_id=? AND status='RUNNING'
                """, status, deletedCount, timestamp(earliest), timestamp(latest), manifest,
                errorCode, timestamp(completedAt), runId);
        if (changed != 1) throw new IllegalStateException("清理批次状态已变化");
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private AuditCleanupRun map(ResultSet rs, int row) throws SQLException {
        long triggeredBy = rs.getLong("triggered_by");
        boolean triggeredByNull = rs.wasNull();
        int policyVersion = rs.getInt("policy_version");
        boolean policyVersionNull = rs.wasNull();
        return new AuditCleanupRun(rs.getString("run_id"), rs.getString("trigger_type"),
                rs.getString("policy_id"), policyVersionNull ? null : policyVersion,
                rs.getString("data_category"), rs.getString("source_module"),
                rs.getString("building_id"), local(rs.getTimestamp("cutoff_time")),
                rs.getLong("candidate_count"), rs.getLong("deleted_count"), rs.getLong("held_count"),
                rs.getLong("protected_count"), local(rs.getTimestamp("earliest_operation_time")),
                local(rs.getTimestamp("latest_operation_time")), rs.getString("status"),
                rs.getString("error_code"), rs.getString("system_actor"),
                triggeredByNull ? null : triggeredBy, rs.getString("trigger_request_id"),
                rs.getString("trace_id"), rs.getString("manifest_sha256"),
                local(rs.getTimestamp("started_at")), local(rs.getTimestamp("completed_at")));
    }

    private static LocalDateTime local(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    public record PendingExceptionRun(
            String runId, String sourceModule, String scopeJson, long candidateCount, long deletedCount,
            long triggeredBy, String triggerRequestId, String traceId, String dataCategory,
            LocalDateTime earliestOperationTime, LocalDateTime latestOperationTime, String manifestSha256) {
    }
}
