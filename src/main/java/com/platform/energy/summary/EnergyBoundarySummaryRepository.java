package com.platform.energy.summary;

import com.platform.energy.summary.EnergySummaryModels.BoundaryPolicyVersion;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static com.platform.energy.summary.EnergySummaryErrors.POLICY_REQUIRED;
import static com.platform.energy.summary.EnergySummaryErrors.error;

@Repository
/** 保存计量边界汇总口径的稳定身份和不可覆盖版本。 */
public class EnergyBoundarySummaryRepository {
    private final JdbcTemplate jdbc;

    public EnergyBoundarySummaryRepository(
            @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String findPolicyIdForUpdate(
            String buildingId, String boundaryId, String energyItemCode) {
        List<String> values = jdbc.query("""
                SELECT policy_id FROM biz_energy_boundary_summary_policy
                WHERE building_id=? AND metering_boundary_id=? AND energy_item_code=?
                FOR UPDATE
                """, (rs, row) -> rs.getString(1), buildingId, boundaryId, energyItemCode);
        return values.isEmpty() ? null : values.getFirst();
    }

    public void insertIdentity(
            String policyId, String buildingId, String boundaryId,
            String energyItemCode, long createdBy, LocalDateTime createdAt) {
        jdbc.update("""
                INSERT INTO biz_energy_boundary_summary_policy
                (policy_id,building_id,metering_boundary_id,energy_item_code,created_by,created_at)
                VALUES (?,?,?,?,?,?)
                """, policyId, buildingId, boundaryId, energyItemCode, createdBy,
                Timestamp.valueOf(createdAt));
    }

    public int nextVersion(String policyId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no),0)+1
                FROM biz_energy_boundary_summary_policy_version WHERE policy_id=?
                """, Integer.class, policyId);
    }

    public void insertVersion(BoundaryPolicyVersion value) {
        jdbc.update("""
                INSERT INTO biz_energy_boundary_summary_policy_version
                (version_id,policy_id,version_no,aggregation_mode,status,source_type,
                 evidence_reference,effective_from,effective_to,config_revision,
                 created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, value.versionId(), value.policyId(), value.versionNo(),
                value.aggregationMode(), value.status(), value.sourceType(),
                value.evidenceReference(), timestamp(value.effectiveFrom()),
                timestamp(value.effectiveTo()), value.configRevision(), value.createdBy(),
                timestamp(value.createdAt()));
    }

    public BoundaryPolicyVersion findVersion(String versionId) {
        return one("""
                SELECT v.*,p.building_id,p.metering_boundary_id,p.energy_item_code
                FROM biz_energy_boundary_summary_policy_version v
                JOIN biz_energy_boundary_summary_policy p ON p.policy_id=v.policy_id
                WHERE v.version_id=?
                """, versionId);
    }

    public BoundaryPolicyVersion findApproved(
            String buildingId, String boundaryId, String energyItemCode,
            LocalDateTime effectiveAt) {
        List<BoundaryPolicyVersion> values = jdbc.query("""
                SELECT v.*,p.building_id,p.metering_boundary_id,p.energy_item_code
                FROM biz_energy_boundary_summary_policy_version v
                JOIN biz_energy_boundary_summary_policy p ON p.policy_id=v.policy_id
                WHERE p.building_id=? AND p.metering_boundary_id=? AND p.energy_item_code=?
                  AND v.status='APPROVED' AND v.effective_from<=?
                  AND (v.effective_to IS NULL OR v.effective_to>?)
                ORDER BY v.effective_from DESC
                """, this::row, buildingId, boundaryId, energyItemCode,
                timestamp(effectiveAt), timestamp(effectiveAt));
        if (values.size() > 1) {
            throw error(409, POLICY_REQUIRED, "计量边界汇总口径存在重叠版本");
        }
        return values.isEmpty() ? null : values.getFirst();
    }

    public void closeApproved(String policyId, String excludedVersionId, LocalDateTime effectiveTo) {
        jdbc.update("""
                UPDATE biz_energy_boundary_summary_policy_version
                SET effective_to=?
                WHERE policy_id=? AND status='APPROVED' AND version_id<>?
                  AND effective_to IS NULL
                """, timestamp(effectiveTo), policyId, excludedVersionId);
    }

    public int approve(
            String versionId, int expectedRevision, long reviewerId,
            LocalDateTime approvedAt, String reviewComment) {
        return jdbc.update("""
                UPDATE biz_energy_boundary_summary_policy_version
                SET status='APPROVED',config_revision=config_revision+1,
                    approved_by=?,approved_at=?,review_comment=?
                WHERE version_id=? AND status='PENDING_EXPERT' AND config_revision=?
                """, reviewerId, timestamp(approvedAt), reviewComment,
                versionId, expectedRevision);
    }

    private BoundaryPolicyVersion one(String sql, Object... args) {
        List<BoundaryPolicyVersion> values = jdbc.query(sql, this::row, args);
        return values.isEmpty() ? null : values.getFirst();
    }

    private BoundaryPolicyVersion row(ResultSet rs, int row) throws SQLException {
        return new BoundaryPolicyVersion(rs.getString("policy_id"), rs.getString("version_id"),
                rs.getInt("version_no"), rs.getString("building_id"),
                rs.getString("metering_boundary_id"), rs.getString("energy_item_code"),
                rs.getString("aggregation_mode"), rs.getString("status"),
                rs.getString("source_type"), rs.getString("evidence_reference"),
                local(rs, "effective_from"), local(rs, "effective_to"),
                rs.getInt("config_revision"), rs.getLong("created_by"),
                local(rs, "created_at"), nullableLong(rs, "approved_by"),
                local(rs, "approved_at"), rs.getString("review_comment"));
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
