package com.platform.audit.query;

import com.platform.audit.AuditSummarySanitizer;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 使用固定表与固定列定义访问单个权威审计源，所有用户输入均通过 JDBC 参数绑定。 */
final class JdbcAuditEvidenceProvider implements AuditEvidenceProvider {
    private final JdbcTemplate jdbc;
    private final AuditSummarySanitizer sanitizer;
    private final Definition definition;

    JdbcAuditEvidenceProvider(JdbcTemplate jdbc, AuditSummarySanitizer sanitizer, Definition definition) {
        this.jdbc = jdbc;
        this.sanitizer = sanitizer;
        this.definition = definition;
    }

    @Override
    public String sourceModule() {
        return definition.sourceModule();
    }

    @Override
    public List<AuditEvidenceRecord> query(
            AuditQueryFilter filter, Set<String> allowedBuildingIds,
            AuditQueryCursor cursor, int limit) {
        if (allowedBuildingIds != null && allowedBuildingIds.isEmpty()) return List.of();
        List<Object> arguments = new ArrayList<>();
        arguments.add(sourceModule());
        StringBuilder sql = new StringBuilder("SELECT ").append(definition.projection())
                .append(" FROM ").append(definition.table()).append(" WHERE 1=1");
        appendConditions(sql, arguments, filter, allowedBuildingIds, cursor);
        sql.append(" ORDER BY ").append(definition.operationTime()).append(" DESC,")
                .append(definition.auditId()).append(" DESC LIMIT ?");
        arguments.add(limit);
        return jdbc.query(sql.toString(), this::map, arguments.toArray());
    }

    @Override
    public long countUpTo(AuditQueryFilter filter, Set<String> allowedBuildingIds, long limit) {
        if (allowedBuildingIds != null && allowedBuildingIds.isEmpty()) return 0;
        List<Object> arguments = new ArrayList<>();
        StringBuilder inner = new StringBuilder("SELECT 1 FROM ").append(definition.table())
                .append(" WHERE 1=1");
        appendConditions(inner, arguments, filter, allowedBuildingIds, null);
        inner.append(" LIMIT ?");
        arguments.add(limit);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM (" + inner + ") bounded_audit_count",
                Long.class, arguments.toArray());
        return count == null ? 0 : count;
    }

    private void appendConditions(
            StringBuilder sql, List<Object> arguments, AuditQueryFilter filter,
            Set<String> allowedBuildingIds, AuditQueryCursor cursor) {
        if (allowedBuildingIds != null) {
            sql.append(" AND ").append(definition.buildingId()).append(" IN (")
                    .append("?,".repeat(allowedBuildingIds.size()));
            sql.setLength(sql.length() - 1);
            sql.append(')');
            arguments.addAll(allowedBuildingIds);
        }
        equal(sql, arguments, definition.operatorId(), filter.operatorId());
        equal(sql, arguments, definition.actorType(), filter.actorType());
        equal(sql, arguments, definition.actionType(), filter.actionType());
        equal(sql, arguments, definition.objectType(), filter.objectType());
        equal(sql, arguments, definition.objectId(), filter.objectId());
        equal(sql, arguments, definition.result(), filter.result());
        equal(sql, arguments, definition.reasonCode(), filter.reasonCode());
        equal(sql, arguments, definition.traceId(), filter.traceId());
        sql.append(" AND ").append(definition.operationTime()).append(">=? AND ")
                .append(definition.operationTime()).append("<?");
        arguments.add(Timestamp.from(filter.from()));
        arguments.add(Timestamp.from(filter.to()));
        if (cursor != null) appendCursor(sql, arguments, cursor);
    }

    private void appendCursor(StringBuilder sql, List<Object> arguments, AuditQueryCursor cursor) {
        sql.append(" AND (").append(definition.operationTime()).append("<? OR (")
                .append(definition.operationTime()).append("=? AND ")
                .append(definition.auditId()).append("<?)");
        Timestamp time = Timestamp.from(cursor.operationTime());
        arguments.add(time);
        arguments.add(time);
        arguments.add(cursor.auditId());
        if (sourceModule().compareTo(cursor.sourceModule()) < 0) {
            sql.append(" OR (").append(definition.operationTime()).append("=? AND ")
                    .append(definition.auditId()).append("=?)");
            arguments.add(time);
            arguments.add(cursor.auditId());
        }
        sql.append(')');
    }

    private static void equal(StringBuilder sql, List<Object> arguments, String expression, Object value) {
        if (value == null || expression == null) return;
        sql.append(" AND ").append(expression).append("=?");
        arguments.add(value);
    }

    private AuditEvidenceRecord map(ResultSet rs, int row) throws SQLException {
        long rawOperatorId = rs.getLong("operator_id");
        Long operatorId = rs.wasNull() ? null : rawOperatorId;
        Timestamp operationTime = rs.getTimestamp("operation_time");
        return new AuditEvidenceRecord(
                rs.getString("audit_id"), rs.getString("source_module"), rs.getString("building_id"),
                rs.getString("actor_type"), operatorId, rs.getString("action_type"),
                rs.getString("object_type"), rs.getString("object_id"), rs.getString("version_id"),
                rs.getString("review_request_id"), sanitizer.sanitize(rs.getString("before_summary")),
                sanitizer.sanitize(rs.getString("after_summary")), rs.getString("result"),
                rs.getString("reason_code"), rs.getString("trace_id"), operationTime.toInstant(),
                rs.getString("environment_mode"), rs.getBoolean("self_approval_dev_mode"));
    }

    record Definition(
            String sourceModule, String table, String auditId, String buildingId,
            String actorType, String operatorId, String actionType, String objectType,
            String objectId, String versionId, String reviewRequestId, String beforeSummary,
            String afterSummary, String result, String reasonCode, String traceId,
            String operationTime, String environmentMode, String selfApprovalDevMode) {

        String projection() {
            return String.join(",",
                    auditId + " AS audit_id", "? AS source_module", buildingId + " AS building_id",
                    actorType + " AS actor_type", operatorId + " AS operator_id",
                    actionType + " AS action_type", objectType + " AS object_type",
                    objectId + " AS object_id", versionId + " AS version_id",
                    reviewRequestId + " AS review_request_id", beforeSummary + " AS before_summary",
                    afterSummary + " AS after_summary", result + " AS result",
                    reasonCode + " AS reason_code", traceId + " AS trace_id",
                    operationTime + " AS operation_time", environmentMode + " AS environment_mode",
                    selfApprovalDevMode + " AS self_approval_dev_mode");
        }
    }
}
