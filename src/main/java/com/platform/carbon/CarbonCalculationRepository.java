package com.platform.carbon;

import com.platform.carbon.CarbonModels.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
/** 保存计算批次、明细、失败段和汇总；计算过程本身不持有数据库事务。 */
class CarbonCalculationRepository {
    private final JdbcTemplate jdbc;

    CarbonCalculationRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    CalculationBatch findBatch(String batchId) {
        return one("SELECT * FROM biz_carbon_calculation_batch WHERE calculation_batch_id=?",
                CarbonCalculationRepository::batch, batchId);
    }

    CalculationBatch findByIdempotency(String buildingId, String idempotencyKey) {
        return one("""
                SELECT * FROM biz_carbon_calculation_batch
                WHERE building_id=? AND idempotency_key=?
                """, CarbonCalculationRepository::batch, buildingId, idempotencyKey);
    }

    CalculationBatch findCurrentFormal(String buildingId, PeriodType periodType,
                                       Instant start, Instant end) {
        return one("""
                SELECT b.* FROM biz_carbon_calculation_batch b
                WHERE b.building_id=? AND b.period_type=? AND b.period_start=? AND b.period_end=?
                  AND b.result_nature='FORMAL' AND b.publication_status IN ('DIRECT','PUBLISHED')
                  AND b.status='COMPLETED_COMPLETE'
                  AND NOT EXISTS (
                    SELECT 1 FROM biz_carbon_result_relation r
                    WHERE r.old_calculation_batch_id=b.calculation_batch_id
                      AND r.relation_status='SUPERSEDED')
                  AND NOT EXISTS (
                    SELECT 1 FROM biz_carbon_result_relation candidate
                    WHERE candidate.new_calculation_batch_id=b.calculation_batch_id
                      AND candidate.relation_status='CANDIDATE')
                ORDER BY b.completed_at DESC LIMIT 1
                """, CarbonCalculationRepository::batch, buildingId, periodType.name(),
                timestamp(start), timestamp(end));
    }

    void insertBatch(CalculationBatch value) {
        jdbc.update("""
                INSERT INTO biz_carbon_calculation_batch
                (calculation_batch_id,building_id,period_type,period_start,period_end,timezone_id,
                 result_nature,publication_status,status,idempotency_key,request_hash,active_lock_key,
                 rounding_policy_version_id,supersedes_calculation_batch_id,started_at,deadline_at,
                 snapshot_count,detail_count,slow_calculation,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, value.batchId(), value.buildingId(), value.periodType().name(),
                timestamp(value.periodStart()), timestamp(value.periodEnd()), value.timezoneId(),
                value.resultNature().name(), value.publicationStatus(), value.status(), value.idempotencyKey(),
                value.requestHash(), value.activeLockKey(), value.roundingPolicyVersionId(),
                value.supersedesBatchId(), timestamp(value.startedAt()), timestamp(value.deadlineAt()),
                value.snapshotCount(), value.detailCount(), value.slowCalculation(),
                value.createdBy(), timestamp(value.createdAt()));
    }

    int timeoutBatch(String batchId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE biz_carbon_calculation_batch
                SET status='FAILED_TIMEOUT',active_lock_key=NULL,completed_at=?,
                    safe_error_code='CARBON_CALCULATION_TIMEOUT',
                    safe_error_message='计算超过截止时间'
                WHERE calculation_batch_id=? AND status='CALCULATING' AND deadline_at<?
                """, timestamp(now), batchId, timestamp(now));
    }

    void insertItems(String batchId, List<CalculatedItem> values) {
        jdbc.batchUpdate("""
                INSERT INTO biz_carbon_calculation_item
                (calculation_item_id,calculation_batch_id,activity_snapshot_id,
                 activity_evidence_hash,activity_period_start,activity_period_end,energy_item_code,
                 scope_type,activity_quantity,activity_unit_code,factor_version_id,
                 formula_version_id,gwp_version_id,raw_emission_kg_co2e,
                 final_emission_kg_co2e,match_reason,evidence_json,evidence_hash)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, values, values.size(), (statement, value) -> {
                    statement.setString(1, id());
                    statement.setString(2, batchId);
                    statement.setString(3, value.activity().snapshotId());
                    statement.setString(4, value.activity().evidenceHash());
                    statement.setTimestamp(5, timestamp(value.activity().startInclusive()));
                    statement.setTimestamp(6, timestamp(value.activity().endExclusive()));
                    statement.setString(7, value.activity().energyItemCode());
                    statement.setString(8, value.factor().scopeType().name());
                    statement.setBigDecimal(9, value.activity().quantity());
                    statement.setString(10, value.activity().unitCode());
                    statement.setString(11, value.factor().factorVersionId());
                    statement.setString(12, value.formulaVersionId());
                    statement.setString(13, value.gwpVersionId());
                    statement.setBigDecimal(14, value.exactEmissionKgCo2e().setScale(18,
                            java.math.RoundingMode.HALF_UP));
                    statement.setBigDecimal(15, value.persistedEmissionKgCo2e());
                    statement.setString(16, value.matchReason());
                    statement.setString(17, value.evidenceJson());
                    statement.setString(18, value.evidenceHash());
                });
    }

    void insertFailures(String batchId, List<CalculationFailure> values) {
        jdbc.batchUpdate("""
                INSERT INTO biz_carbon_calculation_failure
                (failure_id,calculation_batch_id,activity_snapshot_id,energy_item_code,
                 activity_period_start,activity_period_end,safe_error_code,safe_error_message,
                 activity_evidence_hash) VALUES (?,?,?,?,?,?,?,?,?)
                """, values, values.size(), (statement, value) -> {
                    statement.setString(1, id());
                    statement.setString(2, batchId);
                    statement.setString(3, value.activity().snapshotId());
                    statement.setString(4, value.activity().energyItemCode());
                    statement.setTimestamp(5, timestamp(value.activity().startInclusive()));
                    statement.setTimestamp(6, timestamp(value.activity().endExclusive()));
                    statement.setString(7, value.errorCode());
                    statement.setString(8, value.errorMessage());
                    statement.setString(9, value.activity().evidenceHash());
                });
    }

    void insertSummaries(String batchId, List<SummaryMetric> values) {
        jdbc.batchUpdate("""
                INSERT INTO biz_carbon_calculation_summary
                (summary_id,calculation_batch_id,metric_code,dimension_code,raw_value,final_value,
                 unit_code,denominator_version_id,unavailable_reason,evidence_hash)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, values, values.size(), (statement, value) -> {
                    statement.setString(1, id());
                    statement.setString(2, batchId);
                    statement.setString(3, value.metricCode());
                    statement.setString(4, value.dimensionCode());
                    statement.setBigDecimal(5, value.rawValue());
                    statement.setBigDecimal(6, value.finalValue());
                    statement.setString(7, value.unitCode());
                    statement.setString(8, value.denominatorVersionId());
                    statement.setString(9, value.unavailableReason());
                    statement.setString(10, value.evidenceHash());
                });
    }

    int completeBatch(String batchId, String status, int snapshots, int details,
                      boolean slow, long durationMs, LocalDateTime completedAt) {
        return jdbc.update("""
                UPDATE biz_carbon_calculation_batch
                SET status=?,active_lock_key=NULL,snapshot_count=?,detail_count=?,
                    slow_calculation=?,duration_ms=?,completed_at=?
                WHERE calculation_batch_id=? AND status='CALCULATING'
                """, status, snapshots, details, slow, durationMs,
                timestamp(completedAt), batchId);
    }

    void failBatch(String batchId, String code, String message, LocalDateTime completedAt,
                   long durationMs) {
        jdbc.update("""
                UPDATE biz_carbon_calculation_batch
                SET status='FAILED',active_lock_key=NULL,safe_error_code=?,safe_error_message=?,
                    duration_ms=?,completed_at=?
                WHERE calculation_batch_id=? AND status='CALCULATING'
                """, code, message, durationMs, timestamp(completedAt), batchId);
    }

    List<StoredCalculationItem> listItems(String batchId) {
        return jdbc.query("""
                SELECT * FROM biz_carbon_calculation_item
                WHERE calculation_batch_id=? ORDER BY activity_period_start,energy_item_code
                """, CarbonCalculationRepository::item, batchId);
    }

    List<SummaryMetric> listSummaries(String batchId) {
        return jdbc.query("""
                SELECT * FROM biz_carbon_calculation_summary
                WHERE calculation_batch_id=? ORDER BY metric_code,dimension_code
                """, CarbonCalculationRepository::summary, batchId);
    }

    List<CalculationFailure> listFailures(String batchId) {
        return jdbc.query("""
                SELECT * FROM biz_carbon_calculation_failure
                WHERE calculation_batch_id=? ORDER BY activity_period_start,energy_item_code
                """, (rs, row) -> new CalculationFailure(
                        new ActivitySegment(rs.getString("activity_snapshot_id"), null, null,
                                instant(rs, "activity_period_start"),
                                instant(rs, "activity_period_end"), null,
                                rs.getString("energy_item_code"), null, null,
                                null, null, null, rs.getString("activity_evidence_hash")),
                        rs.getString("safe_error_code"), rs.getString("safe_error_message")),
                batchId);
    }

    CalculationDetail detail(String batchId) {
        CalculationBatch value = findBatch(batchId);
        return value == null ? null : new CalculationDetail(value, listItems(batchId),
                listFailures(batchId), listSummaries(batchId));
    }

    private static CalculationBatch batch(ResultSet rs, int row) throws SQLException {
        return new CalculationBatch(rs.getString("calculation_batch_id"),
                rs.getString("building_id"), PeriodType.valueOf(rs.getString("period_type")),
                instant(rs, "period_start"), instant(rs, "period_end"),
                rs.getString("timezone_id"), ResultNature.valueOf(rs.getString("result_nature")),
                rs.getString("publication_status"), rs.getString("status"), rs.getString("idempotency_key"),
                rs.getString("request_hash"), rs.getString("active_lock_key"),
                rs.getString("rounding_policy_version_id"),
                rs.getString("supersedes_calculation_batch_id"), local(rs, "started_at"),
                local(rs, "deadline_at"), local(rs, "completed_at"),
                nullableLong(rs, "duration_ms"), rs.getInt("snapshot_count"),
                rs.getInt("detail_count"), rs.getBoolean("slow_calculation"),
                rs.getString("safe_error_code"), rs.getString("safe_error_message"),
                rs.getLong("created_by"), local(rs, "created_at"));
    }

    private static StoredCalculationItem item(ResultSet rs, int row) throws SQLException {
        return new StoredCalculationItem(rs.getString("calculation_item_id"),
                rs.getString("calculation_batch_id"), rs.getString("activity_snapshot_id"),
                rs.getString("energy_item_code"), ScopeType.valueOf(rs.getString("scope_type")),
                rs.getBigDecimal("activity_quantity"), rs.getString("activity_unit_code"),
                rs.getString("factor_version_id"), rs.getString("formula_version_id"),
                rs.getString("gwp_version_id"), rs.getBigDecimal("final_emission_kg_co2e"),
                rs.getString("match_reason"), rs.getString("evidence_hash"));
    }

    private static SummaryMetric summary(ResultSet rs, int row) throws SQLException {
        return new SummaryMetric(rs.getString("metric_code"), rs.getString("dimension_code"),
                rs.getBigDecimal("raw_value"), rs.getBigDecimal("final_value"),
                rs.getString("unit_code"), rs.getString("denominator_version_id"),
                rs.getString("unavailable_reason"), rs.getString("evidence_hash"));
    }

    private <T> T one(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
        List<T> values = jdbc.query(sql, mapper, args);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
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
