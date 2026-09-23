package com.platform.audit.sensitive;

import com.platform.framework.web.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
/** 通用敏感变更申请的定向 SQL 仓储；所有状态更新都带前置状态条件。 */
public class SensitiveChangeRepository {
    private static final String SELECT = """
            SELECT request_id,operation_code,status,building_id,target_type,target_id,command_json,
                   request_sha256,impact_summary,submitted_by,submitted_at,reviewer_id,review_comment,
                   reviewed_at,executed_at,execution_error_code,idempotency_key,trace_id,environment_mode,
                   self_approval_dev_mode,create_time,update_time
            FROM sys_sensitive_change_request
            """;

    private final JdbcTemplate jdbcTemplate;

    public Optional<SensitiveChangeRecord> find(String requestId, boolean lock) {
        String sql = SELECT + " WHERE request_id=?" + (lock ? " FOR UPDATE" : "");
        return jdbcTemplate.query(sql, MAPPER, requestId).stream().findFirst();
    }

    public Optional<SensitiveChangeRecord> findByIdempotency(long submitterId, String idempotencyKey) {
        return jdbcTemplate.query(SELECT + " WHERE submitted_by=? AND idempotency_key=?",
                MAPPER, submitterId, idempotencyKey).stream().findFirst();
    }

    /** 审核待办还包括本人已批准但待执行的申请；列表仅返回摘要，不泄露命令原文及一次性凭据。 */
    public PageResponse<ListItem> list(long userId, boolean reviewQueue, boolean allowSelfApproval, int page, int size) {
        String where = reviewQueue
                ? " WHERE (r.status='PENDING_REVIEW' AND (?=TRUE OR r.submitted_by<>?))"
                    + " OR (r.status='APPROVED' AND r.reviewer_id=?)"
                : " WHERE r.submitted_by=?";
        Long total = reviewQueue
                ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_sensitive_change_request r" + where,
                        Long.class, allowSelfApproval, userId, userId)
                : jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_sensitive_change_request r" + where,
                        Long.class, userId);
        String sql = """
                SELECT r.request_id,r.operation_code,r.status,r.target_type,r.target_id,r.impact_summary,
                       r.submitted_by,u.username AS submitter_name,r.submitted_at,r.create_time
                FROM sys_sensitive_change_request r
                LEFT JOIN sys_user u ON u.id=r.submitted_by
                """ + where + " ORDER BY r.create_time DESC,r.request_id DESC LIMIT ? OFFSET ?";
        long offset = ((long) page - 1) * size;
        List<ListItem> items = reviewQueue
                ? jdbcTemplate.query(sql, LIST_MAPPER, allowSelfApproval, userId, userId, size, offset)
                : jdbcTemplate.query(sql, LIST_MAPPER, userId, size, offset);
        return new PageResponse<>(page, size, total == null ? 0 : total, items);
    }

    public record ListItem(String requestId, String operationCode, SensitiveChangeStatus status,
                           String targetType, String targetId, String impactSummary, long submittedBy,
                           String submitterName, LocalDateTime submittedAt, LocalDateTime createTime) { }

    private static final RowMapper<ListItem> LIST_MAPPER = (rs, rowNum) -> new ListItem(
            rs.getString("request_id"), rs.getString("operation_code"),
            SensitiveChangeStatus.valueOf(rs.getString("status")), rs.getString("target_type"),
            rs.getString("target_id"), rs.getString("impact_summary"), rs.getLong("submitted_by"),
            rs.getString("submitter_name"), localDateTime(rs, "submitted_at"),
            localDateTime(rs, "create_time"));

    public void insert(SensitiveChangeRecord value) {
        jdbcTemplate.update("""
                INSERT INTO sys_sensitive_change_request
                (request_id,operation_code,status,building_id,target_type,target_id,command_json,request_sha256,
                 impact_summary,submitted_by,idempotency_key,trace_id,environment_mode,self_approval_dev_mode,
                 create_time,update_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, value.requestId(), value.operationCode(), value.status().name(), value.buildingId(),
                value.targetType(), value.targetId(), value.commandJson(), value.requestSha256(),
                value.impactSummary(), value.submittedBy(), value.idempotencyKey(), value.traceId(),
                value.environmentMode(), value.selfApprovalDevMode(), timestamp(value.createTime()),
                timestamp(value.updateTime()));
    }

    public int submit(String requestId, LocalDateTime at, String traceId) {
        return jdbcTemplate.update("""
                UPDATE sys_sensitive_change_request
                SET status='PENDING_REVIEW',submitted_at=?,trace_id=?,update_time=?
                WHERE request_id=? AND status='DRAFT'
                """, timestamp(at), traceId, timestamp(at), requestId);
    }

    public int withdraw(String requestId, LocalDateTime at, String traceId) {
        return jdbcTemplate.update("""
                UPDATE sys_sensitive_change_request
                SET status='WITHDRAWN',trace_id=?,update_time=?
                WHERE request_id=? AND status IN ('DRAFT','PENDING_REVIEW')
                """, traceId, timestamp(at), requestId);
    }

    public int approve(String requestId, long reviewerId, String comment, LocalDateTime at,
                       String traceId, boolean selfApproval) {
        return jdbcTemplate.update("""
                UPDATE sys_sensitive_change_request
                SET status='APPROVED',reviewer_id=?,review_comment=?,reviewed_at=?,trace_id=?,
                    self_approval_dev_mode=?,update_time=?
                WHERE request_id=? AND status='PENDING_REVIEW'
                """, reviewerId, comment, timestamp(at), traceId, selfApproval, timestamp(at), requestId);
    }

    public int reject(String requestId, long reviewerId, String comment, LocalDateTime at,
                      String traceId, boolean selfApproval) {
        return jdbcTemplate.update("""
                UPDATE sys_sensitive_change_request
                SET status='REJECTED',reviewer_id=?,review_comment=?,reviewed_at=?,trace_id=?,
                    self_approval_dev_mode=?,update_time=?
                WHERE request_id=? AND status='PENDING_REVIEW'
                """, reviewerId, comment, timestamp(at), traceId, selfApproval, timestamp(at), requestId);
    }

    public int markExecuted(String requestId, LocalDateTime at, String traceId) {
        return jdbcTemplate.update("""
                UPDATE sys_sensitive_change_request
                SET status='EXECUTED',executed_at=?,execution_error_code=NULL,trace_id=?,update_time=?
                WHERE request_id=? AND status='APPROVED'
                """, timestamp(at), traceId, timestamp(at), requestId);
    }

    public int markExecutionFailed(String requestId, String errorCode, LocalDateTime at, String traceId) {
        return jdbcTemplate.update("""
                UPDATE sys_sensitive_change_request
                SET status='EXECUTION_FAILED',executed_at=?,execution_error_code=?,trace_id=?,update_time=?
                WHERE request_id=? AND status='APPROVED'
                """, timestamp(at), errorCode, traceId, timestamp(at), requestId);
    }

    private static final RowMapper<SensitiveChangeRecord> MAPPER = (rs, rowNum) -> new SensitiveChangeRecord(
            rs.getString("request_id"), rs.getString("operation_code"),
            SensitiveChangeStatus.valueOf(rs.getString("status")), rs.getString("building_id"),
            rs.getString("target_type"), rs.getString("target_id"), rs.getString("command_json"),
            rs.getString("request_sha256"), rs.getString("impact_summary"), rs.getLong("submitted_by"),
            localDateTime(rs, "submitted_at"), nullableLong(rs, "reviewer_id"), rs.getString("review_comment"),
            localDateTime(rs, "reviewed_at"), localDateTime(rs, "executed_at"),
            rs.getString("execution_error_code"), rs.getString("idempotency_key"), rs.getString("trace_id"),
            rs.getString("environment_mode"), rs.getBoolean("self_approval_dev_mode"),
            localDateTime(rs, "create_time"), localDateTime(rs, "update_time"));

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }
}
