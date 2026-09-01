package com.platform.energy.aggregation;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
/** 持久化不可覆盖的计量事件、活动修正和瞬时量积分策略版本。 */
public class EnergyAggregationGovernanceRepository {
    private final JdbcTemplate jdbc;

    public EnergyAggregationGovernanceRepository(
            @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public EventIdentity findEventIdentity(String eventId) {
        return one("SELECT * FROM biz_energy_meter_event WHERE event_id=?",
                EnergyAggregationGovernanceRepository::eventIdentity, eventId);
    }

    public void insertEventIdentity(EventIdentity value) {
        jdbc.update("""
                INSERT INTO biz_energy_meter_event
                (event_id,building_id,meter_point_id,event_type,created_by,created_at)
                VALUES (?,?,?,?,?,?)
                """, value.eventId(), value.buildingId(), value.meterPointId(), value.eventType(),
                value.createdBy(), timestamp(value.createdAt()));
    }

    public int nextEventVersion(String eventId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no),0)+1 FROM biz_energy_meter_event_version
                WHERE event_id=?
                """, Integer.class, eventId);
    }

    public void insertEventVersion(EventVersionRow value) {
        jdbc.update("""
                INSERT INTO biz_energy_meter_event_version
                (event_version_id,event_id,version_no,occurred_at,pre_event_reading,
                 post_event_reading,rollover_modulus,old_meter_id,new_meter_id,
                 relation_version_before,relation_version_after,status,source_type,
                 evidence_reference,simulation_flag,config_revision,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'PENDING_REVIEW',?,?,?,0,?,?)
                """, value.eventVersionId(), value.eventId(), value.versionNo(),
                timestamp(value.occurredAt()), value.preEventReading(), value.postEventReading(),
                value.rolloverModulus(), value.oldMeterId(), value.newMeterId(),
                value.relationVersionBefore(), value.relationVersionAfter(), value.sourceType(),
                value.evidenceReference(), value.simulationFlag(), value.createdBy(),
                timestamp(value.createdAt()));
    }

    public EventVersionRow findEventVersion(String versionId) {
        return one(eventSelect() + " WHERE v.event_version_id=?",
                EnergyAggregationGovernanceRepository::eventVersion, versionId);
    }

    public List<EventVersionRow> listEventVersions(String buildingId, String pointId) {
        return jdbc.query(eventSelect() + """
                 WHERE e.building_id=? AND e.meter_point_id=?
                 ORDER BY v.occurred_at,v.version_no
                """, EnergyAggregationGovernanceRepository::eventVersion, buildingId, pointId);
    }

    public List<EventVersionRow> listApprovedEvents(
            String buildingId, String pointId, LocalDateTime from, LocalDateTime to) {
        return jdbc.query(eventSelect() + """
                 WHERE e.building_id=? AND e.meter_point_id=? AND v.status='APPROVED'
                   AND v.occurred_at>=? AND v.occurred_at<?
                 ORDER BY v.occurred_at,v.version_no
                """, EnergyAggregationGovernanceRepository::eventVersion, buildingId, pointId,
                timestamp(from), timestamp(to));
    }

    public int approveEvent(String versionId, int revision, long reviewerId,
                            LocalDateTime approvedAt, String reviewComment) {
        return jdbc.update("""
                UPDATE biz_energy_meter_event_version
                SET status='APPROVED',approved_by=?,approved_at=?,review_comment=?,
                    config_revision=config_revision+1
                WHERE event_version_id=? AND status='PENDING_REVIEW' AND config_revision=?
                """, reviewerId, timestamp(approvedAt), reviewComment, versionId, revision);
    }

    public EventVersionRow findLatestApprovedEvent(String eventId) {
        return one(eventSelect() + """
                 WHERE e.event_id=? AND v.status='APPROVED' ORDER BY v.version_no DESC LIMIT 1
                """, EnergyAggregationGovernanceRepository::eventVersion, eventId);
    }

    public void disableEventVersion(String versionId) {
        jdbc.update("""
                UPDATE biz_energy_meter_event_version SET status='DISABLED',
                    config_revision=config_revision+1 WHERE event_version_id=? AND status='APPROVED'
                """, versionId);
    }

    public CorrectionIdentity findCorrectionIdentity(String correctionId) {
        return one("SELECT * FROM biz_energy_activity_correction WHERE correction_id=?",
                EnergyAggregationGovernanceRepository::correctionIdentity, correctionId);
    }

    public void insertCorrectionIdentity(CorrectionIdentity value) {
        jdbc.update("""
                INSERT INTO biz_energy_activity_correction
                (correction_id,building_id,meter_point_id,original_fact_identity,created_by,created_at)
                VALUES (?,?,?,?,?,?)
                """, value.correctionId(), value.buildingId(), value.meterPointId(),
                value.originalFactIdentity(), value.createdBy(), timestamp(value.createdAt()));
    }

    public int nextCorrectionVersion(String correctionId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no),0)+1 FROM biz_energy_activity_correction_version
                WHERE correction_id=?
                """, Integer.class, correctionId);
    }

    public void insertCorrectionVersion(CorrectionVersionRow value) {
        jdbc.update("""
                INSERT INTO biz_energy_activity_correction_version
                (correction_version_id,correction_id,version_no,original_value,corrected_value,
                 correction_reason,status,source_type,evidence_reference,quality_gate_passed,
                 quality_policy_version,config_revision,created_by,created_at)
                VALUES (?,?,?,?,?,?,'PENDING_REVIEW',?,?,0,'PENDING',0,?,?)
                """, value.correctionVersionId(), value.correctionId(), value.versionNo(),
                value.originalValue(), value.correctedValue(), value.correctionReason(),
                value.sourceType(), value.evidenceReference(), value.createdBy(),
                timestamp(value.createdAt()));
    }

    public CorrectionVersionRow findCorrectionVersion(String versionId) {
        return one(correctionSelect() + " WHERE v.correction_version_id=?",
                EnergyAggregationGovernanceRepository::correctionVersion, versionId);
    }

    public List<CorrectionVersionRow> listCorrectionVersions(String buildingId, String pointId) {
        return jdbc.query(correctionSelect() + """
                 WHERE c.building_id=? AND c.meter_point_id=?
                 ORDER BY c.original_fact_identity,v.version_no
                """, EnergyAggregationGovernanceRepository::correctionVersion, buildingId, pointId);
    }

    public List<CorrectionVersionRow> listApprovedCorrections(String buildingId, String pointId) {
        return jdbc.query(correctionSelect() + """
                 WHERE c.building_id=? AND c.meter_point_id=? AND v.status='APPROVED'
                 ORDER BY c.original_fact_identity,v.version_no
                """, EnergyAggregationGovernanceRepository::correctionVersion, buildingId, pointId);
    }

    public CorrectionVersionRow findLatestApprovedCorrection(String correctionId) {
        return one(correctionSelect() + """
                 WHERE c.correction_id=? AND v.status='APPROVED'
                 ORDER BY v.version_no DESC LIMIT 1
                """, EnergyAggregationGovernanceRepository::correctionVersion, correctionId);
    }

    public void disableCorrectionVersion(String versionId) {
        jdbc.update("""
                UPDATE biz_energy_activity_correction_version SET status='DISABLED',
                    config_revision=config_revision+1
                WHERE correction_version_id=? AND status='APPROVED'
                """, versionId);
    }

    public int approveCorrection(String versionId, int revision, long reviewerId,
                                 LocalDateTime approvedAt, String reviewComment,
                                 String qualityPolicyVersion) {
        return jdbc.update("""
                UPDATE biz_energy_activity_correction_version
                SET status='APPROVED',quality_gate_passed=1,quality_policy_version=?,
                    approved_by=?,approved_at=?,review_comment=?,config_revision=config_revision+1
                WHERE correction_version_id=? AND status='PENDING_REVIEW' AND config_revision=?
                """, qualityPolicyVersion, reviewerId, timestamp(approvedAt), reviewComment,
                versionId, revision);
    }

    public PolicyIdentity findPolicyIdentity(String buildingId, String pointId) {
        return one("""
                SELECT * FROM biz_energy_integration_policy
                WHERE building_id=? AND meter_point_id=?
                """, EnergyAggregationGovernanceRepository::policyIdentity, buildingId, pointId);
    }

    public void insertPolicyIdentity(PolicyIdentity value) {
        jdbc.update("""
                INSERT INTO biz_energy_integration_policy
                (policy_id,building_id,meter_point_id,created_by,created_at) VALUES (?,?,?,?,?)
                """, value.policyId(), value.buildingId(), value.meterPointId(),
                value.createdBy(), timestamp(value.createdAt()));
    }

    public int nextPolicyVersion(String policyId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no),0)+1 FROM biz_energy_integration_policy_version
                WHERE policy_id=?
                """, Integer.class, policyId);
    }

    public void insertPolicyVersion(PolicyVersionRow value) {
        jdbc.update("""
                INSERT INTO biz_energy_integration_policy_version
                (policy_version_id,policy_id,version_no,integration_method,maximum_gap_seconds,
                 minimum_coverage_ratio,boundary_handling,status,source_type,evidence_reference,
                 effective_from,effective_to,config_revision,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,'PENDING_REVIEW',?,?,?,?,0,?,?)
                """, value.policyVersionId(), value.policyId(), value.versionNo(),
                value.integrationMethod(), value.maximumGapSeconds(), value.minimumCoverageRatio(),
                value.boundaryHandling(), value.sourceType(), value.evidenceReference(),
                timestamp(value.effectiveFrom()), timestamp(value.effectiveTo()), value.createdBy(),
                timestamp(value.createdAt()));
    }

    public PolicyVersionRow findPolicyVersion(String versionId) {
        return one(policySelect() + " WHERE v.policy_version_id=?",
                EnergyAggregationGovernanceRepository::policyVersion, versionId);
    }

    public List<PolicyVersionRow> listPolicyVersions(String buildingId, String pointId) {
        return jdbc.query(policySelect() + """
                 WHERE p.building_id=? AND p.meter_point_id=? ORDER BY v.version_no DESC
                """, EnergyAggregationGovernanceRepository::policyVersion, buildingId, pointId);
    }

    public PolicyVersionRow findEffectivePolicy(
            String buildingId, String pointId, LocalDateTime effectiveAt) {
        return one(policySelect() + """
                 WHERE p.building_id=? AND p.meter_point_id=? AND v.status='APPROVED'
                   AND v.effective_from<=? AND (v.effective_to IS NULL OR v.effective_to>?)
                 ORDER BY v.version_no DESC LIMIT 1
                """, EnergyAggregationGovernanceRepository::policyVersion, buildingId, pointId,
                timestamp(effectiveAt), timestamp(effectiveAt));
    }

    public PolicyVersionRow findLatestApprovedPolicy(String policyId) {
        return one(policySelect() + """
                 WHERE p.policy_id=? AND v.status='APPROVED' ORDER BY v.version_no DESC LIMIT 1
                """, EnergyAggregationGovernanceRepository::policyVersion, policyId);
    }

    public void closePolicyVersion(String versionId, LocalDateTime effectiveTo) {
        jdbc.update("""
                UPDATE biz_energy_integration_policy_version SET effective_to=?
                WHERE policy_version_id=?
                """, timestamp(effectiveTo), versionId);
    }

    public int approvePolicy(String versionId, int revision, long reviewerId,
                             LocalDateTime approvedAt, String reviewComment) {
        return jdbc.update("""
                UPDATE biz_energy_integration_policy_version
                SET status='APPROVED',approved_by=?,approved_at=?,review_comment=?,
                    config_revision=config_revision+1
                WHERE policy_version_id=? AND status='PENDING_REVIEW' AND config_revision=?
                """, reviewerId, timestamp(approvedAt), reviewComment, versionId, revision);
    }

    private static String eventSelect() {
        return """
                SELECT e.event_id,e.building_id,e.meter_point_id,e.event_type,v.*
                FROM biz_energy_meter_event e JOIN biz_energy_meter_event_version v
                  ON v.event_id=e.event_id
                """;
    }

    private static String correctionSelect() {
        return """
                SELECT c.correction_id,c.building_id,c.meter_point_id,c.original_fact_identity,v.*
                FROM biz_energy_activity_correction c JOIN biz_energy_activity_correction_version v
                  ON v.correction_id=c.correction_id
                """;
    }

    private static String policySelect() {
        return """
                SELECT p.policy_id,p.building_id,p.meter_point_id,v.*
                FROM biz_energy_integration_policy p JOIN biz_energy_integration_policy_version v
                  ON v.policy_id=p.policy_id
                """;
    }

    private <T> T one(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
        List<T> values = jdbc.query(sql, mapper, args);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static EventIdentity eventIdentity(ResultSet rs, int row) throws SQLException {
        return new EventIdentity(rs.getString("event_id"), rs.getString("building_id"),
                rs.getString("meter_point_id"), rs.getString("event_type"),
                rs.getLong("created_by"), time(rs, "created_at"));
    }

    private static EventVersionRow eventVersion(ResultSet rs, int row) throws SQLException {
        return new EventVersionRow(rs.getString("event_id"), rs.getString("event_version_id"),
                rs.getInt("version_no"), rs.getString("building_id"), rs.getString("meter_point_id"),
                rs.getString("event_type"), time(rs, "occurred_at"),
                rs.getBigDecimal("pre_event_reading"), rs.getBigDecimal("post_event_reading"),
                rs.getBigDecimal("rollover_modulus"), rs.getString("old_meter_id"),
                rs.getString("new_meter_id"), rs.getString("relation_version_before"),
                rs.getString("relation_version_after"), rs.getString("status"),
                rs.getString("source_type"), rs.getString("evidence_reference"),
                rs.getBoolean("simulation_flag"), rs.getInt("config_revision"),
                rs.getLong("created_by"), time(rs, "created_at"), nullableLong(rs, "approved_by"),
                time(rs, "approved_at"), rs.getString("review_comment"));
    }

    private static CorrectionIdentity correctionIdentity(ResultSet rs, int row) throws SQLException {
        return new CorrectionIdentity(rs.getString("correction_id"), rs.getString("building_id"),
                rs.getString("meter_point_id"), rs.getString("original_fact_identity"),
                rs.getLong("created_by"), time(rs, "created_at"));
    }

    private static CorrectionVersionRow correctionVersion(ResultSet rs, int row) throws SQLException {
        return new CorrectionVersionRow(rs.getString("correction_id"),
                rs.getString("correction_version_id"), rs.getInt("version_no"),
                rs.getString("building_id"), rs.getString("meter_point_id"),
                rs.getString("original_fact_identity"), rs.getBigDecimal("original_value"),
                rs.getBigDecimal("corrected_value"), rs.getString("correction_reason"),
                rs.getString("status"), rs.getString("source_type"),
                rs.getString("evidence_reference"), rs.getBoolean("quality_gate_passed"),
                rs.getString("quality_policy_version"), rs.getInt("config_revision"),
                rs.getLong("created_by"), time(rs, "created_at"), nullableLong(rs, "approved_by"),
                time(rs, "approved_at"), rs.getString("review_comment"));
    }

    private static PolicyIdentity policyIdentity(ResultSet rs, int row) throws SQLException {
        return new PolicyIdentity(rs.getString("policy_id"), rs.getString("building_id"),
                rs.getString("meter_point_id"), rs.getLong("created_by"), time(rs, "created_at"));
    }

    private static PolicyVersionRow policyVersion(ResultSet rs, int row) throws SQLException {
        return new PolicyVersionRow(rs.getString("policy_id"), rs.getString("policy_version_id"),
                rs.getInt("version_no"), rs.getString("building_id"), rs.getString("meter_point_id"),
                rs.getString("integration_method"), rs.getLong("maximum_gap_seconds"),
                rs.getBigDecimal("minimum_coverage_ratio"), rs.getString("boundary_handling"),
                rs.getString("status"), rs.getString("source_type"),
                rs.getString("evidence_reference"), time(rs, "effective_from"),
                time(rs, "effective_to"), rs.getInt("config_revision"), rs.getLong("created_by"),
                time(rs, "created_at"), nullableLong(rs, "approved_by"),
                time(rs, "approved_at"), rs.getString("review_comment"));
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    public record EventIdentity(
            String eventId, String buildingId, String meterPointId, String eventType,
            long createdBy, LocalDateTime createdAt) {
    }

    public record EventVersionRow(
            String eventId, String eventVersionId, int versionNo, String buildingId,
            String meterPointId, String eventType, LocalDateTime occurredAt,
            BigDecimal preEventReading, BigDecimal postEventReading, BigDecimal rolloverModulus,
            String oldMeterId, String newMeterId, String relationVersionBefore,
            String relationVersionAfter, String status, String sourceType,
            String evidenceReference, boolean simulationFlag, int configRevision,
            long createdBy, LocalDateTime createdAt, Long approvedBy,
            LocalDateTime approvedAt, String reviewComment) {
    }

    public record CorrectionIdentity(
            String correctionId, String buildingId, String meterPointId,
            String originalFactIdentity, long createdBy, LocalDateTime createdAt) {
    }

    public record CorrectionVersionRow(
            String correctionId, String correctionVersionId, int versionNo, String buildingId,
            String meterPointId, String originalFactIdentity, BigDecimal originalValue,
            BigDecimal correctedValue, String correctionReason, String status, String sourceType,
            String evidenceReference, boolean qualityGatePassed, String qualityPolicyVersion,
            int configRevision, long createdBy, LocalDateTime createdAt, Long approvedBy,
            LocalDateTime approvedAt, String reviewComment) {
    }

    public record PolicyIdentity(
            String policyId, String buildingId, String meterPointId,
            long createdBy, LocalDateTime createdAt) {
    }

    public record PolicyVersionRow(
            String policyId, String policyVersionId, int versionNo, String buildingId,
            String meterPointId, String integrationMethod, long maximumGapSeconds,
            BigDecimal minimumCoverageRatio, String boundaryHandling, String status,
            String sourceType, String evidenceReference, LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo, int configRevision, long createdBy,
            LocalDateTime createdAt, Long approvedBy, LocalDateTime approvedAt,
            String reviewComment) {
    }
}
