package com.platform.audit.retention;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** 对固定权威表执行条件下推和保全复核，删除 SQL 不接受用户提供的表名或表达式。 */
final class JdbcAuditRetentionHandler implements AuditRetentionHandler {
    private final JdbcTemplate jdbc;
    private final Definition definition;

    JdbcAuditRetentionHandler(JdbcTemplate jdbc, Definition definition) {
        this.jdbc = jdbc;
        this.definition = definition;
    }

    @Override
    public String sourceModule() {
        return definition.sourceModule();
    }

    @Override
    public AuditRetentionPreview preview(AuditRetentionScope scope) {
        SqlFragment range = range(scope);
        long candidates = count(range.sql(), range.arguments());
        long held = count(range.sql() + " AND " + heldExists(), withSource(range.arguments()));
        long protectedCount = count(range.sql() + " AND " + pendingExists(), range.arguments());
        SqlFragment eligible = eligible(range);
        Long eligibleCount = jdbc.queryForObject("SELECT COUNT(*) " + eligible.sql(), Long.class,
                eligible.arguments().toArray());
        List<LocalDateTime[]> bounds = jdbc.query(
                "SELECT MIN(" + definition.operationTime() + "),MAX(" + definition.operationTime() + ") "
                        + eligible.sql(), (rs, row) -> new LocalDateTime[]{
                            local(rs.getTimestamp(1)), local(rs.getTimestamp(2))}, eligible.arguments().toArray());
        LocalDateTime[] values = bounds.getFirst();
        return new AuditRetentionPreview(candidates, eligibleCount == null ? 0 : eligibleCount,
                held, protectedCount, values[0], values[1]);
    }

    @Override
    public AuditCleanupBatch deleteNextBatch(AuditRetentionScope scope, int limit) {
        if (limit < 1) throw new IllegalArgumentException("清理批次必须大于0");
        SqlFragment eligible = eligible(range(scope));
        List<Object> selectArguments = new ArrayList<>(eligible.arguments());
        selectArguments.add(limit);
        List<Candidate> candidates = jdbc.query("SELECT " + definition.auditId() + ","
                        + definition.operationTime() + " " + eligible.sql() + " ORDER BY "
                        + definition.operationTime() + "," + definition.auditId() + " LIMIT ?",
                (rs, row) -> new Candidate(rs.getString(1), local(rs.getTimestamp(2))),
                selectArguments.toArray());
        if (candidates.isEmpty()) return AuditCleanupBatch.empty();

        String placeholders = String.join(",", java.util.Collections.nCopies(candidates.size(), "?"));
        List<Object> deleteArguments = new ArrayList<>();
        for (Candidate candidate : candidates) deleteArguments.add(candidate.auditId());
        deleteArguments.addAll(eligible.arguments());
        int deleted = jdbc.update("DELETE FROM " + definition.table() + " WHERE "
                        + definition.auditId() + " IN (" + placeholders + ") AND "
                        + eligible.sql().substring((" FROM " + definition.table() + " WHERE ").length()),
                deleteArguments.toArray());
        if (deleted != candidates.size()) {
            throw new IllegalStateException("清理候选在删除前发生变化");
        }
        return new AuditCleanupBatch(deleted, candidates.getFirst().operationTime(),
                candidates.getLast().operationTime(), digest(candidates));
    }

    private SqlFragment eligible(SqlFragment range) {
        List<Object> arguments = new ArrayList<>(range.arguments());
        arguments.add(sourceModule());
        return new SqlFragment(range.sql() + " AND NOT " + heldExists() + " AND NOT " + pendingExists(), arguments);
    }

    private SqlFragment range(AuditRetentionScope scope) {
        String table = definition.table();
        StringBuilder sql = new StringBuilder(" FROM ").append(table).append(" WHERE 1=1");
        List<Object> arguments = new ArrayList<>();
        equal(sql, arguments, definition.auditId(), scope.auditId());
        equal(sql, arguments, definition.buildingId(), scope.buildingId());
        equal(sql, arguments, definition.actionType(), scope.actionType());
        equal(sql, arguments, definition.objectType(), scope.objectType());
        equal(sql, arguments, definition.objectId(), scope.objectId());
        if (scope.fromTime() != null) {
            sql.append(" AND ").append(definition.operationTime()).append(">=?");
            arguments.add(Timestamp.valueOf(scope.fromTime()));
        }
        if (scope.toTime() != null) {
            sql.append(" AND ").append(definition.operationTime()).append("<?");
            arguments.add(Timestamp.valueOf(scope.toTime()));
        }
        return new SqlFragment(sql.toString(), arguments);
    }

    private String heldExists() {
        String table = definition.table();
        return "EXISTS (SELECT 1 FROM sys_audit_evidence_hold h WHERE h.status='ACTIVE'"
                + " AND (h.source_module IS NULL OR h.source_module=?)"
                + " AND (h.audit_id IS NULL OR h.audit_id=" + table + "." + definition.auditId() + ")"
                + " AND (h.building_id IS NULL OR h.building_id=" + table + "." + definition.buildingId() + ")"
                + " AND (h.action_type IS NULL OR h.action_type=" + table + "." + definition.actionType() + ")"
                + " AND (h.object_type IS NULL OR h.object_type=" + table + "." + definition.objectType() + ")"
                + " AND (h.object_id IS NULL OR h.object_id=" + table + "." + definition.objectId() + ")"
                + " AND (h.from_time IS NULL OR h.from_time<=" + table + "." + definition.operationTime() + ")"
                + " AND (h.to_time IS NULL OR h.to_time>" + table + "." + definition.operationTime() + "))";
    }

    private String pendingExists() {
        return definition.pendingProtectionSql() == null ? "FALSE" : definition.pendingProtectionSql();
    }

    private long count(String range, List<Object> arguments) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) " + range, Long.class, arguments.toArray());
        return count == null ? 0 : count;
    }

    private List<Object> withSource(List<Object> arguments) {
        List<Object> values = new ArrayList<>(arguments);
        values.add(sourceModule());
        return values;
    }

    private static void equal(StringBuilder sql, List<Object> arguments, String column, Object value) {
        if (value == null) return;
        sql.append(" AND ").append(column).append("=?");
        arguments.add(value);
    }

    private static LocalDateTime local(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private static String digest(List<Candidate> candidates) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Candidate candidate : candidates) {
                digest.update(candidate.auditId().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    record Definition(
            String sourceModule, String table, String auditId, String buildingId,
            String actionType, String objectType, String objectId, String operationTime,
            String pendingProtectionSql) {
    }

    private record SqlFragment(String sql, List<Object> arguments) {
    }

    private record Candidate(String auditId, LocalDateTime operationTime) {
    }
}
