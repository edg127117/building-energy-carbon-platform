package com.platform.energy.period;

import com.platform.energy.period.EnergyPeriodModels.ExceptionPolicyVersion;
import com.platform.energy.period.EnergyPeriodModels.PeriodPolicyVersion;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
/** 持久化建筑周期口径和封账例外的不可覆盖版本。 */
public class EnergyPeriodGovernanceRepository {
    private final JdbcTemplate jdbc;

    public EnergyPeriodGovernanceRepository(
            @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String findPeriodPolicyId(String buildingId) {
        return one("SELECT policy_id FROM biz_energy_period_policy WHERE building_id=?",
                (rs, row) -> rs.getString(1), buildingId);
    }

    public void insertPeriodPolicyIdentity(
            String policyId, String buildingId, long userId, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO biz_energy_period_policy
                (policy_id,building_id,created_by,created_at) VALUES (?,?,?,?)
                """, policyId, buildingId, userId, timestamp(now));
    }

    public int nextPeriodPolicyVersion(String policyId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no),0)+1 FROM biz_energy_period_policy_version
                WHERE policy_id=?
                """, Integer.class, policyId);
    }

    public void insertPeriodPolicyVersion(PeriodPolicyVersion value) {
        jdbc.update("""
                INSERT INTO biz_energy_period_policy_version
                (version_id,policy_id,version_no,timezone_id,closing_delay_hours,lock_mode,
                 status,source_type,evidence_reference,effective_from,effective_to,config_revision,
                 created_by,created_at)
                VALUES (?,?,?,?,?,?,'PENDING_REVIEW',?,?,?,?,0,?,?)
                """, value.versionId(), value.policyId(), value.versionNo(), value.timezoneId(),
                value.closingDelayHours(), value.lockMode(), value.sourceType(),
                value.evidenceReference(), timestamp(value.effectiveFrom()),
                timestamp(value.effectiveTo()), value.createdBy(), timestamp(value.createdAt()));
    }

    public PeriodPolicyVersion findPeriodPolicyVersion(String versionId) {
        return one(periodPolicySelect() + " WHERE v.version_id=?", this::periodPolicy, versionId);
    }

    public PeriodPolicyVersion findEffectivePeriodPolicy(
            String buildingId, LocalDateTime effectiveAt) {
        return one(periodPolicySelect() + """
                 WHERE p.building_id=? AND v.status='APPROVED' AND v.effective_from<=?
                   AND (v.effective_to IS NULL OR v.effective_to>?)
                """, this::periodPolicy, buildingId, timestamp(effectiveAt), timestamp(effectiveAt));
    }

    public PeriodPolicyVersion findLatestApprovedPeriodPolicy(String policyId) {
        return one(periodPolicySelect() + """
                 WHERE p.policy_id=? AND v.status='APPROVED' ORDER BY v.version_no DESC LIMIT 1
                """, this::periodPolicy, policyId);
    }

    public int approvePeriodPolicy(
            String versionId, int revision, long reviewerId, LocalDateTime now,
            String reviewComment) {
        return jdbc.update("""
                UPDATE biz_energy_period_policy_version
                SET status='APPROVED',approved_by=?,approved_at=?,review_comment=?,
                    config_revision=config_revision+1
                WHERE version_id=? AND status='PENDING_REVIEW' AND config_revision=?
                """, reviewerId, timestamp(now), reviewComment, versionId, revision);
    }

    public void closePeriodPolicy(String versionId, LocalDateTime effectiveTo) {
        jdbc.update("""
                UPDATE biz_energy_period_policy_version
                SET status='DISABLED',effective_to=?,config_revision=config_revision+1
                WHERE version_id=? AND status='APPROVED'
                """, timestamp(effectiveTo), versionId);
    }

    public String findExceptionPolicyId(
            String buildingId, String issueCode, String scope) {
        return one("""
                SELECT policy_id FROM biz_energy_lock_exception_policy
                WHERE building_id=? AND issue_code=? AND applicable_scope=?
                """, (rs, row) -> rs.getString(1), buildingId, issueCode, scope);
    }

    public void insertExceptionPolicyIdentity(
            String policyId, String buildingId, String issueCode, String scope,
            long userId, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO biz_energy_lock_exception_policy
                (policy_id,building_id,issue_code,applicable_scope,created_by,created_at)
                VALUES (?,?,?,?,?,?)
                """, policyId, buildingId, issueCode, scope, userId, timestamp(now));
    }

    public int nextExceptionPolicyVersion(String policyId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no),0)+1
                FROM biz_energy_lock_exception_policy_version WHERE policy_id=?
                """, Integer.class, policyId);
    }

    public void insertExceptionPolicyVersion(ExceptionPolicyVersion value) {
        jdbc.update("""
                INSERT INTO biz_energy_lock_exception_policy_version
                (version_id,policy_id,version_no,severity,lock_action,maximum_affected_count,
                 maximum_affected_ratio,minimum_coverage_ratio,requires_approval,
                 required_evidence,status,source_type,evidence_reference,effective_from,
                 effective_to,config_revision,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING_REVIEW',?,?,?,?,0,?,?)
                """, value.versionId(), value.policyId(), value.versionNo(), value.severity(),
                value.lockAction(), value.maximumAffectedCount(), value.maximumAffectedRatio(),
                value.minimumCoverageRatio(), value.requiresApproval(), value.requiredEvidence(),
                value.sourceType(), value.evidenceReference(), timestamp(value.effectiveFrom()),
                timestamp(value.effectiveTo()), value.createdBy(), timestamp(value.createdAt()));
    }

    public ExceptionPolicyVersion findExceptionPolicyVersion(String versionId) {
        return one(exceptionPolicySelect() + " WHERE v.version_id=?",
                this::exceptionPolicy, versionId);
    }

    public ExceptionPolicyVersion findLatestApprovedExceptionPolicy(String policyId) {
        return one(exceptionPolicySelect() + """
                 WHERE p.policy_id=? AND v.status='APPROVED' ORDER BY v.version_no DESC LIMIT 1
                """, this::exceptionPolicy, policyId);
    }

    public List<ExceptionPolicyVersion> findEffectiveExceptionPolicies(
            String buildingId, List<String> issueCodes, LocalDateTime effectiveAt) {
        if (issueCodes.isEmpty()) return List.of();
        String placeholders = String.join(",", issueCodes.stream().map(value -> "?").toList());
        Object[] arguments = new Object[issueCodes.size() + 3];
        arguments[0] = buildingId;
        for (int index = 0; index < issueCodes.size(); index++) arguments[index + 1] = issueCodes.get(index);
        arguments[arguments.length - 2] = timestamp(effectiveAt);
        arguments[arguments.length - 1] = timestamp(effectiveAt);
        return jdbc.query(exceptionPolicySelect() + " WHERE p.building_id=? AND p.issue_code IN ("
                + placeholders + ") AND v.status='APPROVED' AND v.effective_from<=?"
                + " AND (v.effective_to IS NULL OR v.effective_to>?)", this::exceptionPolicy,
                arguments);
    }

    public int approveExceptionPolicy(
            String versionId, int revision, long reviewerId, LocalDateTime now,
            String reviewComment) {
        return jdbc.update("""
                UPDATE biz_energy_lock_exception_policy_version
                SET status='APPROVED',approved_by=?,approved_at=?,review_comment=?,
                    config_revision=config_revision+1
                WHERE version_id=? AND status='PENDING_REVIEW' AND config_revision=?
                """, reviewerId, timestamp(now), reviewComment, versionId, revision);
    }

    public void closeExceptionPolicy(String versionId, LocalDateTime effectiveTo) {
        jdbc.update("""
                UPDATE biz_energy_lock_exception_policy_version
                SET status='DISABLED',effective_to=?,config_revision=config_revision+1
                WHERE version_id=? AND status='APPROVED'
                """, timestamp(effectiveTo), versionId);
    }

    private String periodPolicySelect() {
        return """
                SELECT p.policy_id,p.building_id,v.version_id,v.version_no,v.timezone_id,
                       v.closing_delay_hours,v.lock_mode,v.status,v.source_type,
                       v.evidence_reference,v.effective_from,v.effective_to,v.config_revision,
                       v.created_by,v.created_at,v.approved_by,v.approved_at
                FROM biz_energy_period_policy p
                JOIN biz_energy_period_policy_version v ON v.policy_id=p.policy_id
                """;
    }

    private PeriodPolicyVersion periodPolicy(ResultSet rs, int row) throws SQLException {
        return new PeriodPolicyVersion(rs.getString("policy_id"), rs.getString("version_id"),
                rs.getInt("version_no"), rs.getString("building_id"),
                rs.getString("timezone_id"), rs.getInt("closing_delay_hours"),
                rs.getString("lock_mode"), rs.getString("status"), rs.getString("source_type"),
                rs.getString("evidence_reference"), local(rs, "effective_from"),
                local(rs, "effective_to"), rs.getInt("config_revision"),
                rs.getLong("created_by"), local(rs, "created_at"),
                nullableLong(rs, "approved_by"), local(rs, "approved_at"));
    }

    private String exceptionPolicySelect() {
        return """
                SELECT p.policy_id,p.building_id,p.issue_code,p.applicable_scope,
                       v.version_id,v.version_no,v.severity,v.lock_action,
                       v.maximum_affected_count,v.maximum_affected_ratio,
                       v.minimum_coverage_ratio,v.requires_approval,v.required_evidence,
                       v.status,v.source_type,v.evidence_reference,v.effective_from,v.effective_to,
                       v.config_revision,v.created_by,v.created_at,v.approved_by,v.approved_at
                FROM biz_energy_lock_exception_policy p
                JOIN biz_energy_lock_exception_policy_version v ON v.policy_id=p.policy_id
                """;
    }

    private ExceptionPolicyVersion exceptionPolicy(ResultSet rs, int row) throws SQLException {
        Integer maximumCount = (Integer) rs.getObject("maximum_affected_count");
        return new ExceptionPolicyVersion(rs.getString("policy_id"), rs.getString("version_id"),
                rs.getInt("version_no"), rs.getString("building_id"), rs.getString("issue_code"),
                rs.getString("severity"), rs.getString("lock_action"), maximumCount,
                rs.getBigDecimal("maximum_affected_ratio"), rs.getBigDecimal("minimum_coverage_ratio"),
                rs.getString("applicable_scope"), rs.getBoolean("requires_approval"),
                rs.getString("required_evidence"), rs.getString("status"),
                rs.getString("source_type"), rs.getString("evidence_reference"),
                local(rs, "effective_from"), local(rs, "effective_to"),
                rs.getInt("config_revision"), rs.getLong("created_by"), local(rs, "created_at"),
                nullableLong(rs, "approved_by"), local(rs, "approved_at"));
    }

    private <T> T one(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
        List<T> values = jdbc.query(sql, mapper, args);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime local(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
