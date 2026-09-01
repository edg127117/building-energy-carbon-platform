package com.platform.energy.period;

import com.platform.energy.period.EnergyPeriodModels.CurrentProjection;
import com.platform.energy.period.EnergyPeriodModels.LockRequest;
import com.platform.energy.period.EnergyPeriodModels.PeriodSnapshot;
import com.platform.energy.period.EnergyPeriodModels.RecalculationBatch;
import com.platform.energy.period.EnergyPeriodModels.RecalculationItem;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Repository
/** 保存低频工作流与结果索引；未完成重算批次关联的快照对业务查询不可见。 */
public class EnergyPeriodResultRepository {
    private final JdbcTemplate jdbc;

    public EnergyPeriodResultRepository(
            @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CurrentProjection findCurrent(String projectionId) {
        return one("SELECT * FROM biz_energy_period_result_current WHERE projection_id=?",
                this::current, projectionId);
    }

    public CurrentProjection findCurrent(
            String buildingId, String pointId, String periodType,
            Instant startInclusive, Instant endExclusive) {
        return one("""
                SELECT * FROM biz_energy_period_result_current
                WHERE building_id=? AND point_id=? AND period_type=?
                  AND period_start=? AND period_end=?
                """, this::current, buildingId, pointId, periodType,
                timestamp(startInclusive), timestamp(endExclusive));
    }

    public void insertCurrent(CurrentProjection value) {
        jdbc.update("""
                INSERT INTO biz_energy_period_result_current
                (projection_id,result_key,building_id,point_id,period_type,period_start,period_end,
                 timezone_id,period_policy_version_id,status,revision,result_nature,energy_item_code,
                 native_quantity,native_unit_code,tce_value,tce_unit_code,coverage_ratio,issue_codes,
                 evidence_json,evidence_hash,conversion_selection_json,activity_watermark,
                 calculated_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, currentArguments(value));
    }

    public int updateCurrent(CurrentProjection value, long expectedRevision) {
        return jdbc.update("""
                UPDATE biz_energy_period_result_current
                SET result_key=?,timezone_id=?,period_policy_version_id=?,status=?,revision=?,
                    result_nature=?,energy_item_code=?,native_quantity=?,native_unit_code=?,
                    tce_value=?,tce_unit_code=?,coverage_ratio=?,issue_codes=?,evidence_json=?,
                    evidence_hash=?,conversion_selection_json=?,activity_watermark=?,
                    calculated_at=?,updated_at=?
                WHERE projection_id=? AND revision=?
                """, value.resultKey(), value.timezoneId(), value.periodPolicyVersionId(),
                value.status(), value.revision(), value.resultNature(), value.energyItemCode(),
                value.nativeQuantity(), value.nativeUnitCode(), value.tce(), value.tceUnitCode(),
                value.coverageRatio(), value.issueCodes(), value.evidenceJson(), value.evidenceHash(),
                value.conversionSelectionJson(), timestamp(value.activityWatermark()),
                timestamp(value.calculatedAt()), timestamp(value.updatedAt()), value.projectionId(),
                expectedRevision);
    }

    public void insertLockRequest(LockRequest value) {
        jdbc.update("""
                INSERT INTO biz_energy_period_lock_request
                (request_id,projection_id,projection_revision,building_id,status,
                 issue_policy_versions,reason,evidence_reference,submitted_by,submitted_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, value.requestId(), value.projectionId(), value.projectionRevision(),
                value.buildingId(), value.status(), value.issuePolicyVersions(), value.reason(),
                value.evidenceReference(), value.submittedBy(), timestamp(value.submittedAt()));
    }

    public LockRequest findLockRequest(String requestId) {
        return one("SELECT * FROM biz_energy_period_lock_request WHERE request_id=?",
                this::lockRequest, requestId);
    }

    public int approveLockRequest(
            String requestId, long reviewerId, LocalDateTime reviewedAt, String reviewComment) {
        return jdbc.update("""
                UPDATE biz_energy_period_lock_request
                SET status='APPROVED',reviewed_by=?,reviewed_at=?,review_comment=?
                WHERE request_id=? AND status='PENDING_REVIEW'
                """, reviewerId, timestamp(reviewedAt), reviewComment, requestId);
    }

    public int nextSnapshotVersion(String projectionId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(MAX(snapshot_version),0)+1
                FROM biz_energy_period_result_snapshot WHERE projection_id=?
                """, Integer.class, projectionId);
    }

    public void insertSnapshot(PeriodSnapshot value) {
        jdbc.update("""
                INSERT INTO biz_energy_period_result_snapshot
                (snapshot_id,result_key,projection_id,building_id,point_id,period_type,
                 period_start,period_end,timezone_id,period_policy_version_id,snapshot_version,
                 status,result_nature,energy_item_code,native_quantity,native_unit_code,tce_value,
                 tce_unit_code,coverage_ratio,issue_codes,exception_policy_versions,evidence_json,
                 evidence_hash,conversion_selection_json,activity_watermark,supersedes_snapshot_id,
                 source_batch_id,locked_by,locked_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, snapshotArguments(value));
    }

    public PeriodSnapshot findSnapshot(String snapshotId) {
        return one("SELECT * FROM biz_energy_period_result_snapshot WHERE snapshot_id=?",
                this::snapshot, snapshotId);
    }

    public PeriodSnapshot findVisibleSnapshot(String projectionId) {
        return one("""
                SELECT s.* FROM biz_energy_period_result_snapshot s
                LEFT JOIN biz_energy_recalculation_batch b ON b.batch_id=s.source_batch_id
                WHERE s.projection_id=?
                  AND s.status IN ('LOCKED_COMPLETE','LOCKED_WITH_EXCEPTIONS','LOCKED_PARTIAL')
                  AND (s.source_batch_id IS NULL OR b.status='COMPLETED')
                ORDER BY s.snapshot_version DESC LIMIT 1
                """, this::snapshot, projectionId);
    }

    public List<PeriodSnapshot> listVisibleMonthlySnapshots(
            String buildingId, String pointId, Instant yearStart, Instant yearEnd) {
        return jdbc.query("""
                SELECT s.* FROM biz_energy_period_result_snapshot s
                LEFT JOIN biz_energy_recalculation_batch b ON b.batch_id=s.source_batch_id
                WHERE s.building_id=? AND s.point_id=? AND s.period_type='MONTH'
                  AND s.period_start>=? AND s.period_end<=?
                  AND s.status IN ('LOCKED_COMPLETE','LOCKED_WITH_EXCEPTIONS','LOCKED_PARTIAL')
                  AND (s.source_batch_id IS NULL OR b.status='COMPLETED')
                  AND NOT EXISTS (
                    SELECT 1 FROM biz_energy_period_result_snapshot newer
                    LEFT JOIN biz_energy_recalculation_batch newer_batch
                      ON newer_batch.batch_id=newer.source_batch_id
                    WHERE newer.projection_id=s.projection_id
                      AND newer.snapshot_version>s.snapshot_version
                      AND newer.status IN
                        ('LOCKED_COMPLETE','LOCKED_WITH_EXCEPTIONS','LOCKED_PARTIAL')
                      AND (newer.source_batch_id IS NULL OR newer_batch.status='COMPLETED'))
                ORDER BY s.period_start
                """, this::snapshot, buildingId, pointId, timestamp(yearStart), timestamp(yearEnd));
    }

    public RecalculationBatch findBatch(String batchId) {
        return one("SELECT * FROM biz_energy_recalculation_batch WHERE batch_id=?",
                this::batch, batchId);
    }

    public RecalculationBatch findBatchByIdempotencyKey(String idempotencyKey) {
        return one("SELECT * FROM biz_energy_recalculation_batch WHERE idempotency_key=?",
                this::batch, idempotencyKey);
    }

    public void insertBatch(RecalculationBatch value) {
        jdbc.update("""
                INSERT INTO biz_energy_recalculation_batch
                (batch_id,building_id,idempotency_key,request_hash,mode,status,reason,total_items,
                 processed_items,changed_items,unchanged_items,failed_items,submitted_by,submitted_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, value.batchId(), value.buildingId(), value.idempotencyKey(),
                value.requestHash(), value.mode(), value.status(), value.reason(), value.totalItems(),
                value.processedItems(), value.changedItems(), value.unchangedItems(),
                value.failedItems(), value.submittedBy(), timestamp(value.submittedAt()));
    }

    public void insertBatchItems(List<RecalculationItem> items) {
        jdbc.batchUpdate("""
                INSERT INTO biz_energy_recalculation_batch_item
                (item_id,batch_id,source_snapshot_id,item_order,status)
                VALUES (?,?,?,?,'PENDING')
                """, items, items.size(), (statement, value) -> {
                    statement.setString(1, value.itemId());
                    statement.setString(2, value.batchId());
                    statement.setString(3, value.sourceSnapshotId());
                    statement.setInt(4, value.itemOrder());
                });
    }

    public List<RecalculationItem> listBatchItems(String batchId) {
        return jdbc.query("""
                SELECT * FROM biz_energy_recalculation_batch_item
                WHERE batch_id=? ORDER BY item_order
                """, this::item, batchId);
    }

    public int approveBatch(
            String batchId, long reviewerId, LocalDateTime approvedAt, String reviewComment) {
        return jdbc.update("""
                UPDATE biz_energy_recalculation_batch
                SET status='VALIDATING',approved_by=?,approved_at=?,review_comment=?
                WHERE batch_id=? AND status='PENDING_REVIEW'
                """, reviewerId, timestamp(approvedAt), reviewComment, batchId);
    }

    public int advanceBatch(String batchId, String expected, String next) {
        return jdbc.update("""
                UPDATE biz_energy_recalculation_batch SET status=?
                WHERE batch_id=? AND status=?
                """, next, batchId, expected);
    }

    public void updateBatchItem(
            String itemId, String status, String newSnapshotId, String safeError) {
        jdbc.update("""
                UPDATE biz_energy_recalculation_batch_item
                SET status=?,new_snapshot_id=?,safe_error=? WHERE item_id=? AND status='PENDING'
                """, status, newSnapshotId, safeError, itemId);
    }

    public void refreshBatchCounters(String batchId) {
        jdbc.update("""
                UPDATE biz_energy_recalculation_batch b SET
                  processed_items=(SELECT COUNT(*) FROM biz_energy_recalculation_batch_item i
                                   WHERE i.batch_id=b.batch_id AND i.status<>'PENDING'),
                  changed_items=(SELECT COUNT(*) FROM biz_energy_recalculation_batch_item i
                                 WHERE i.batch_id=b.batch_id AND i.status='CHANGED'),
                  unchanged_items=(SELECT COUNT(*) FROM biz_energy_recalculation_batch_item i
                                   WHERE i.batch_id=b.batch_id AND i.status='UNCHANGED'),
                  failed_items=(SELECT COUNT(*) FROM biz_energy_recalculation_batch_item i
                                WHERE i.batch_id=b.batch_id AND i.status='FAILED')
                WHERE b.batch_id=?
                """, batchId);
    }

    public int completeBatch(String batchId, LocalDateTime completedAt) {
        jdbc.update("""
                UPDATE biz_energy_period_result_snapshot SET status='SUPERSEDED'
                WHERE snapshot_id IN (
                  SELECT source_snapshot_id FROM biz_energy_recalculation_batch_item
                  WHERE batch_id=? AND status='CHANGED')
                """, batchId);
        return jdbc.update("""
                UPDATE biz_energy_recalculation_batch SET status='COMPLETED',completed_at=?
                WHERE batch_id=? AND status='WRITING_RESULTS' AND failed_items=0
                  AND processed_items=total_items
                """, timestamp(completedAt), batchId);
    }

    public void failBatch(String batchId, String safeError, LocalDateTime completedAt) {
        jdbc.update("""
                UPDATE biz_energy_recalculation_batch
                SET status='FAILED',safe_error=?,completed_at=? WHERE batch_id=? AND status<>'COMPLETED'
                """, safeError, timestamp(completedAt), batchId);
    }

    public void markDirty(
            String dirtyId, String buildingId, String pointId, String periodType,
            Instant periodStart, Instant periodEnd, String reasonCode, LocalDateTime now) {
        int updated = jdbc.update("""
                UPDATE biz_energy_dirty_period
                SET event_count=event_count+1,last_detected_at=?,
                    reason_codes=CASE WHEN LOCATE(?,reason_codes)>0 THEN reason_codes
                                      ELSE CONCAT(reason_codes,',',?) END,
                    status='PENDING'
                WHERE building_id=? AND point_id=? AND period_type=?
                  AND period_start=? AND period_end=?
                """, timestamp(now), reasonCode, reasonCode, buildingId, pointId, periodType,
                timestamp(periodStart), timestamp(periodEnd));
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO biz_energy_dirty_period
                    (dirty_id,building_id,point_id,period_type,period_start,period_end,reason_codes,
                     event_count,status,first_detected_at,last_detected_at)
                    VALUES (?,?,?,?,?,?,?,1,'PENDING',?,?)
                    """, dirtyId, buildingId, pointId, periodType, timestamp(periodStart),
                    timestamp(periodEnd), reasonCode, timestamp(now), timestamp(now));
        }
    }

    private Object[] currentArguments(CurrentProjection value) {
        return new Object[]{value.projectionId(), value.resultKey(), value.buildingId(),
                value.pointId(), value.periodType(), timestamp(value.startInclusive()),
                timestamp(value.endExclusive()), value.timezoneId(), value.periodPolicyVersionId(),
                value.status(), value.revision(), value.resultNature(), value.energyItemCode(),
                value.nativeQuantity(), value.nativeUnitCode(), value.tce(), value.tceUnitCode(),
                value.coverageRatio(), value.issueCodes(), value.evidenceJson(), value.evidenceHash(),
                value.conversionSelectionJson(), timestamp(value.activityWatermark()),
                timestamp(value.calculatedAt()), timestamp(value.updatedAt())};
    }

    private Object[] snapshotArguments(PeriodSnapshot value) {
        return new Object[]{value.snapshotId(), value.resultKey(), value.projectionId(),
                value.buildingId(), value.pointId(), value.periodType(),
                timestamp(value.startInclusive()), timestamp(value.endExclusive()), value.timezoneId(),
                value.periodPolicyVersionId(), value.snapshotVersion(), value.status(),
                value.resultNature(), value.energyItemCode(), value.nativeQuantity(),
                value.nativeUnitCode(), value.tce(), value.tceUnitCode(), value.coverageRatio(),
                value.issueCodes(), value.exceptionPolicyVersions(), value.evidenceJson(),
                value.evidenceHash(), value.conversionSelectionJson(),
                timestamp(value.activityWatermark()), value.supersedesSnapshotId(),
                value.sourceBatchId(), value.lockedBy(), timestamp(value.lockedAt())};
    }

    private CurrentProjection current(ResultSet rs, int row) throws SQLException {
        return new CurrentProjection(rs.getString("projection_id"), rs.getString("result_key"),
                rs.getString("building_id"), rs.getString("point_id"), rs.getString("period_type"),
                instant(rs, "period_start"), instant(rs, "period_end"), rs.getString("timezone_id"),
                rs.getString("period_policy_version_id"), rs.getString("status"),
                rs.getLong("revision"), rs.getString("result_nature"),
                rs.getString("energy_item_code"), rs.getBigDecimal("native_quantity"),
                rs.getString("native_unit_code"), rs.getBigDecimal("tce_value"),
                rs.getString("tce_unit_code"), rs.getBigDecimal("coverage_ratio"),
                rs.getString("issue_codes"), rs.getString("evidence_json"),
                rs.getString("evidence_hash"), rs.getString("conversion_selection_json"),
                instant(rs, "activity_watermark"), local(rs, "calculated_at"),
                local(rs, "updated_at"));
    }

    private LockRequest lockRequest(ResultSet rs, int row) throws SQLException {
        return new LockRequest(rs.getString("request_id"), rs.getString("projection_id"),
                rs.getLong("projection_revision"), rs.getString("building_id"),
                rs.getString("status"), rs.getString("issue_policy_versions"),
                rs.getString("reason"), rs.getString("evidence_reference"),
                rs.getLong("submitted_by"), local(rs, "submitted_at"),
                nullableLong(rs, "reviewed_by"), local(rs, "reviewed_at"),
                rs.getString("review_comment"));
    }

    private PeriodSnapshot snapshot(ResultSet rs, int row) throws SQLException {
        return new PeriodSnapshot(rs.getString("snapshot_id"), rs.getString("result_key"),
                rs.getString("projection_id"), rs.getString("building_id"),
                rs.getString("point_id"), rs.getString("period_type"),
                instant(rs, "period_start"), instant(rs, "period_end"),
                rs.getString("timezone_id"), rs.getString("period_policy_version_id"),
                rs.getInt("snapshot_version"), rs.getString("status"),
                rs.getString("result_nature"), rs.getString("energy_item_code"),
                rs.getBigDecimal("native_quantity"), rs.getString("native_unit_code"),
                rs.getBigDecimal("tce_value"), rs.getString("tce_unit_code"),
                rs.getBigDecimal("coverage_ratio"), rs.getString("issue_codes"),
                rs.getString("exception_policy_versions"), rs.getString("evidence_json"),
                rs.getString("evidence_hash"), rs.getString("conversion_selection_json"),
                instant(rs, "activity_watermark"), rs.getString("supersedes_snapshot_id"),
                rs.getString("source_batch_id"), rs.getLong("locked_by"), local(rs, "locked_at"));
    }

    private RecalculationBatch batch(ResultSet rs, int row) throws SQLException {
        return new RecalculationBatch(rs.getString("batch_id"), rs.getString("building_id"),
                rs.getString("idempotency_key"), rs.getString("request_hash"), rs.getString("mode"),
                rs.getString("status"), rs.getString("reason"), rs.getInt("total_items"),
                rs.getInt("processed_items"), rs.getInt("changed_items"),
                rs.getInt("unchanged_items"), rs.getInt("failed_items"),
                rs.getLong("submitted_by"), local(rs, "submitted_at"),
                nullableLong(rs, "approved_by"), local(rs, "approved_at"),
                rs.getString("review_comment"), rs.getString("safe_error"),
                local(rs, "completed_at"));
    }

    private RecalculationItem item(ResultSet rs, int row) throws SQLException {
        return new RecalculationItem(rs.getString("item_id"), rs.getString("batch_id"),
                rs.getString("source_snapshot_id"), rs.getInt("item_order"),
                rs.getString("status"), rs.getString("new_snapshot_id"),
                rs.getString("safe_error"));
    }

    private <T> T one(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
        List<T> values = jdbc.query(sql, mapper, args);
        return values.isEmpty() ? null : values.getFirst();
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
