package com.platform.carbon;

import com.platform.carbon.CarbonModels.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
/** 持久化自动重算变化、影响批次、数据库租约、重试和正式结果替代关系。 */
class CarbonRecalculationRepository {
    private final JdbcTemplate jdbc;

    CarbonRecalculationRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    DependencyChange findPendingChangeForUpdate() {
        return one("""
                SELECT * FROM biz_carbon_dependency_change
                WHERE status='PENDING' ORDER BY created_at LIMIT 1 FOR UPDATE
                """, CarbonRecalculationRepository::change);
    }

    int claimChange(String changeId) {
        return jdbc.update("""
                UPDATE biz_carbon_dependency_change SET status='ANALYZING'
                WHERE change_id=? AND status='PENDING'
                """, changeId);
    }

    void completeChange(String changeId, boolean success, LocalDateTime now) {
        jdbc.update("""
                UPDATE biz_carbon_dependency_change SET status=?,processed_at=?
                WHERE change_id=? AND status='ANALYZING'
                """, success ? "PROCESSED" : "FAILED", timestamp(now), changeId);
    }

    void insertTrigger(String triggerId, DependencyChange change, String fingerprint,
                       int impactedItems, String impactStatus, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO biz_carbon_recalculation_trigger
                (trigger_id,change_id,trigger_fingerprint,trigger_reason,correction_window_start,
                 correction_window_end,impact_status,impacted_item_count,created_at,analyzed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, triggerId, change.changeId(), fingerprint, change.changeType(),
                timestamp(change.effectiveFrom()), timestamp(change.effectiveTo()), impactStatus,
                impactedItems, timestamp(change.createdAt()), timestamp(now));
    }

    List<CalculationBatch> findImpactedResults(DependencyChange change, int limit) {
        return jdbc.query("""
                SELECT b.* FROM biz_carbon_calculation_batch b
                WHERE b.period_type='YEAR'
                  AND b.status IN ('COMPLETED_COMPLETE','COMPLETED_INCOMPLETE')
                  AND b.publication_status IN ('DIRECT','PUBLISHED')
                  AND (? IS NULL OR b.building_id=?)
                  AND b.period_end>? AND (? IS NULL OR b.period_start<?)
                  AND NOT EXISTS (
                    SELECT 1 FROM biz_carbon_calculation_batch newer
                    WHERE newer.building_id=b.building_id AND newer.period_type=b.period_type
                      AND newer.period_start=b.period_start AND newer.period_end=b.period_end
                      AND newer.result_nature=b.result_nature
                      AND newer.status IN ('COMPLETED_COMPLETE','COMPLETED_INCOMPLETE')
                      AND newer.completed_at>b.completed_at
                      AND NOT EXISTS (
                        SELECT 1 FROM biz_carbon_result_relation candidate
                        WHERE candidate.new_calculation_batch_id=newer.calculation_batch_id
                          AND candidate.relation_status='CANDIDATE'))
                  AND NOT EXISTS (
                    SELECT 1 FROM biz_carbon_result_relation r
                    WHERE r.old_calculation_batch_id=b.calculation_batch_id
                      AND r.relation_status='SUPERSEDED')
                ORDER BY b.building_id,b.period_start,b.result_nature LIMIT ?
                """, CarbonRecalculationRepository::calculationBatch, change.buildingId(),
                change.buildingId(), timestamp(change.effectiveFrom()),
                timestamp(change.effectiveTo()), timestamp(change.effectiveTo()), limit);
    }

    boolean batchTouchesEnergyItem(String batchId, String energyItemCode) {
        Integer count = jdbc.queryForObject("""
                SELECT
                  (SELECT COUNT(*) FROM biz_carbon_calculation_item
                   WHERE calculation_batch_id=? AND energy_item_code=?) +
                  (SELECT COUNT(*) FROM biz_carbon_calculation_failure
                   WHERE calculation_batch_id=? AND energy_item_code=?)
                """, Integer.class, batchId, energyItemCode, batchId, energyItemCode);
        return count != null && count > 0;
    }

    RecalculationBatch findMergeableBatch(String reason, String boundary,
                                          ResultNature nature, int maximumItems) {
        return one("""
                SELECT * FROM biz_carbon_recalculation_batch
                WHERE trigger_reason=? AND organization_boundary=? AND result_nature=?
                  AND status='PENDING_CALCULATION' AND scope_frozen=0 AND item_count<?
                ORDER BY created_at LIMIT 1 FOR UPDATE
                """, CarbonRecalculationRepository::batch, reason, boundary,
                nature.name(), maximumItems);
    }

    void insertBatch(RecalculationBatch value) {
        jdbc.update("""
                INSERT INTO biz_carbon_recalculation_batch
                (recalculation_batch_id,batch_key,trigger_reason,organization_boundary,
                 result_nature,status,scope_frozen,item_count,eligible_item_count,initiated_by,
                 created_at) VALUES (?,?,?,?,?,'PENDING_CALCULATION',0,0,0,?,?)
                """, value.batchId(), value.batchKey(), value.triggerReason(),
                value.organizationBoundary(), value.resultNature().name(), value.initiatedBy(),
                timestamp(value.createdAt()));
    }

    void attachTrigger(String batchId, String triggerId) {
        jdbc.update("""
                INSERT IGNORE INTO biz_carbon_recalculation_batch_trigger
                (recalculation_batch_id,trigger_id) VALUES (?,?)
                """, batchId, triggerId);
    }

    int insertItem(String batchId, CalculationBatch oldResult, int accountingYear) {
        String activeLock = oldResult.buildingId() + '|' + accountingYear + '|'
                + oldResult.resultNature();
        return jdbc.update("""
                INSERT IGNORE INTO biz_carbon_recalculation_item
                (recalculation_item_id,recalculation_batch_id,building_id,accounting_year,
                 old_calculation_batch_id,status,approval_eligible,retry_count,active_lock_key,
                 created_at)
                VALUES (?,?,?,?,?,'PENDING',0,0,?,?)
                """, id(), batchId, oldResult.buildingId(), accountingYear,
                oldResult.batchId(), activeLock, timestamp(LocalDateTime.now()));
    }

    String findActiveBatchForItem(String buildingId, int accountingYear, ResultNature nature) {
        return one("""
                SELECT i.recalculation_batch_id FROM biz_carbon_recalculation_item i
                JOIN biz_carbon_recalculation_batch b
                  ON b.recalculation_batch_id=i.recalculation_batch_id
                WHERE i.building_id=? AND i.accounting_year=? AND b.result_nature=?
                  AND i.active_lock_key IS NOT NULL
                LIMIT 1 FOR UPDATE
                """, (rs, row) -> rs.getString(1), buildingId, accountingYear, nature.name());
    }

    void refreshBatchItemCount(String batchId) {
        jdbc.update("""
                UPDATE biz_carbon_recalculation_batch b SET
                  item_count=(SELECT COUNT(*) FROM biz_carbon_recalculation_item i
                              WHERE i.recalculation_batch_id=b.recalculation_batch_id)
                WHERE recalculation_batch_id=?
                """, batchId);
    }

    RecalculationBatch findClaimableBatchForUpdate(LocalDateTime now) {
        return one("""
                SELECT * FROM biz_carbon_recalculation_batch
                WHERE status IN ('PENDING_CALCULATION','FAILED_RETRYABLE')
                  AND (lease_until IS NULL OR lease_until<?)
                  AND EXISTS (
                    SELECT 1 FROM biz_carbon_recalculation_item i
                    WHERE i.recalculation_batch_id=biz_carbon_recalculation_batch.recalculation_batch_id
                      AND i.status IN ('PENDING','FAILED_RETRYABLE')
                      AND (i.next_attempt_at IS NULL OR i.next_attempt_at<=?))
                ORDER BY created_at LIMIT 1 FOR UPDATE
                """, CarbonRecalculationRepository::batch, timestamp(now), timestamp(now));
    }

    void recoverExpiredBatches(LocalDateTime now, int maximumRetries) {
        jdbc.update("""
                UPDATE biz_carbon_recalculation_item i
                JOIN biz_carbon_recalculation_batch b
                  ON b.recalculation_batch_id=i.recalculation_batch_id
                SET i.status=CASE WHEN i.retry_count+1>? THEN 'DEAD' ELSE 'FAILED_RETRYABLE' END,
                    i.next_attempt_at=CASE WHEN i.retry_count+1>? THEN NULL ELSE ? END,
                    i.safe_error_code='CARBON_RECALCULATION_LEASE_EXPIRED',
                    i.safe_error_message='工作线程租约过期，任务已恢复',
                    i.active_lock_key=CASE WHEN i.retry_count+1>? THEN NULL ELSE i.active_lock_key END,
                    i.retry_count=i.retry_count+1
                WHERE b.status='CALCULATING' AND b.lease_until<? AND i.status='CALCULATING'
                """, maximumRetries, maximumRetries, timestamp(now), maximumRetries,
                timestamp(now));
        jdbc.update("""
                UPDATE biz_carbon_recalculation_batch b
                SET b.status=CASE WHEN EXISTS (
                      SELECT 1 FROM biz_carbon_recalculation_item i
                      WHERE i.recalculation_batch_id=b.recalculation_batch_id
                        AND i.status='FAILED_RETRYABLE')
                    THEN 'FAILED_RETRYABLE' ELSE 'DEAD' END,
                    b.lease_token=NULL,b.lease_until=NULL,
                    b.safe_error_code='CARBON_RECALCULATION_LEASE_EXPIRED'
                WHERE b.status='CALCULATING' AND b.lease_until<?
                """, timestamp(now));
    }

    int claimBatch(String batchId, String leaseToken, LocalDateTime leaseUntil,
                   LocalDateTime now) {
        return jdbc.update("""
                UPDATE biz_carbon_recalculation_batch
                SET status='CALCULATING',scope_frozen=1,lease_token=?,lease_until=?,
                    started_at=COALESCE(started_at,?)
                WHERE recalculation_batch_id=?
                  AND status IN ('PENDING_CALCULATION','FAILED_RETRYABLE')
                """, leaseToken, timestamp(leaseUntil), timestamp(now), batchId);
    }

    RecalculationBatch findBatch(String batchId) {
        return one("SELECT * FROM biz_carbon_recalculation_batch WHERE recalculation_batch_id=?",
                CarbonRecalculationRepository::batch, batchId);
    }

    List<RecalculationItem> listItems(String batchId) {
        return jdbc.query("""
                SELECT * FROM biz_carbon_recalculation_item
                WHERE recalculation_batch_id=? ORDER BY building_id,accounting_year
                """, CarbonRecalculationRepository::item, batchId);
    }

    RecalculationItem findItem(String itemId) {
        return one("SELECT * FROM biz_carbon_recalculation_item WHERE recalculation_item_id=?",
                CarbonRecalculationRepository::item, itemId);
    }

    List<String> listBuildingIds(String batchId) {
        return jdbc.query("""
                SELECT DISTINCT building_id FROM biz_carbon_recalculation_item
                WHERE recalculation_batch_id=? ORDER BY building_id
                """, (rs, row) -> rs.getString(1), batchId);
    }

    List<Long> listResponsibleUsers(String batchId) {
        return jdbc.query("""
                SELECT DISTINCT c.triggered_by FROM biz_carbon_recalculation_batch_trigger bt
                JOIN biz_carbon_recalculation_trigger t ON t.trigger_id=bt.trigger_id
                JOIN biz_carbon_dependency_change c ON c.change_id=t.change_id
                WHERE bt.recalculation_batch_id=? AND c.triggered_by IS NOT NULL
                """, (rs, row) -> rs.getLong(1), batchId);
    }

    int startItem(String itemId, String batchId) {
        return jdbc.update("""
                UPDATE biz_carbon_recalculation_item SET status='CALCULATING'
                WHERE recalculation_item_id=? AND recalculation_batch_id=?
                  AND status IN ('PENDING','FAILED_RETRYABLE')
                """, itemId, batchId);
    }

    void succeedItem(String itemId, String candidateBatchId, LocalDateTime now) {
        jdbc.update("""
                UPDATE biz_carbon_recalculation_item
                SET status='SUCCEEDED',candidate_calculation_batch_id=?,approval_eligible=1,
                    safe_error_code=NULL,safe_error_message=NULL,next_attempt_at=NULL,
                    completed_at=?
                WHERE recalculation_item_id=? AND status='CALCULATING'
                """, candidateBatchId, timestamp(now), itemId);
    }

    void failItem(String itemId, boolean dead, int retries, LocalDateTime nextAttempt,
                  String code, String message, LocalDateTime now) {
        jdbc.update("""
                UPDATE biz_carbon_recalculation_item
                SET status=?,approval_eligible=0,retry_count=?,next_attempt_at=?,
                    safe_error_code=?,safe_error_message=?,
                    active_lock_key=CASE WHEN ? THEN NULL ELSE active_lock_key END,completed_at=?
                WHERE recalculation_item_id=? AND status='CALCULATING'
                """, dead ? "DEAD" : "FAILED_RETRYABLE", retries,
                timestamp(nextAttempt), code, message, dead,
                timestamp(now), itemId);
    }

    void registerCandidate(String recalculationBatchId, String buildingId,
                           String oldBatchId, String newBatchId) {
        jdbc.update("""
                INSERT INTO biz_carbon_result_relation
                (relation_id,building_id,old_calculation_batch_id,new_calculation_batch_id,
                 relation_status,approved_recalculation_batch_id)
                VALUES (?,?,?,?,'CANDIDATE',?)
                """, id(), buildingId, oldBatchId, newBatchId, recalculationBatchId);
    }

    void finishCalculationPhase(String batchId, ResultNature nature, LocalDateTime now) {
        refreshBatchCounters(batchId);
        int retryable = countItems(batchId, "FAILED_RETRYABLE");
        int succeeded = countItems(batchId, "SUCCEEDED");
        String status;
        if (retryable > 0) status = "FAILED_RETRYABLE";
        else if (succeeded > 0 && nature == ResultNature.FORMAL) status = "PENDING_APPROVAL";
        else if (succeeded > 0) status = "COMPLETED";
        else status = "DEAD";
        jdbc.update("""
                UPDATE biz_carbon_recalculation_batch
                SET status=?,lease_token=NULL,lease_until=NULL,
                    completed_at=CASE WHEN ? IN ('COMPLETED','DEAD') THEN ? ELSE completed_at END
                WHERE recalculation_batch_id=? AND status='CALCULATING'
                """, status, status, timestamp(now), batchId);
        if (nature == ResultNature.DEVELOPMENT_SIMULATION && "COMPLETED".equals(status)) {
            jdbc.update("""
                    UPDATE biz_carbon_recalculation_item SET active_lock_key=NULL
                    WHERE recalculation_batch_id=? AND status='SUCCEEDED'
                    """, batchId);
        }
    }

    void refreshBatchCounters(String batchId) {
        jdbc.update("""
                UPDATE biz_carbon_recalculation_batch b SET
                  item_count=(SELECT COUNT(*) FROM biz_carbon_recalculation_item i
                              WHERE i.recalculation_batch_id=b.recalculation_batch_id),
                  eligible_item_count=(SELECT COUNT(*) FROM biz_carbon_recalculation_item i
                              WHERE i.recalculation_batch_id=b.recalculation_batch_id
                                AND i.approval_eligible=1)
                WHERE recalculation_batch_id=?
                """, batchId);
    }

    int approveBatch(String batchId, long reviewerId, String comment, LocalDateTime now) {
        return jdbc.update("""
                UPDATE biz_carbon_recalculation_batch
                SET status='PUBLISHING',approved_by=?,approved_at=?,review_comment=?
                WHERE recalculation_batch_id=? AND status='PENDING_APPROVAL'
                  AND eligible_item_count>0
                """, reviewerId, timestamp(now), comment, batchId);
    }

    int rejectBatch(String batchId, long reviewerId, String comment, LocalDateTime now) {
        int updated = jdbc.update("""
                UPDATE biz_carbon_recalculation_batch
                SET status='REJECTED',approved_by=?,approved_at=?,review_comment=?,completed_at=?
                WHERE recalculation_batch_id=? AND status='PENDING_APPROVAL'
                """, reviewerId, timestamp(now), comment, timestamp(now), batchId);
        if (updated == 1) {
            jdbc.update("""
                    UPDATE biz_carbon_recalculation_item SET status='REJECTED',
                        approval_eligible=0,active_lock_key=NULL
                    WHERE recalculation_batch_id=? AND status='SUCCEEDED'
                    """, batchId);
        }
        return updated;
    }

    int publishBatch(String batchId, LocalDateTime now) {
        List<RecalculationItem> eligible = jdbc.query("""
                SELECT * FROM biz_carbon_recalculation_item
                WHERE recalculation_batch_id=? AND status='SUCCEEDED' AND approval_eligible=1
                FOR UPDATE
                """, CarbonRecalculationRepository::item, batchId);
        if (eligible.isEmpty()) return 0;
        for (RecalculationItem item : eligible) {
            Integer current = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM biz_carbon_calculation_batch
                    WHERE calculation_batch_id=? AND publication_status IN ('DIRECT','PUBLISHED')
                      AND status='COMPLETED_COMPLETE'
                    """, Integer.class, item.oldCalculationBatchId());
            if (current == null || current != 1) return 0;
            int relation = jdbc.update("""
                    UPDATE biz_carbon_result_relation
                    SET relation_status='SUPERSEDED',published_at=?
                    WHERE approved_recalculation_batch_id=? AND old_calculation_batch_id=?
                      AND new_calculation_batch_id=? AND relation_status='CANDIDATE'
                    """, timestamp(now), batchId, item.oldCalculationBatchId(),
                    item.candidateCalculationBatchId());
            if (relation != 1) return 0;
            jdbc.update("""
                    UPDATE biz_carbon_calculation_batch SET publication_status='SUPERSEDED'
                    WHERE calculation_batch_id=? AND publication_status IN ('DIRECT','PUBLISHED')
                    """, item.oldCalculationBatchId());
            jdbc.update("""
                    UPDATE biz_carbon_calculation_batch SET publication_status='PUBLISHED'
                    WHERE calculation_batch_id=? AND publication_status='CANDIDATE'
                    """, item.candidateCalculationBatchId());
            jdbc.update("""
                    UPDATE biz_carbon_recalculation_item
                    SET status='PUBLISHED',approval_eligible=0,active_lock_key=NULL,completed_at=?
                    WHERE recalculation_item_id=? AND status='SUCCEEDED'
                    """, timestamp(now), item.itemId());
        }
        return jdbc.update("""
                UPDATE biz_carbon_recalculation_batch
                SET status='COMPLETED',completed_at=?
                WHERE recalculation_batch_id=? AND status='PUBLISHING'
                """, timestamp(now), batchId);
    }

    private int countItems(String batchId, String status) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_carbon_recalculation_item
                WHERE recalculation_batch_id=? AND status=?
                """, Integer.class, batchId, status);
        return count == null ? 0 : count;
    }

    private <T> T one(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
        List<T> values = jdbc.query(sql, mapper, args);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static DependencyChange change(ResultSet rs, int row) throws SQLException {
        return new DependencyChange(rs.getString("change_id"), rs.getString("change_type"),
                rs.getString("source_object_type"), rs.getString("source_object_id"),
                rs.getString("change_detail"), rs.getString("old_version_id"),
                rs.getString("new_version_id"),
                rs.getString("change_fingerprint"), rs.getString("building_id"),
                rs.getString("organization_boundary"),
                local(rs, "effective_from"), local(rs, "effective_to"),
                nullableLong(rs, "triggered_by"), rs.getString("status"),
                local(rs, "created_at"), local(rs, "processed_at"));
    }

    private static RecalculationBatch batch(ResultSet rs, int row) throws SQLException {
        return new RecalculationBatch(rs.getString("recalculation_batch_id"),
                rs.getString("batch_key"), rs.getString("trigger_reason"),
                rs.getString("organization_boundary"),
                ResultNature.valueOf(rs.getString("result_nature")), rs.getString("status"),
                rs.getBoolean("scope_frozen"), rs.getInt("item_count"),
                rs.getInt("eligible_item_count"), rs.getString("lease_token"),
                local(rs, "lease_until"), local(rs, "started_at"),
                local(rs, "completed_at"), nullableLong(rs, "initiated_by"),
                nullableLong(rs, "approved_by"), local(rs, "approved_at"),
                rs.getString("review_comment"), rs.getString("safe_error_code"),
                local(rs, "created_at"));
    }

    private static RecalculationItem item(ResultSet rs, int row) throws SQLException {
        return new RecalculationItem(rs.getString("recalculation_item_id"),
                rs.getString("recalculation_batch_id"), rs.getString("building_id"),
                rs.getInt("accounting_year"), rs.getString("old_calculation_batch_id"),
                rs.getString("candidate_calculation_batch_id"), rs.getString("status"),
                rs.getBoolean("approval_eligible"), rs.getInt("retry_count"),
                local(rs, "next_attempt_at"), rs.getString("safe_error_code"),
                rs.getString("safe_error_message"), rs.getString("active_lock_key"),
                local(rs, "created_at"), local(rs, "completed_at"));
    }

    private static CalculationBatch calculationBatch(ResultSet rs, int row) throws SQLException {
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

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static Timestamp timestamp(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
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
