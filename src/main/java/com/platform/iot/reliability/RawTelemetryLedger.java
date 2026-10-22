package com.platform.iot.reliability;

import com.platform.iot.temporal.model.RawTelemetryEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;

/**
 * MySQL 决定首次接受内容，TDengine 只接收该冻结快照。意图独立提交，不能随消息回执回滚。
 * 冲突按内容合并且有持久化配额；没有依据时不会根据到达先后自动更正业务值。
 */
@Service
public class RawTelemetryLedger {
    private final JdbcTemplate jdbc;
    private final RawTelemetryCodec codec;
    private final TransactionTemplate tx;
    private final int maxVariants;
    private final long maxEvidence;
    @Value("${telemetry-conflicts.max-pending-intents:50000}")
    private int maxPending=50000;

    public RawTelemetryLedger(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, RawTelemetryCodec codec,
            @Value("${telemetry-conflicts.max-variants-per-fact:16}") int maxVariants,
            @Value("${telemetry-conflicts.max-evidence-rows:50000}") long maxEvidence) {
        if (maxVariants < 1 || maxVariants > 100 || maxEvidence < 1) throw new IllegalArgumentException("Invalid conflict capacity");
        this.jdbc = jdbc; this.codec = codec; this.maxVariants = maxVariants; this.maxEvidence = maxEvidence;
        tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(30);
    }

    public record Reservation(RawTelemetryEvent original, boolean conflict, boolean expired) { }
    public record Fact(String pointKey, long eventTime, String pointId, String buildingId, String hash,
            String snapshotJson, String storageState, String conflictState, int conflictRevision,
            Long firstConflictAt, String selectedJson, int selectionVersion) { }
    public record Evidence(String evidenceId, String hash, String snapshotJson, long firstSeenAt,
            long lastSeenAt, long attempts) { }
    public record Selection(String pointId, long eventTime, double originalValue, double selectedValue,
            String versionId) { }

    /** 所有原始入口共用此登记。旧库已有记录优先接管，不能由本次新值覆盖后再宣称首次写入。 */
    public Reservation reserve(RawTelemetryEvent incoming, Supplier<RawTelemetryEvent> historical) {
        String key = codec.key(incoming.pointId());
        String hash = codec.hash(incoming);
        return tx.execute(status -> {
            boundary(key);
            long floor = jdbc.queryForObject("SELECT before_ms FROM biz_telemetry_retention_boundary WHERE point_key=? FOR UPDATE", Long.class, key);
            if (incoming.eventTime() < floor) return new Reservation(null, false, true);
            boolean inserted = false;
            try {
                jdbc.update("""
                        INSERT INTO biz_telemetry_raw_intent
                        (point_key,event_ms,point_id,building_id,payload_hash,snapshot_json,storage_state)
                        VALUES (?,?,?,?,?,?,'RESERVING')
                        """, key, incoming.eventTime(), incoming.pointId(), incoming.buildingId(), hash, codec.encode(incoming));
                inserted = true;
            } catch (DuplicateKeyException duplicate) {
                // 唯一键竞争完成后再锁定已经提交的行，不依赖查询空键时的间隙锁。
            }
            Fact fact = locked(key, incoming.eventTime());
            if (inserted) {
                if(jdbc.update("UPDATE biz_telemetry_conflict_budget SET used_rows=used_rows+1 WHERE budget_key='PENDING_RAW' AND used_rows<?",maxPending)!=1)
                    throw new DataAccessResourceFailureException("Pending raw capacity reached");
                RawTelemetryEvent existing = historical.get();
                RawTelemetryEvent frozen = existing == null ? incoming : existing;
                if (!key.equals(codec.key(frozen.pointId()))) throw new IllegalStateException("Historical raw identity mismatch");
                jdbc.update("""
                        UPDATE biz_telemetry_raw_intent SET point_id=?,building_id=?,payload_hash=?,snapshot_json=?,storage_state='PENDING'
                        WHERE point_key=? AND event_ms=?
                        """, frozen.pointId(), frozen.buildingId(), codec.hash(frozen), codec.encode(frozen), key, incoming.eventTime());
                fact = locked(key, incoming.eventTime());
            }
            RawTelemetryEvent original = codec.decode(fact.snapshotJson());
            if (!hash.equals(fact.hash())) {
                appendConflict(fact, incoming, hash);
                return new Reservation(original, true, false);
            }
            if (!codec.hash(original).equals(hash)) throw new IllegalStateException("Raw snapshot digest mismatch");
            return new Reservation(original, false, false);
        });
    }

    public void retainIfKnown(RawTelemetryEvent incoming) {
        tx.executeWithoutResult(status->{
            String key=codec.key(incoming.pointId());
            var boundaries=jdbc.queryForList("SELECT before_ms FROM biz_telemetry_retention_boundary WHERE point_key=? FOR UPDATE",Long.class,key);
            if(boundaries.isEmpty()||incoming.eventTime()<boundaries.getFirst())return;
            var rows=jdbc.query("SELECT * FROM biz_telemetry_raw_intent WHERE point_key=? AND event_ms=? FOR UPDATE",RawTelemetryLedger::mapFact,key,incoming.eventTime());
            if(rows.isEmpty())return;
            String hash=codec.hash(incoming);
            if(!hash.equals(rows.getFirst().hash()))appendConflict(rows.getFirst(),incoming,hash);
        });
    }

    private void appendConflict(Fact fact, RawTelemetryEvent incoming, String hash) {
        long now = System.currentTimeMillis();
        int duplicate = jdbc.update("""
                UPDATE biz_telemetry_conflict_evidence SET attempt_count=attempt_count+1,last_seen_ms=?
                WHERE point_key=? AND event_ms=? AND payload_hash=?
                """, now, fact.pointKey(), fact.eventTime(), hash);
        if (duplicate == 1) return; // 已处置或已超期内容重传不会重新打开待办。
        int variants = jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_conflict_evidence WHERE point_key=? AND event_ms=?",
                Integer.class, fact.pointKey(), fact.eventTime());
        if (variants >= maxVariants || jdbc.update("""
                UPDATE biz_telemetry_conflict_budget SET used_rows=used_rows+1
                WHERE budget_key='EVIDENCE' AND used_rows<?
                """, maxEvidence) != 1) {
            throw new DataAccessResourceFailureException("Telemetry conflict capacity reached; evidence not acknowledged");
        }
        jdbc.update("""
                INSERT INTO biz_telemetry_conflict_evidence
                (evidence_id,point_key,event_ms,payload_hash,snapshot_json,first_seen_ms,last_seen_ms,attempt_count)
                VALUES (?,?,?,?,?,?,?,1)
                """, id(), fact.pointKey(), fact.eventTime(), hash, codec.encode(incoming), now, now);
        jdbc.update("""
                UPDATE biz_telemetry_raw_intent SET first_conflict_ms=CASE WHEN conflict_state='OPEN' THEN first_conflict_ms ELSE ? END,
                    conflict_state='OPEN',conflict_revision=conflict_revision+1 WHERE point_key=? AND event_ms=?
                """, now, fact.pointKey(), fact.eventTime());
    }

    /** 回放与退役共用水位锁，防止清理后在锁外重新写回过期原始行。 */
    public <T> T storeFrozen(RawTelemetryEvent original, Supplier<T> write) {
        return tx.execute(status -> {
            String key = codec.key(original.pointId());
            boundary(key);
            long floor = jdbc.queryForObject("SELECT before_ms FROM biz_telemetry_retention_boundary WHERE point_key=? FOR UPDATE", Long.class, key);
            if (original.eventTime() < floor) throw new IllegalStateException("Raw intent retired");
            Fact fact = locked(key, original.eventTime());
            if (!fact.snapshotJson().equals(codec.encode(original))) throw new IllegalStateException("Frozen raw snapshot mismatch");
            T result = write.get();
            if(!"PERSISTED".equals(fact.storageState()))
                jdbc.update("UPDATE biz_telemetry_conflict_budget SET used_rows=used_rows-1 WHERE budget_key='PENDING_RAW'");
            jdbc.update("UPDATE biz_telemetry_raw_intent SET storage_state='PERSISTED',retry_count=0,next_retry_ms=0 WHERE point_key=? AND event_ms=?", key, original.eventTime());
            return result;
        });
    }

    /** 在 storeFrozen 的同一 MySQL 事务内登记恢复通知，避免原接收时非迟到的事件漏掉历史重聚合。 */
    public void queueRecovered(RawTelemetryEvent event) {
        TelemetryConflictService.enqueue(jdbc,event.pointId(),event.buildingId(),event.eventTime(),"raw-recovery");
    }

    public List<RawTelemetryEvent> pending(int limit, long now) {
        return jdbc.query("SELECT snapshot_json FROM biz_telemetry_raw_intent WHERE storage_state='PENDING' AND next_retry_ms<=? ORDER BY next_retry_ms,point_key,event_ms LIMIT ?",
                (rs, n) -> codec.decode(rs.getString(1)), now, limit);
    }

    public void retryLater(RawTelemetryEvent event, long now) {
        jdbc.update("""
                UPDATE biz_telemetry_raw_intent SET storage_state=CASE WHEN retry_count>=9 THEN 'FAILED' ELSE 'PENDING' END,last_error='RAW_RECOVERY_FAILED',retry_count=retry_count+1,next_retry_ms=?
                WHERE point_key=? AND event_ms=? AND storage_state='PENDING'
                """, now + 60_000, codec.key(event.pointId()), event.eventTime());
    }

    /** 分批合并业务选择；能源点只消费既有 APPROVED 更正，不另建一套免审核取值。 */
    public List<RawTelemetryEvent> effective(List<RawTelemetryEvent> originals) {
        if (originals.isEmpty()) return originals;
        List<RawTelemetryEvent> result = new ArrayList<>(originals.size());
        for (int offset = 0; offset < originals.size(); offset += 500) {
            List<RawTelemetryEvent> batch = originals.subList(offset, Math.min(offset + 500, originals.size()));
            Set<String> ids = new LinkedHashSet<>(); batch.forEach(e -> ids.add(e.pointId()));
            long from = batch.stream().mapToLong(RawTelemetryEvent::eventTime).min().orElseThrow();
            long to = batch.stream().mapToLong(RawTelemetryEvent::eventTime).max().orElseThrow() + 1;
            Map<String, Selection> selected = selections(ids, from, to);
            for (RawTelemetryEvent e : batch) {
                Selection s = selected.get(identity(e.pointId(), e.eventTime()));
                if (s != null && Double.compare(s.originalValue(), e.value()) != 0) {
                    throw new IllegalStateException("Effective correction does not match original telemetry");
                }
                result.add(s == null ? e : codec.withValue(e, s.selectedValue()));
            }
        }
        return List.copyOf(result);
    }

    public Map<String, Selection> selections(Collection<String> pointIds, long from, long to) {
        if (pointIds.isEmpty()) return Map.of();
        String marks = String.join(",", Collections.nCopies(pointIds.size(), "?"));
        List<Object> args = new ArrayList<>(pointIds); args.add(from); args.add(to);
        Map<String, Selection> result = new HashMap<>();
        jdbc.query("SELECT * FROM biz_telemetry_raw_intent WHERE point_id IN (" + marks + ") AND event_ms>=? AND event_ms<? AND selected_json IS NOT NULL LIMIT 5001",
                (ResultSet rs) -> {
                    if (result.size()>=5000) throw new IllegalStateException("Correction query capacity exceeded");
                    RawTelemetryEvent base = codec.decode(rs.getString("snapshot_json"));
                    RawTelemetryEvent chosen = codec.decode(rs.getString("selected_json"));
                    result.put(identity(base.pointId(), base.eventTime()), new Selection(base.pointId(), base.eventTime(), base.value(), chosen.value(), "telemetry:" + rs.getInt("selection_version")));
                }, args.toArray());
        // 能源治理以 pointId@eventTime 标识原始事实，保持已有审批和精度边界。
        final int[] correctionCount = {0};
        jdbc.query("""
                SELECT c.meter_point_id,c.original_fact_identity,v.original_value,v.corrected_value,v.correction_version_id
                FROM biz_energy_activity_correction c JOIN biz_energy_activity_correction_version v ON v.correction_id=c.correction_id
                WHERE c.meter_point_id IN (%s) AND v.status='APPROVED'
                AND CAST(SUBSTRING(c.original_fact_identity,LOCATE('@',c.original_fact_identity)+1) AS DECIMAL(20,0))>=?
                AND CAST(SUBSTRING(c.original_fact_identity,LOCATE('@',c.original_fact_identity)+1) AS DECIMAL(20,0))<? LIMIT 5001
                """.formatted(marks), (ResultSet rs) -> {
            if (++correctionCount[0]>5000) throw new IllegalStateException("Correction query capacity exceeded");
            String point = rs.getString(1), fact = rs.getString(2);
            long time = Long.parseLong(fact.substring(fact.lastIndexOf('@') + 1));
            if (time >= from && time < to) {
                Selection value = new Selection(point, time, rs.getBigDecimal(3).doubleValue(), rs.getBigDecimal(4).doubleValue(), rs.getString(5));
                if (result.putIfAbsent(identity(point, time), value) != null) throw new IllegalStateException("Multiple effective corrections for one fact");
            }
        }, args.toArray());
        return result;
    }

    /** 与物理清理共享点级短事务。未完成原始意图阻止清理越过该键，水位只前进。 */
    public long retireBefore(String pointId, long cutoff) {
        return tx.execute(status -> {
            String key = codec.key(pointId); boundary(key);
            long floor = jdbc.queryForObject("SELECT before_ms FROM biz_telemetry_retention_boundary WHERE point_key=? FOR UPDATE", Long.class, key);
            Long pending = jdbc.queryForObject("SELECT MIN(event_ms) FROM biz_telemetry_raw_intent WHERE point_key=? AND storage_state<>'PERSISTED'", Long.class, key);
            long safe = Math.max(floor, pending == null ? cutoff : Math.min(cutoff, pending));
            jdbc.update("UPDATE biz_telemetry_retention_boundary SET before_ms=? WHERE point_key=?", safe, key);
            // 有争议或处置引用的事实单独保全；普通成功意图随原始证据退役。
            jdbc.update("DELETE FROM biz_telemetry_raw_intent WHERE point_key=? AND event_ms<? AND storage_state='PERSISTED' AND conflict_state='NONE'", key, safe);
            return safe;
        });
    }

    public Fact find(String pointId, long time) {
        List<Fact> rows = jdbc.query("SELECT * FROM biz_telemetry_raw_intent WHERE point_key=? AND event_ms=?", RawTelemetryLedger::mapFact, codec.key(pointId), time);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    Fact locked(String key, long time) {
        return jdbc.queryForObject("SELECT * FROM biz_telemetry_raw_intent WHERE point_key=? AND event_ms=? FOR UPDATE", RawTelemetryLedger::mapFact, key, time);
    }

    public List<Evidence> evidence(Fact fact) {
        return jdbc.query("SELECT * FROM biz_telemetry_conflict_evidence WHERE point_key=? AND event_ms=? AND snapshot_json IS NOT NULL ORDER BY first_seen_ms,evidence_id", (rs, n) -> new Evidence(rs.getString("evidence_id"), rs.getString("payload_hash"), rs.getString("snapshot_json"), rs.getLong("first_seen_ms"), rs.getLong("last_seen_ms"), rs.getLong("attempt_count")), fact.pointKey(), fact.eventTime());
    }

    private void boundary(String key) {
        // 原子 upsert 直接取得排他锁；重复 INSERT 后再升级共享锁会在双实例恢复时死锁。
        jdbc.update("INSERT INTO biz_telemetry_retention_boundary(point_key,before_ms) VALUES (?,0) ON DUPLICATE KEY UPDATE point_key=VALUES(point_key)",key);
    }

    static Fact mapFact(ResultSet rs, int row) throws SQLException {
        return new Fact(rs.getString("point_key"), rs.getLong("event_ms"), rs.getString("point_id"), rs.getString("building_id"), rs.getString("payload_hash"), rs.getString("snapshot_json"), rs.getString("storage_state"), rs.getString("conflict_state"), rs.getInt("conflict_revision"), rs.getObject("first_conflict_ms", Long.class), rs.getString("selected_json"), rs.getInt("selection_version"));
    }

    public static String identity(String pointId, long time) { return pointId + "@" + time; }
    public static String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
