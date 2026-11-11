package com.platform.energy.classification;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 分类版本与人工来源的 MySQL 边界；建筑锁串行化版本发布、导入幂等及重复期间检查。 */
@Repository
public class ClassificationRepository {
    private final JdbcTemplate jdbc;
    public ClassificationRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Version(String id, String buildingId, int no, long revision, String status,
                          Long from, Long to, String json, long author, Long reviewer) {}
    public record Batch(String id, String buildingId, String hash, String status, long revision, long author) {}
    public record StoredReading(String id, String batchId, String json, BigDecimal quantity, long author,
                               String batchStatus, Instant startInclusive) {
        public StoredReading(String id, String batchId, String json, BigDecimal quantity, long author,
                            String batchStatus) {
            this(id,batchId,json,quantity,author,batchStatus,null);
        }
    }
    public record WindowReading(StoredReading reading, Instant endExclusive) {}

    public void lock(String buildingId) {
        // MySQL 上重复键异常会留下共享锁；原子 upsert 直接取得写锁，避免多个事务同时升级锁导致死锁。
        jdbc.update("INSERT INTO biz_energy_classification_scope(building_id,revision) VALUES (?,0) ON DUPLICATE KEY UPDATE revision=revision", buildingId);
        jdbc.queryForObject("SELECT revision FROM biz_energy_classification_scope WHERE building_id=? FOR UPDATE", Long.class, buildingId);
    }
    public boolean buildingExists(String id) { return count("SELECT COUNT(*) FROM building WHERE building_id=? AND del_flag=0", id)>0; }
    public String pointIdForCode(String building,String code) {
        return jdbc.queryForList("SELECT point_id FROM biz_data_point WHERE building_id=? AND point_code=? AND del_flag=0",String.class,building,code)
                .stream().findFirst().orElse(null);
    }
    public boolean objectExists(String building, String type, String id) {
        String sql = switch (type) {
            case "POINT" -> "SELECT COUNT(*) FROM biz_data_point WHERE building_id=? AND point_id=? AND del_flag=0";
            case "EQUIPMENT" -> "SELECT COUNT(*) FROM biz_equipment WHERE building_id=? AND equip_id=? AND del_flag=0";
            case "SPACE" -> "SELECT COUNT(*) FROM biz_space WHERE building_id=? AND space_id=? AND del_flag=0";
            default -> null;
        };
        return sql != null && count(sql, building, id)>0;
    }
    public java.util.Map<String,String> pointEquipment(String building,java.util.Collection<String> pointIds) {
        java.util.Map<String,String> result=new java.util.HashMap<>();
        if(pointIds.isEmpty())return result;
        java.util.List<Object> args=new java.util.ArrayList<>();args.add(building);args.addAll(pointIds);
        String parameters=String.join(",",java.util.Collections.nCopies(pointIds.size(),"?"));
        jdbc.query("SELECT point_id,equip_id FROM biz_data_point WHERE building_id=? AND del_flag=0 AND equip_id IS NOT NULL AND point_id IN ("+parameters+")",r->{result.put(r.getString("point_id"),r.getString("equip_id"));},args.toArray());
        return result;
    }
    public List<Version> versions(String building) {
        return jdbc.query("SELECT * FROM biz_energy_classification_version WHERE building_id=? ORDER BY version_no DESC LIMIT 100", (r,n)->new Version(r.getString("version_id"), r.getString("building_id"), r.getInt("version_no"), r.getLong("revision"), r.getString("status"), (Long)r.getObject("effective_from"), (Long)r.getObject("effective_to"), r.getString("configuration_json"),r.getLong("created_by"),(Long)r.getObject("reviewed_by")), building);
    }
    public Version version(String building, String id) {
        return jdbc.query("SELECT * FROM biz_energy_classification_version WHERE building_id=? AND version_id=?", (r,n)->new Version(r.getString("version_id"),r.getString("building_id"),r.getInt("version_no"),r.getLong("revision"),r.getString("status"),(Long)r.getObject("effective_from"),(Long)r.getObject("effective_to"),r.getString("configuration_json"),r.getLong("created_by"),(Long)r.getObject("reviewed_by")),building,id).stream().findFirst().orElse(null);
    }
    public void insertVersion(String id, String building, String json, String reason, long user) {
        Integer no=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM biz_energy_classification_version WHERE building_id=?",Integer.class,building);
        jdbc.update("INSERT INTO biz_energy_classification_version(version_id,building_id,version_no,revision,status,configuration_json,reason,created_by,created_at) VALUES (?,?,?,0,'DRAFT',?,?,?,?)",id,building,no,json,reason,user,Instant.now().toEpochMilli());
    }
    public int save(Version v, long revision, String json, String reason) {
        return jdbc.update("UPDATE biz_energy_classification_version SET configuration_json=?,reason=?,revision=revision+1 WHERE version_id=? AND revision=? AND status='DRAFT'",json,reason,v.id(),revision);
    }
    public int submit(Version v, long revision, String reason) {
        return jdbc.update("UPDATE biz_energy_classification_version SET status='PENDING_REVIEW',reason=?,revision=revision+1 WHERE version_id=? AND revision=? AND status='DRAFT'",reason,v.id(),revision);
    }
    public int reopen(Version v,long revision,String reason) {
        return jdbc.update("UPDATE biz_energy_classification_version SET status='DRAFT',reason=?,revision=revision+1 WHERE version_id=? AND revision=? AND status='PENDING_REVIEW'",reason,v.id(),revision);
    }
    public int approve(Version v, long revision, Instant from, long reviewer, String reason) {
        // 普通换版只结束跨越新起点的区间，不延长已结束的历史版本。
        jdbc.update("UPDATE biz_energy_classification_version SET effective_to=? WHERE building_id=? AND status='APPROVED' AND (effective_to IS NULL OR effective_to>?)",
                from.toEpochMilli(), v.buildingId(), from.toEpochMilli());
        return jdbc.update("UPDATE biz_energy_classification_version SET status='APPROVED',effective_from=?,reviewed_by=?,reason=?,revision=revision+1 WHERE version_id=? AND revision=? AND status='PENDING_REVIEW'",from.toEpochMilli(),reviewer,reason,v.id(),revision);
    }
    public boolean overlapsApproved(String building, Instant from, Instant to) {
        return !jdbc.queryForList("SELECT version_id FROM biz_energy_classification_version WHERE building_id=? AND status='APPROVED' AND effective_from<? AND (effective_to IS NULL OR effective_to>?) FOR UPDATE",
                String.class, building, to.toEpochMilli(), from.toEpochMilli()).isEmpty();
    }
    public boolean approvedStartsAtOrAfter(String building, Instant from) {
        return !jdbc.queryForList("SELECT version_id FROM biz_energy_classification_version WHERE building_id=? AND status='APPROVED' AND effective_from>=? FOR UPDATE",
                String.class, building, from.toEpochMilli()).isEmpty();
    }
    public int approveHistorical(Version v, long revision, Instant from, Instant to, long reviewer, String reason) {
        return jdbc.update("UPDATE biz_energy_classification_version SET status='APPROVED',effective_from=?,effective_to=?,reviewed_by=?,reason=?,revision=revision+1 WHERE version_id=? AND revision=? AND status='PENDING_REVIEW'",
                from.toEpochMilli(), to.toEpochMilli(), reviewer, reason, v.id(), revision);
    }
    public List<Version> effective(String building, Instant from, Instant to) {
        return jdbc.query("SELECT * FROM biz_energy_classification_version WHERE building_id=? AND status='APPROVED' AND effective_from<=? AND (effective_to IS NULL OR effective_to>=?)", (r,n)->new Version(r.getString("version_id"),r.getString("building_id"),r.getInt("version_no"),r.getLong("revision"),r.getString("status"),(Long)r.getObject("effective_from"),(Long)r.getObject("effective_to"),r.getString("configuration_json"),r.getLong("created_by"),(Long)r.getObject("reviewed_by")),building,from.toEpochMilli(),to.toEpochMilli());
    }
    public Batch batch(String building, String idOrKey, boolean byKey) {
        return jdbc.query("SELECT * FROM biz_energy_manual_batch WHERE building_id=? AND "+(byKey?"idempotency_key":"batch_id")+"=?",(r,n)->new Batch(r.getString("batch_id"),r.getString("building_id"),r.getString("payload_hash"),r.getString("status"),r.getLong("revision"),r.getLong("created_by")),building,idOrKey).stream().findFirst().orElse(null);
    }
    public void insertBatch(String id,String building,String key,String hash,String reason,long user) {
        jdbc.update("INSERT INTO biz_energy_manual_batch(batch_id,building_id,idempotency_key,payload_hash,status,created_by,reason,created_at) VALUES (?,?,?,?,'PENDING_REVIEW',?,?,?)",id,building,key,hash,user,reason,Instant.now().toEpochMilli());
    }
    public int batchReadingCount(String building, String batchId) {
        return Math.toIntExact(count("SELECT COUNT(*) FROM biz_energy_manual_reading WHERE building_id=? AND batch_id=?", building, batchId));
    }
    public void insertReading(String id,String batch,String building,ClassificationContracts.Reading reading,String json,BigDecimal quantity,long user) {
        jdbc.update("INSERT INTO biz_energy_manual_reading(fact_id,batch_id,building_id,point_id,start_inclusive,end_exclusive,reading_json,normalized_quantity,supersedes_fact_id,created_by) VALUES (?,?,?,?,?,?,?,?,?,?)",id,batch,building,reading.pointId(),reading.startInclusive().toEpochMilli(),reading.endExclusive().toEpochMilli(),json,quantity,reading.supersedesFactId(),user);
    }
    public int approveBatch(String building,String id,long revision,long reviewer) {
        return jdbc.update("UPDATE biz_energy_manual_batch SET status='APPROVED',reviewed_by=?,reviewed_at=?,revision=revision+1 WHERE building_id=? AND batch_id=? AND status='PENDING_REVIEW' AND revision=?",reviewer,Instant.now().toEpochMilli(),building,id,revision);
    }
    /** 小时计算按实际审批时间冻结来源；迁移前没有审批时刻的旧批次不能冒充水位之前已审核。 */
    public List<StoredReading> approvedReadingsAsOf(String building,Instant from,Instant to,Instant asOf,int limit) {
        // 替代编号大多为 NULL 时，MySQL 可能为每条候选扫描整楼；显式使用既有替代索引做等值查找。
        // 各分页路径保留同一提示；审批水位和替代关系谓词保持不变。
        return jdbc.query("""
                SELECT /*+ INDEX(r idx_energy_manual_point_period) NO_SEMIJOIN(@hourly_replacement) */ r.*,b.status AS batch_status FROM biz_energy_manual_reading r
                JOIN biz_energy_manual_batch b ON b.batch_id=r.batch_id AND b.building_id=r.building_id
                WHERE r.building_id=? AND r.start_inclusive<? AND r.end_exclusive>?
                  AND b.status='APPROVED' AND b.created_at<=? AND b.reviewed_at<=?
                  AND NOT EXISTS (
                    SELECT /*+ QB_NAME(hourly_replacement) JOIN_ORDER(s,sb) INDEX(s idx_energy_manual_supersedes) */ 1 FROM biz_energy_manual_reading s
                    JOIN biz_energy_manual_batch sb ON sb.batch_id=s.batch_id AND sb.building_id=s.building_id
                    WHERE s.supersedes_fact_id IS NOT NULL AND s.building_id=r.building_id AND s.supersedes_fact_id=r.fact_id
                      AND sb.status='APPROVED' AND sb.created_at<=? AND sb.reviewed_at<=?)
                ORDER BY r.start_inclusive,r.fact_id LIMIT ?
                """,this::stored,building,to.toEpochMilli(),from.toEpochMilli(),asOf.toEpochMilli(),asOf.toEpochMilli(),asOf.toEpochMilli(),asOf.toEpochMilli(),limit);
    }
    public List<StoredReading> readings(String building,Instant from,Instant to,boolean approved) {
        if(approved)return currentApprovedReadingsPage(building,from,to,null,null,5001);
        return jdbc.query("""
                SELECT r.*,b.status AS batch_status FROM biz_energy_manual_reading r
                JOIN biz_energy_manual_batch b ON b.batch_id=r.batch_id AND b.building_id=r.building_id
                WHERE r.building_id=? AND r.start_inclusive<? AND r.end_exclusive>?
                ORDER BY r.start_inclusive,r.fact_id LIMIT 5001
                """,this::stored,building,to.toEpochMilli(),from.toEpochMilli());
    }
    /** 按测点读取当前审核事实，避免单一测点工作流被同楼其他来源的读取上限阻断。 */
    public List<StoredReading> currentApprovedReadings(String building,String point,Instant from,Instant to,int limit) {
        // Keep replacement checks correlated to candidate facts; materializing all history repeats for every day/page.
        return jdbc.query("""
                SELECT /*+ NO_SEMIJOIN(@current_replacement) */ r.*,b.status AS batch_status FROM biz_energy_manual_reading r
                JOIN biz_energy_manual_batch b ON b.batch_id=r.batch_id AND b.building_id=r.building_id
                WHERE r.building_id=? AND r.point_id=? AND r.start_inclusive<? AND r.end_exclusive>?
                  AND b.status='APPROVED'
                  AND NOT EXISTS (
                    SELECT /*+ QB_NAME(current_replacement) JOIN_ORDER(s,sb) INDEX(s idx_energy_manual_supersedes) */ 1 FROM biz_energy_manual_reading s
                    JOIN biz_energy_manual_batch sb ON sb.batch_id=s.batch_id AND sb.building_id=s.building_id
                    WHERE s.building_id=r.building_id AND s.supersedes_fact_id=r.fact_id AND sb.status='APPROVED')
                ORDER BY r.start_inclusive,r.fact_id LIMIT ?
                """,this::stored,building,point,to.toEpochMilli(),from.toEpochMilli(),limit);
    }
    /** 稳定游标分页；替代关系通过候选事实关联查询，并限定在同一建筑。 */
    public List<StoredReading> currentApprovedReadingsPage(String building,Instant from,Instant to,
                                                            Instant afterStartInclusive,String afterFactId,int limit) {
        // The day cursor bounds candidate facts, not the optimizer's otherwise unbounded replacement materialization.
        String prefix="""
                SELECT /*+ NO_SEMIJOIN(@current_replacement) */ r.*,b.status AS batch_status FROM biz_energy_manual_reading r
                JOIN biz_energy_manual_batch b ON b.batch_id=r.batch_id AND b.building_id=r.building_id
                WHERE r.building_id=? AND r.start_inclusive<? AND r.end_exclusive>?
                  AND b.status='APPROVED'
                  AND NOT EXISTS (
                    SELECT /*+ QB_NAME(current_replacement) JOIN_ORDER(s,sb) INDEX(s idx_energy_manual_supersedes) */ 1 FROM biz_energy_manual_reading s
                    JOIN biz_energy_manual_batch sb ON sb.batch_id=s.batch_id AND sb.building_id=s.building_id
                    WHERE s.building_id=r.building_id AND s.supersedes_fact_id=r.fact_id AND sb.status='APPROVED')
                """;
        if(afterStartInclusive==null)return jdbc.query(prefix+" ORDER BY r.start_inclusive,r.fact_id LIMIT ?",this::stored,
                building,to.toEpochMilli(),from.toEpochMilli(),limit);
        return jdbc.query(prefix+" AND (r.start_inclusive>? OR (r.start_inclusive=? AND r.fact_id>?)) ORDER BY r.start_inclusive,r.fact_id LIMIT ?",
                this::stored,building,to.toEpochMilli(),from.toEpochMilli(),afterStartInclusive.toEpochMilli(),afterStartInclusive.toEpochMilli(),afterFactId,limit);
    }
    /** The caller owns temporary files before this transaction starts, including commit-failure cleanup. */
    @org.springframework.transaction.annotation.Transactional(readOnly=true,
            isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,
            propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
            rollbackFor=java.io.IOException.class)
    public void populateMonthlyWindow(MonthlyReadingWindow window) throws java.io.IOException {
        // Pages share this connection/snapshot; daily calculations begin only after it has completed.
        window.scan(this);
    }
    /** 月级共享扫描仍保留完整 overlap；不能以月初作为事实起点下界，否则会漏掉跨月冲突。 */
    @org.springframework.transaction.annotation.Transactional(readOnly=true,
            isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,
            propagation=org.springframework.transaction.annotation.Propagation.REQUIRED)
    public List<WindowReading> currentApprovedWindowPage(String building,Instant from,Instant to,
            Instant afterStartInclusive,String afterFactId,int limit,java.util.function.Supplier<Duration> remaining) {
        Duration available=remaining.get();
        if (available == null || available.isZero() || available.isNegative() || Thread.currentThread().isInterrupted())
            throw new org.springframework.dao.QueryTimeoutException("Monthly source scan budget expired");
        String sql="""
                SELECT /*+ NO_SEMIJOIN(@window_replacement) INDEX(r idx_energy_manual_cursor) */
                       r.fact_id,r.batch_id,r.start_inclusive,r.end_exclusive,r.normalized_quantity,r.created_by,
                       CASE WHEN OCTET_LENGTH(r.reading_json)<=16384 THEN r.reading_json ELSE NULL END AS reading_json,
                       b.status AS batch_status FROM biz_energy_manual_reading r
                JOIN biz_energy_manual_batch b ON b.batch_id=r.batch_id AND b.building_id=r.building_id
                WHERE r.building_id=? AND r.start_inclusive<? AND r.end_exclusive>?
                  AND b.status='APPROVED'
                  AND NOT EXISTS (
                    SELECT /*+ QB_NAME(window_replacement) JOIN_ORDER(s,sb) INDEX(s idx_energy_manual_supersedes) */ 1 FROM biz_energy_manual_reading s
                    JOIN biz_energy_manual_batch sb ON sb.batch_id=s.batch_id AND sb.building_id=s.building_id
                    WHERE s.building_id=r.building_id AND s.supersedes_fact_id=r.fact_id AND sb.status='APPROVED')
                """;
        var args=new ArrayList<Object>(List.of(building,to.toEpochMilli(),from.toEpochMilli()));
        if(afterStartInclusive!=null) {
            sql+=" AND (r.start_inclusive>? OR (r.start_inclusive=? AND r.fact_id>?))";
            args.add(afterStartInclusive.toEpochMilli());args.add(afterStartInclusive.toEpochMilli());args.add(afterFactId);
        }
        sql+=" ORDER BY r.start_inclusive,r.fact_id LIMIT ?";args.add(limit);
        return queryWithinBudget(sql,args,(row,index)->
                new WindowReading(stored(row,index),Instant.ofEpochMilli(row.getLong("end_exclusive"))),remaining);
    }

    public record WindowCandidate(String id,Instant startInclusive,Instant endExclusive) {}

    /** Every returned index candidate advances the cursor, including historical rows outside the month. */
    public List<WindowCandidate> windowCandidatesPage(String building,Instant to,Instant afterStartInclusive,
            String afterFactId,int limit,java.util.function.Supplier<Duration> remaining) {
        return windowCandidatesPage(building,null,to,afterStartInclusive,afterFactId,limit,remaining);
    }

    /** Filters historical non-overlap in the covering index, without imposing a start lower bound. */
    public List<WindowCandidate> overlappingWindowCandidatesPage(String building,Instant from,Instant to,Instant afterStartInclusive,
            String afterFactId,int limit,java.util.function.Supplier<Duration> remaining) {
        java.util.Objects.requireNonNull(from);
        if(!from.isBefore(to))throw new IllegalArgumentException("Monthly source window must be nonempty");
        return windowCandidatesPage(building,from,to,afterStartInclusive,afterFactId,limit,remaining);
    }

    private List<WindowCandidate> windowCandidatesPage(String building,Instant from,Instant to,Instant afterStartInclusive,
            String afterFactId,int limit,java.util.function.Supplier<Duration> remaining) {
        if(limit<1||limit>1000)throw new IllegalArgumentException("Monthly metadata page must contain 1..1000 candidates");
        // Schema V101/V112 defines a four-column covering cursor index; no JSON is projected here.
        String sql="""
                SELECT /*+ INDEX(r idx_energy_manual_cursor) */ r.fact_id,r.start_inclusive,r.end_exclusive
                FROM biz_energy_manual_reading r WHERE r.building_id=? AND r.start_inclusive<?
                """;
        var args=new ArrayList<Object>(List.of(building,to.toEpochMilli()));
        // Reduces returned rows and round trips, not necessarily index pages examined or physical I/O.
        // Do not filter APPROVED/replacement here: candidate integrity and capacity checks precede payload selection.
        // Retain unexpected SQL NULLs rather than relying on a deployed NOT NULL constraint.
        if(from!=null) {sql+=" AND (r.end_exclusive>? OR r.end_exclusive IS NULL)";args.add(from.toEpochMilli());}
        if(afterStartInclusive!=null) {
            sql+=" AND (r.start_inclusive>? OR (r.start_inclusive=? AND r.fact_id>?))";
            args.add(afterStartInclusive.toEpochMilli());args.add(afterStartInclusive.toEpochMilli());args.add(afterFactId);
        }
        sql+=" ORDER BY r.start_inclusive,r.fact_id LIMIT ?";args.add(limit);
        return queryWithinBudget(sql,args,(row,index)->new WindowCandidate(row.getString("fact_id"),
                Instant.ofEpochMilli(row.getLong("start_inclusive")),Instant.ofEpochMilli(row.getLong("end_exclusive"))),remaining);
    }

    /** PK membership bounds full-payload reads, even when an entire ID batch is pending or superseded. */
    public List<WindowReading> currentApprovedWindowByIds(String building,Instant from,Instant to,List<String> ids,
            java.util.function.Supplier<Duration> remaining) {
        if(ids.isEmpty())return List.of();
        if(ids.size()>1000||new java.util.HashSet<>(ids).size()!=ids.size())
            throw new IllegalArgumentException("Monthly payload batch must contain at most 1000 distinct IDs");
        String sql="""
                SELECT /*+ NO_SEMIJOIN(@id_replacement) INDEX(r PRIMARY) JOIN_ORDER(r,b) */
                       r.fact_id,r.batch_id,r.start_inclusive,r.end_exclusive,r.normalized_quantity,r.created_by,
                       CASE WHEN OCTET_LENGTH(r.reading_json)<=16384 THEN r.reading_json ELSE NULL END AS reading_json,
                       b.status AS batch_status FROM biz_energy_manual_reading r
                JOIN biz_energy_manual_batch b ON b.batch_id=r.batch_id AND b.building_id=r.building_id
                WHERE r.building_id=? AND r.start_inclusive<? AND r.end_exclusive>?
                  AND b.status='APPROVED'
                  AND NOT EXISTS (
                    SELECT /*+ QB_NAME(id_replacement) JOIN_ORDER(s,sb) INDEX(s idx_energy_manual_supersedes) */ 1 FROM biz_energy_manual_reading s
                    JOIN biz_energy_manual_batch sb ON sb.batch_id=s.batch_id AND sb.building_id=s.building_id
                    WHERE s.building_id=r.building_id AND s.supersedes_fact_id=r.fact_id AND sb.status='APPROVED')
                """;
        var args=new ArrayList<Object>(List.of(building,to.toEpochMilli(),from.toEpochMilli()));
        sql+=" AND r.fact_id IN ("+String.join(",",java.util.Collections.nCopies(ids.size(),"?"))+") ORDER BY r.fact_id";
        args.addAll(ids);
        return queryWithinBudget(sql,args,(row,index)->
                new WindowReading(stored(row,index),Instant.ofEpochMilli(row.getLong("end_exclusive"))),remaining);
    }

    /** 固定审批水位的主键批读；后于水位批准的替代事实不能遮蔽原事实。 */
    public List<WindowReading> approvedWindowByIdsAsOf(String building,Instant from,Instant to,Instant asOf,List<String> ids,
            java.util.function.Supplier<Duration> remaining) {
        if(ids.isEmpty())return List.of();
        if(ids.size()>256||new java.util.HashSet<>(ids).size()!=ids.size())
            throw new IllegalArgumentException("Reviewed day payload batch must contain at most 256 distinct IDs");
        String sql="""
                SELECT /*+ NO_SEMIJOIN(@day_asof_replacement) INDEX(r PRIMARY) JOIN_ORDER(r,b) */
                       r.fact_id,r.batch_id,r.start_inclusive,r.end_exclusive,r.normalized_quantity,r.created_by,
                       CASE WHEN OCTET_LENGTH(r.reading_json)<=16384 THEN r.reading_json ELSE NULL END AS reading_json,
                       b.status AS batch_status FROM biz_energy_manual_reading r
                JOIN biz_energy_manual_batch b ON b.batch_id=r.batch_id AND b.building_id=r.building_id
                WHERE r.building_id=? AND r.start_inclusive<? AND r.end_exclusive>?
                  AND b.status='APPROVED' AND b.created_at<=? AND b.reviewed_at<=?
                  AND NOT EXISTS (
                    SELECT /*+ QB_NAME(day_asof_replacement) JOIN_ORDER(s,sb) INDEX(s idx_energy_manual_supersedes) */ 1 FROM biz_energy_manual_reading s
                    JOIN biz_energy_manual_batch sb ON sb.batch_id=s.batch_id AND sb.building_id=s.building_id
                    WHERE s.building_id=r.building_id AND s.supersedes_fact_id=r.fact_id AND sb.status='APPROVED' AND sb.created_at<=? AND sb.reviewed_at<=?)
                """;
        var args=new ArrayList<Object>(List.of(building,to.toEpochMilli(),from.toEpochMilli(),
                asOf.toEpochMilli(),asOf.toEpochMilli(),asOf.toEpochMilli(),asOf.toEpochMilli()));
        sql+=" AND r.fact_id IN ("+String.join(",",java.util.Collections.nCopies(ids.size(),"?"))+") ORDER BY r.fact_id";
        args.addAll(ids);
        return queryWithinBudget(sql,args,(row,index)->
                new WindowReading(stored(row,index),Instant.ofEpochMilli(row.getLong("end_exclusive"))),remaining);
    }

    private <T> List<T> queryWithinBudget(String sql,List<Object> args,org.springframework.jdbc.core.RowMapper<T> mapper,
            java.util.function.Supplier<Duration> remaining) {
        Duration available=remaining.get();
        if(available==null||available.isNegative()||available.toMillis()<1||Thread.currentThread().isInterrupted())
            throw new org.springframework.dao.QueryTimeoutException("Monthly source scan budget expired");
        return jdbc.query(connection->{
            Duration left=remaining.get();
            if(left==null||left.isNegative()||left.toMillis()<1||Thread.currentThread().isInterrupted())
                throw new org.springframework.dao.QueryTimeoutException("Monthly source scan budget expired before prepare");
            // JDBC cancellation may lose its connection; the server must also cap this individual SELECT.
            long millis=Math.min(Integer.MAX_VALUE,left.toMillis());int hint=sql.indexOf("/*+")+3;
            if(hint<3)throw new IllegalStateException("Monthly source SELECT requires an optimizer hint block");
            return connection.prepareStatement(sql.substring(0,hint)+" MAX_EXECUTION_TIME("+millis+")"+sql.substring(hint));
        },statement->{
            // Apply after JdbcTemplate defaults; never enlarge an existing smaller transaction timeout.
            Duration left=remaining.get();
            if(left==null||left.isZero()||left.isNegative()||Thread.currentThread().isInterrupted())
                throw new org.springframework.dao.QueryTimeoutException("Monthly source scan budget expired before execution");
            int existing=statement.getQueryTimeout();
            int requested=(int)Math.max(1,Math.min(Integer.MAX_VALUE,left.toSeconds()));
            statement.setQueryTimeout(existing>0?Math.min(existing,requested):requested);
            for(int i=0;i<args.size();i++)statement.setObject(i+1,args.get(i));
        },new org.springframework.jdbc.core.RowMapperResultSetExtractor<>(mapper));
    }
    public List<StoredReading> batchReadings(String building,String batch) {
        return jdbc.query("SELECT r.*,b.status AS batch_status FROM biz_energy_manual_reading r JOIN biz_energy_manual_batch b ON b.batch_id=r.batch_id AND b.building_id=r.building_id WHERE r.building_id=? AND r.batch_id=? ORDER BY r.fact_id",this::stored,building,batch);
    }
    /** 读取同建筑已审核的原始证据，包括已被细化替代的父事实；不把它重新标为当前来源。 */
    public StoredReading approvedReading(String building,String factId) {
        return jdbc.query("""
                SELECT r.*,b.status AS batch_status FROM biz_energy_manual_reading r
                JOIN biz_energy_manual_batch b ON b.batch_id=r.batch_id AND b.building_id=r.building_id
                WHERE r.building_id=? AND r.fact_id=? AND b.status='APPROVED'
                """,this::stored,building,factId).stream().findFirst().orElse(null);
    }
    private StoredReading stored(java.sql.ResultSet r,int row) throws java.sql.SQLException {
        return new StoredReading(r.getString("fact_id"),r.getString("batch_id"),r.getString("reading_json"),
                r.getBigDecimal("normalized_quantity"),r.getLong("created_by"),r.getString("batch_status"),
                Instant.ofEpochMilli(r.getLong("start_inclusive")));
    }
    private long count(String sql,Object... args) { Long n=jdbc.queryForObject(sql,Long.class,args);return n==null?0:n; }
}
