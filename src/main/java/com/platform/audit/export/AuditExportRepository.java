package com.platform.audit.export;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
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
/** 导出任务只保存不可变查询快照、文件校验值和生命周期，不保存导出的审计内容副本。 */
public class AuditExportRepository {
    @Qualifier("mysqlJdbcTemplate")
    private final JdbcTemplate jdbc;

    public void insert(AuditExportJob job) {
        jdbc.update("""
                INSERT INTO sys_audit_export_job
                (export_id,requested_by,purpose,query_json,query_sha256,status,expires_at,trace_id,created_at)
                VALUES (?,?,?,?,?,'PENDING',?,?,?)
                """, job.exportId(), job.requestedBy(), job.purpose(), job.queryJson(), job.querySha256(),
                Timestamp.valueOf(job.expiresAt()), job.traceId(), Timestamp.valueOf(job.createdAt()));
    }

    public Optional<AuditExportJob> find(String exportId) {
        return jdbc.query(SELECT + " WHERE export_id=?", this::map, exportId).stream().findFirst();
    }

    public boolean markRunning(String exportId) {
        return jdbc.update("""
                UPDATE sys_audit_export_job SET status='RUNNING',started_at=CURRENT_TIMESTAMP
                WHERE export_id=? AND status='PENDING'
                """, exportId) == 1;
    }

    public void complete(String exportId, int rowCount, String filePath, String fileSha256) {
        if (jdbc.update("""
                UPDATE sys_audit_export_job
                SET status='COMPLETED',row_count=?,file_path=?,file_sha256=?,completed_at=CURRENT_TIMESTAMP
                WHERE export_id=? AND status='RUNNING'
                """, rowCount, filePath, fileSha256, exportId) != 1) {
            throw new IllegalStateException("审计导出任务状态已变化");
        }
    }

    public void fail(String exportId, String errorCode) {
        jdbc.update("""
                UPDATE sys_audit_export_job
                SET status='FAILED',error_code=?,completed_at=CURRENT_TIMESTAMP
                WHERE export_id=? AND status IN ('PENDING','RUNNING')
                """, errorCode, exportId);
    }

    public void markDownloaded(String exportId, long userId) {
        if (jdbc.update("""
                UPDATE sys_audit_export_job SET downloaded_by=?,downloaded_at=CURRENT_TIMESTAMP
                WHERE export_id=? AND requested_by=? AND status='COMPLETED'
                """, userId, exportId, userId) != 1) {
            throw new IllegalStateException("审计导出下载状态已变化");
        }
    }

    public List<AuditExportJob> findExpiredCompleted(int limit) {
        return jdbc.query(SELECT + """
                WHERE status='COMPLETED' AND expires_at<=CURRENT_TIMESTAMP
                ORDER BY expires_at,export_id LIMIT ?
                """, this::map, limit);
    }

    public void markExpired(String exportId) {
        jdbc.update("""
                UPDATE sys_audit_export_job SET status='EXPIRED',file_path=NULL
                WHERE export_id=? AND status='COMPLETED' AND expires_at<=CURRENT_TIMESTAMP
                """, exportId);
    }

    private AuditExportJob map(ResultSet rs, int row) throws SQLException {
        return new AuditExportJob(
                rs.getString("export_id"), rs.getLong("requested_by"), rs.getString("purpose"),
                rs.getString("query_json"), rs.getString("query_sha256"), rs.getString("status"),
                nullableInteger(rs, "row_count"), rs.getString("file_path"), rs.getString("file_sha256"),
                time(rs, "expires_at"), rs.getString("error_code"), rs.getString("trace_id"),
                time(rs, "created_at"), time(rs, "started_at"), time(rs, "completed_at"),
                time(rs, "downloaded_at"));
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static final String SELECT = """
            SELECT export_id,requested_by,purpose,query_json,query_sha256,status,row_count,file_path,
                   file_sha256,expires_at,error_code,trace_id,created_at,started_at,completed_at,downloaded_at
            FROM sys_audit_export_job
            """;
}
