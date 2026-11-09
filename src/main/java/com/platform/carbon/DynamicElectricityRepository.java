package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DataAccessException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.platform.carbon.DynamicElectricityQueryObservation.*;

/** 只保存因子与独立监测结果。发布锁属于事务，避免重复导入或并发修订产生重叠的当前因子。 */
@Repository
public class DynamicElectricityRepository {
    static final int MAX_RESULT_PAYLOAD_BYTES = 16_000_000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private static final int MAX_PROJECTION_BACKFILL_BATCH = 16;
    @org.springframework.beans.factory.annotation.Value("${carbon.dynamic.current-day-batched-read-enabled:false}")
    private boolean currentDayBatchedReadEnabled;

    CurrentReviewedDayVersionRead.Limits currentReviewedDayLimits() {
        return currentDayBatchedReadEnabled ? CurrentReviewedDayVersionRead.BATCHED_LIMITS
                : CurrentReviewedDayVersionRead.DEFAULT_LIMITS;
    }

    public DynamicElectricityRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record FactorRow(DynamicElectricityCore.Factor factor, int revision, String importId,
                            String requestHash, String hash) { }
    public record Result(String id, String building, String grain, Instant start, Instant end,
                         String sourceVersion, String sourceKind, DynamicElectricityCore.Applied values,
                         String electricityHash, com.fasterxml.jackson.databind.JsonNode electricityEvidence,
                         String factorHash, long capturedAt) { }

    /** 图表只持有数值和来源版本；完整证据仍由不可变结果及按ID查询提供。 */
    public record ChartResult(String id, String grain, Instant start, Instant end,
                              String sourceVersion, String sourceKind, ChartValues values) {
        static ChartResult from(Result result) {
            var applied = result.values();
            return new ChartResult(result.id(), result.grain(), result.start(), result.end(),
                    result.sourceVersion(), result.sourceKind(), applied == null ? null :
                    new ChartValues(applied.quantity(), applied.emissionKgCo2(), applied.sourceNatures()));
        }
    }

    public record ChartValues(BigDecimal quantity, BigDecimal emissionKgCo2, List<String> sourceNatures) { }

    public void lock(String building) {
        String id = hash("LOCK:" + building);
        jdbc.update("""
                INSERT INTO biz_dynamic_electricity_result
                (result_id,building_id,grain,start_ms,end_ms,source_version,payload_json,payload_hash,created_at)
                VALUES (?,?,'LOCK',0,0,?,'{}',?,0) ON DUPLICATE KEY UPDATE result_id=result_id
                """, id, building, id, hash("{}"));
        jdbc.queryForObject("SELECT result_id FROM biz_dynamic_electricity_result WHERE result_id=? FOR UPDATE", String.class, id);
    }

    public List<FactorRow> imported(String building, String importId) {
        return factorRows("SELECT * FROM biz_dynamic_electricity_factor WHERE building_id=? AND import_id=? ORDER BY start_ms,point_id", building, importId);
    }

    public List<FactorRow> factors(String building, Instant from, Instant to) {
        return observe(Phase.FACTORS, () -> {
        DynamicElectricityQueryBudget.check();
        var rows = factorRows("""
                SELECT f.* FROM biz_dynamic_electricity_factor f
                WHERE f.building_id=? AND f.start_ms<? AND f.end_ms>?
                AND NOT EXISTS (SELECT 1 FROM biz_dynamic_electricity_factor n
                  WHERE n.building_id=f.building_id AND n.point_id=f.point_id
                    AND n.start_ms=f.start_ms AND n.end_ms=f.end_ms AND n.revision>f.revision)
                ORDER BY f.start_ms,f.point_id LIMIT 50001
                """, building, to.toEpochMilli(), from.toEpochMilli());
        countRows(Count.FACTOR_ROWS, rows);
        return rows;
        });
    }

    public void insertFactor(String building, FactorRow row) {
        var f = row.factor();
        String payload = encode(f);
        jdbc.update("""
                INSERT INTO biz_dynamic_electricity_factor
                (factor_id,building_id,point_id,start_ms,end_ms,revision,import_id,request_hash,payload_json,payload_hash)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, f.id(), building, f.pointId(), f.start().toEpochMilli(), f.end().toEpochMilli(),
                row.revision(), row.importId(), row.requestHash(), payload, hash(payload));
    }

    public boolean pointBelongs(String building, String point) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM biz_data_point WHERE building_id=? AND point_id=? AND del_flag=0", Integer.class, building, point) == 1;
    }

    public boolean buildingExists(String building) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM building WHERE building_id=? AND del_flag=0", Integer.class, building) == 1;
    }

    public String projectVersion(String building) {
        var rows = jdbc.queryForList("SELECT version_id,revision,status,effective_from,effective_to FROM biz_carbon_project_version WHERE building_id=? ORDER BY version_id LIMIT 10001", building);
        if (rows.size() > 10000) throw DynamicElectricityCore.problem("DYNAMIC_CAPACITY", "项目版本超过读取上限");
        return hash(encode(rows));
    }

    /** 小时输入还依赖目录绑定与单位；保守读取已确认版本头，不能让配置修订后的旧结果继续可用。 */
    public String inputMetadataVersion(String building, String sourceKind) {
        var heads = new ArrayList<Object>();
        heads.add(versionHeads("SELECT point_id,unit,equip_id,del_flag,status FROM biz_data_point WHERE building_id=? ORDER BY point_id", building));
        heads.add(versionHeads("""
                SELECT b.point_id,v.binding_version_id,v.config_revision,v.confirmation_status,v.effective_from,v.effective_to
                FROM biz_energy_point_item_binding b JOIN biz_energy_point_item_binding_version v ON v.binding_id=b.binding_id
                WHERE b.building_id=? AND v.confirmation_status='CONFIRMED' ORDER BY v.binding_version_id
                """, building));
        heads.add(versionHeads("""
                SELECT i.item_code,v.version_id,v.config_revision,v.status,v.effective_from,v.effective_to
                FROM biz_energy_item i JOIN biz_energy_item_version v ON v.item_id=i.item_id
                WHERE v.status='APPROVED' ORDER BY v.version_id
                """));
        heads.add(versionHeads("""
                SELECT s.version_id,s.usage_scope FROM biz_energy_item_version_scope s
                JOIN biz_energy_item_version v ON v.version_id=s.version_id
                WHERE v.status='APPROVED' ORDER BY s.version_id,s.usage_scope
                """));
        heads.add(versionHeads("""
                SELECT u.unit_code,v.version_id,v.config_revision,v.status,v.effective_from,v.effective_to
                FROM biz_measurement_unit u JOIN biz_measurement_unit_version v ON v.unit_id=u.unit_id
                WHERE v.status='APPROVED' ORDER BY v.version_id
                """));
        if ("RAW_CUMULATIVE".equals(sourceKind))
            heads.add(versionHeads("SELECT point_id,config_revision FROM biz_energy_point_profile WHERE building_id=? ORDER BY point_id", building));
        return hash(encode(heads));
    }

    private List<Map<String, Object>> versionHeads(String sql, Object... args) {
        var rows = jdbc.queryForList(sql + " LIMIT 10001", args);
        if (rows.size() > 10000) throw DynamicElectricityCore.problem("DYNAMIC_CAPACITY", "动态输入配置版本超过读取上限");
        return rows;
    }

    /** 仅读原始事实账本和质量策略的版本头，不读取TDengine数值或绕过既有质量裁决。 */
    public String rawVersion(String building) {
        var receipts = jdbc.queryForList("SELECT COUNT(*) AS receipt_count,MAX(persisted_at) AS last_persisted FROM biz_telemetry_receipt WHERE building_id=?", building);
        var selections = jdbc.queryForList("SELECT COUNT(*) AS fact_count,MAX(event_ms) AS last_event,SUM(conflict_revision) AS conflicts,SUM(selection_version) AS selections FROM biz_telemetry_raw_intent WHERE building_id=?", building);
        var quality = jdbc.queryForList("SELECT config_revision FROM biz_quality_usage_config_revision ORDER BY singleton_id");
        return hash(encode(List.of(receipts, selections, quality)));
    }

    /** 只核对本结果实际覆盖的输入时段。累计量同时纳入左右锚点，边界更正可使相邻两小时各自失效。 */
    public String electricityVersion(String building,Instant from,Instant to,String sourceKind) {
        return observe(Phase.CURRENT_SOURCE_LEGACY, () -> {
        DynamicElectricityQueryBudget.check();
        if("RAW_CUMULATIVE".equals(sourceKind)) {
            var facts=jdbc.queryForList("SELECT point_id,event_ms,payload_hash,storage_state,conflict_revision,selection_version FROM biz_telemetry_raw_intent WHERE building_id=? AND event_ms>=? AND event_ms<=? ORDER BY point_id,event_ms",building,from.toEpochMilli(),to.toEpochMilli());
            countRows(Count.CURRENT_RAW_ROWS, facts);
            var quality=jdbc.queryForList("SELECT config_revision FROM biz_quality_usage_config_revision ORDER BY singleton_id");
            return hash(encode(List.of(facts,quality,jdbc.queryForList("SELECT point_id,config_revision FROM biz_energy_point_profile WHERE building_id=? ORDER BY point_id",building))));
        }
        // 先按建筑和区间筛选事实，再用替代关系索引检查修订，避免后台复核反复扫描整楼历史。
        var facts=jdbc.queryForList("""
                SELECT /*+ INDEX(r idx_energy_manual_point_period) NO_SEMIJOIN(@dynamic_manual_replacement) */
                       r.fact_id,r.point_id,r.reading_json,r.normalized_quantity,b.revision,b.reviewed_at
                FROM biz_energy_manual_reading r JOIN biz_energy_manual_batch b ON b.batch_id=r.batch_id AND b.building_id=r.building_id
                WHERE r.building_id=? AND r.start_inclusive<? AND r.end_exclusive>? AND b.status='APPROVED'
                  AND NOT EXISTS (
                    SELECT /*+ QB_NAME(dynamic_manual_replacement) JOIN_ORDER(s,sb) */ 1 FROM biz_energy_manual_reading s
                    JOIN biz_energy_manual_batch sb ON sb.batch_id=s.batch_id AND sb.building_id=s.building_id
                    WHERE s.supersedes_fact_id IS NOT NULL AND s.building_id=r.building_id
                      AND s.supersedes_fact_id=r.fact_id AND sb.status='APPROVED')
                ORDER BY r.fact_id
                """,building,to.toEpochMilli(),from.toEpochMilli());
        countRows(Count.CURRENT_RAW_ROWS, facts);
        return hash(encode(facts));
        });
    }

    javax.sql.DataSource currentReviewedDayDataSource() { return jdbc.getDataSource(); }

    /** 仅供同一只读 RR 查询的日内复用；容量不足表示放弃优化，不表示当前来源缺失。 */
    Optional<CurrentReviewedDayVersionRead.DaySnapshot> loadCurrentReviewedDay(String building,Instant from,Instant to,
            java.util.function.Supplier<Duration> remaining) {
        return observe(Phase.DAY_LOAD, () -> CurrentReviewedDayVersionRead.read(this,building,from,to,remaining,currentReviewedDayLimits()));
    }

    List<CurrentReviewedDayVersionRead.Candidate> currentReviewedDayCandidates(String building,Instant from,Instant to,
            String afterId,int limit,java.util.function.Supplier<Duration> remaining) {
        if(limit<1||limit>currentReviewedDayLimits().metadataPageSize())throw new IllegalArgumentException("Invalid current reviewed day metadata page size");
        // 窄覆盖索引只返回真实相交区间；按数据库主键排序和游标比较，不能用 Java 排序替代数据库排序规则。
        String sql="""
                SELECT /*+ INDEX(r idx_energy_manual_point_period) */ r.fact_id,r.start_inclusive,r.end_exclusive
                FROM biz_energy_manual_reading r
                WHERE r.building_id=? AND r.start_inclusive<? AND r.end_exclusive>?
                """;
        var args=new ArrayList<Object>(List.of(building,to.toEpochMilli(),from.toEpochMilli()));
        if(afterId!=null){sql+=" AND r.fact_id>?";args.add(afterId);}
        sql+=" ORDER BY r.fact_id LIMIT ?";args.add(limit);
        String query = sql;
        return observe(Phase.CANDIDATE_HEADERS, () -> {
        DynamicElectricityQueryBudget.check();
        var rows = currentReviewedDayQuery(query,args,(row,index)-> {
            String id=row.getString("fact_id");
            long start=row.getLong("start_inclusive");
            if(row.wasNull())throw CurrentReviewedDayVersionRead.integrity();
            long end=row.getLong("end_exclusive");
            if(row.wasNull())throw CurrentReviewedDayVersionRead.integrity();
            return new CurrentReviewedDayVersionRead.Candidate(id,start,end);
        },remaining);
        countRows(Count.CANDIDATE_ROWS, rows);
        return rows;
        });
    }

    List<CurrentReviewedDayVersionRead.Fact> currentReviewedDayBodies(String building,Instant from,Instant to,
            List<String> ids,java.util.function.Supplier<Duration> remaining) {
        if(ids.isEmpty())return List.of();
        if(ids.size()>currentReviewedDayLimits().bodyPageSize()||new HashSet<>(ids).size()!=ids.size())
            throw new IllegalArgumentException("Current reviewed day body batch exceeds configured size or contains duplicate IDs");
        String sql="""
                SELECT /*+ INDEX(r PRIMARY) JOIN_ORDER(r,b) NO_SEMIJOIN(@dynamic_day_manual_replacement) */
                       r.fact_id,r.point_id,
                       CASE WHEN OCTET_LENGTH(r.reading_json)<=16384 THEN r.reading_json ELSE NULL END AS reading_json,
                       r.normalized_quantity,b.revision,b.reviewed_at,
                       r.start_inclusive AS snapshot_start,r.end_exclusive AS snapshot_end,
                       OCTET_LENGTH(r.reading_json) AS snapshot_bytes
                FROM biz_energy_manual_reading r JOIN biz_energy_manual_batch b ON b.batch_id=r.batch_id AND b.building_id=r.building_id
                WHERE r.building_id=? AND r.start_inclusive<? AND r.end_exclusive>? AND b.status='APPROVED'
                  AND NOT EXISTS (
                    SELECT /*+ QB_NAME(dynamic_day_manual_replacement) JOIN_ORDER(s,sb) */ 1 FROM biz_energy_manual_reading s
                    JOIN biz_energy_manual_batch sb ON sb.batch_id=s.batch_id AND sb.building_id=s.building_id
                    WHERE s.supersedes_fact_id IS NOT NULL AND s.building_id=r.building_id
                      AND s.supersedes_fact_id=r.fact_id AND sb.status='APPROVED')
                """;
        sql+=" AND r.fact_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY r.fact_id";
        var args=new ArrayList<Object>(List.of(building,to.toEpochMilli(),from.toEpochMilli()));args.addAll(ids);
        var mapper=new org.springframework.jdbc.core.ColumnMapRowMapper();
        String query = sql;
        return observe(Phase.CURRENT_RAW_BODIES, () -> {
        DynamicElectricityQueryBudget.check();
        var rows = currentReviewedDayQuery(query,args,(row,index)-> {
            // 与原 queryForList 使用同一个 JDBC 映射器，保留六个字段的名称、NULL 和数值类型。
            var fields=mapper.mapRow(row,index);
            Object start=fields.remove("snapshot_start"),end=fields.remove("snapshot_end"),bytes=fields.remove("snapshot_bytes");
            if(!(start instanceof Number)||!(end instanceof Number)||(bytes!=null&&!(bytes instanceof Number)))
                throw CurrentReviewedDayVersionRead.integrity();
            return new CurrentReviewedDayVersionRead.Fact(new CurrentReviewedDayVersionRead.Candidate(
                    row.getString("fact_id"),((Number)start).longValue(),((Number)end).longValue()),
                    fields,bytes==null?null:((Number)bytes).longValue());
        },remaining);
        countRows(Count.CURRENT_RAW_ROWS, rows);
        return rows;
        });
    }

    java.util.function.Supplier<Duration> currentReviewedDayStatementBudget(java.util.function.Supplier<Duration> remaining) {
        return CurrentReviewedDayVersionRead.statementBudget(remaining,System::nanoTime);
    }

    private <T> List<T> currentReviewedDayQuery(String sql,List<Object> args,org.springframework.jdbc.core.RowMapper<T> mapper,
            java.util.function.Supplier<Duration> remaining) {
        var statementRemaining=currentReviewedDayStatementBudget(remaining);
        return jdbc.query(connection->{
            Duration left=CurrentReviewedDayVersionRead.executionAvailable(statementRemaining);
            long millis=Math.min(Integer.MAX_VALUE,left.toMillis());int hint=sql.indexOf("/*+")+3;
            if(hint<3)throw new IllegalStateException("Current reviewed day SELECT requires an optimizer hint block");
            // JDBC 超时可能关闭连接；服务器也必须限制当前 SELECT，SQL 失败由上层直接传播而不是降级重试。
            return connection.prepareStatement(sql.substring(0,hint)+" MAX_EXECUTION_TIME("+millis+")"+sql.substring(hint));
        },statement->{
            for(int index=0;index<args.size();index++)statement.setObject(index+1,args.get(index));
            int existing=statement.getQueryTimeout();
            Duration left=CurrentReviewedDayVersionRead.executionAvailable(statementRemaining);
            int requested=(int)Math.min(Integer.MAX_VALUE,left.toSeconds());
            statement.setQueryTimeout(existing>0?Math.min(existing,requested):requested);
        },new org.springframework.jdbc.core.RowMapperResultSetExtractor<>(mapper));
    }

    public void save(Result result) {
        String payload = encode(result);
        requirePayloadSize(payload);
        savePayload(result,payload,hash(payload));
    }

    /** 完整校验并解码暂存原文后直接持久化同一原文，避免发布时再复制/排序/序列化全部证据。 */
    Result saveStaged(String payload,String expectedHash) {
        requirePayloadSize(payload);
        var result=decode(payload,expectedHash,Result.class);
        savePayload(result,payload,expectedHash);
        return result;
    }

    private void savePayload(Result result,String payload,String payloadHash) {
        jdbc.update("""
                INSERT INTO biz_dynamic_electricity_result
                (result_id,building_id,grain,start_ms,end_ms,source_version,payload_json,payload_hash,created_at)
                VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE result_id=result_id
                """, result.id(), result.building(), result.grain(), result.start().toEpochMilli(),
                result.end().toEpochMilli(), result.sourceVersion(), payload, payloadHash, result.capturedAt());
        String persistedHash=jdbc.queryForObject("SELECT payload_hash FROM biz_dynamic_electricity_result WHERE result_id=?",String.class,result.id());
        if(!Objects.equals(persistedHash,payloadHash)) {
            // 同来源幂等重算会有新的 capturedAt；保留首次证据，其他内容必须完全一致。
            Result existing=byId(result.building(),result.id());
            if(existing==null||!semanticResultHash(existing).equals(semanticResultHash(result)))
                throw projectionProblem("DYNAMIC_PROJECTION_INTEGRITY","同一动态结果标识对应不同证据");
        }
        if(!"LOCK".equals(result.grain()))saveChartProjection(result,persistedHash);
    }

    private static void requirePayloadSize(String payload) {
        if(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_RESULT_PAYLOAD_BYTES)
            throw DynamicElectricityCore.problem("DYNAMIC_CAPACITY", "动态结果证据超过存储上限");
    }

    /** 与调用方共用只读 RR 快照；图表读取只访问已完整校验后保存的轻量投影。 */
    public List<ChartResult> results(String building, Instant from, Instant to) {
        DynamicElectricityQueryBudget.check();
        return observe(Phase.STORED_HEADERS, () -> jdbc.query("""
                SELECT r.result_id,r.building_id,r.grain,r.start_ms,r.end_ms,r.source_version,
                       r.payload_hash AS evidence_hash,p.source_kind,p.evidence_hash AS projection_evidence_hash,
                       p.chart_json,p.chart_hash,p.integrity_error
                FROM biz_dynamic_electricity_result r
                LEFT JOIN biz_dynamic_electricity_chart_projection p ON p.result_id=r.result_id
                WHERE r.building_id=? AND r.grain NOT IN ('LOCK','INTERVAL') AND r.start_ms>=? AND r.end_ms<=?
                ORDER BY r.start_ms,r.end_ms DESC,r.created_at DESC LIMIT 50001
                """, (r,n) -> {
            DynamicElectricityQueryBudget.check();
            if(n>=50000)throw DynamicElectricityCore.problem("DYNAMIC_CAPACITY","动态结果超过读取上限");
            return decodeProjectionRow(r,building);
        }, building, from.toEpochMilli(), to.toEpochMilli()));
    }

    /** 设备当前指针也必须引用完整证据验证后的投影，不能单独信任任务中的摘要正文。 */
    public List<ChartResult> currentDeviceResults(String building,Instant from,Instant to,String metadata) {
        return jdbc.query("""
                SELECT r.result_id,r.building_id,r.grain,r.start_ms,r.end_ms,r.source_version,
                       r.payload_hash AS evidence_hash,p.source_kind,p.evidence_hash AS projection_evidence_hash,
                       p.chart_json,p.chart_hash,p.integrity_error
                FROM biz_dynamic_electricity_task t
                LEFT JOIN biz_dynamic_electricity_result r ON r.result_id=t.result_id AND r.building_id=t.building_id AND r.start_ms=t.start_ms AND r.end_ms=t.end_ms
                LEFT JOIN biz_dynamic_electricity_chart_projection p ON p.result_id=r.result_id
                WHERE t.building_id=? AND t.start_ms>=? AND t.end_ms<=? AND t.source_kind='DEVICE_INTERVAL' AND t.state='READY' AND t.metadata_version=?
                ORDER BY t.start_ms LIMIT 50001
                """,(r,n)->{
                    DynamicElectricityQueryBudget.check();if(n>=50000)throw DynamicElectricityCore.problem("DYNAMIC_CAPACITY","动态结果超过读取上限");
                    var chart=decodeProjectionRow(r,building);
                    if(!"DEVICE_INTERVAL".equals(chart.sourceKind()))throw projectionProblem("DYNAMIC_PROJECTION_INTEGRITY","设备结果来源类型不一致");
                    return chart;
                },
                building,from.toEpochMilli(),to.toEpochMilli(),metadata);
    }

    private ChartResult decodeProjectionRow(java.sql.ResultSet row,String expectedBuilding) throws java.sql.SQLException {
        String id=row.getString("result_id");
        if(id==null)throw new IllegalStateException("动态碳记录标识缺失");
        if(!expectedBuilding.equals(row.getString("building_id")))
            throw projectionProblem("DYNAMIC_PROJECTION_INTEGRITY","动态结果展示投影建筑范围不一致");
        String payload=row.getString("chart_json"),digest=row.getString("chart_hash");
        String evidenceHash=row.getString("evidence_hash");
        if(payload==null||digest==null||evidenceHash==null)
            throw projectionProblem("DYNAMIC_PROJECTION_PREPARING","动态结果展示投影尚未就绪");
        if(row.getString("integrity_error")!=null)
            throw projectionProblem("DYNAMIC_PROJECTION_INTEGRITY","动态结果完整性巡检未通过");
        if(!Objects.equals(evidenceHash,row.getString("projection_evidence_hash")))
            throw projectionProblem("DYNAMIC_PROJECTION_INTEGRITY","动态结果证据引用不一致");
        if(!hash(payload).equals(digest))throw projectionProblem("DYNAMIC_PROJECTION_INTEGRITY","动态结果展示投影摘要不一致");
        ChartResult chart;
        try { chart=json.readValue(payload,ChartResult.class); }
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("动态结果展示投影无法读取",ex);
        }
        if(chart==null||!id.equals(chart.id())||!row.getString("grain").equals(chart.grain())
                ||row.getLong("start_ms")!=chart.start().toEpochMilli()
                ||row.getLong("end_ms")!=chart.end().toEpochMilli()
                ||!row.getString("source_version").equals(chart.sourceVersion())
                ||!row.getString("source_kind").equals(chart.sourceKind()))
            throw projectionProblem("DYNAMIC_PROJECTION_INTEGRITY","动态结果展示投影身份不一致");
        count(Count.STORED_ROWS,1);
        count(Count.STORED_BYTES,payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        return chart;
    }

    /** 将完整证据已校验的结果投影单独保存，证据摘要作为回查指针。 */
    private void saveChartProjection(Result result,String evidenceHash) {
        ChartResult chart=ChartResult.from(result);
        String payload=encode(chart),digest=hash(payload);
        jdbc.update("""
                INSERT INTO biz_dynamic_electricity_chart_projection
                (result_id,building_id,grain,start_ms,end_ms,source_version,source_kind,evidence_hash,chart_json,chart_hash,created_at,last_verified_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE result_id=result_id
                """,chart.id(),result.building(),chart.grain(),chart.start().toEpochMilli(),chart.end().toEpochMilli(),
                chart.sourceVersion(),chart.sourceKind(),evidenceHash,payload,digest,result.capturedAt(),System.currentTimeMillis());
        jdbc.query("""
                SELECT building_id,grain,start_ms,end_ms,source_version,source_kind,evidence_hash,chart_json,chart_hash,integrity_error
                FROM biz_dynamic_electricity_chart_projection WHERE result_id=?
                """,r->{
            ChartResult existing=decodeProjectionRow(r,result.building(),chart.id());
            if(r.getString("integrity_error")!=null||!digest.equals(hash(encode(existing)))||!Objects.equals(r.getString("evidence_hash"),evidenceHash))
                throw projectionProblem("DYNAMIC_PROJECTION_INTEGRITY","动态结果展示投影与已发布证据不匹配");
        },chart.id());
    }

    private ChartResult decodeProjectionRow(java.sql.ResultSet row,String expectedBuilding,String expectedId) throws java.sql.SQLException {
        String payload=row.getString("chart_json"),digest=row.getString("chart_hash");
        if(payload==null||digest==null||!hash(payload).equals(digest))
            throw projectionProblem("DYNAMIC_PROJECTION_INTEGRITY","动态结果展示投影摘要不一致");
        ChartResult chart;
        try { chart=json.readValue(payload,ChartResult.class); }
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("动态结果展示投影无法读取",ex); }
        if(chart==null||!expectedId.equals(chart.id())||!expectedBuilding.equals(row.getString("building_id"))
                ||!row.getString("grain").equals(chart.grain())||row.getLong("start_ms")!=chart.start().toEpochMilli()
                ||row.getLong("end_ms")!=chart.end().toEpochMilli()||!row.getString("source_version").equals(chart.sourceVersion())
                ||!row.getString("source_kind").equals(chart.sourceKind()))
            throw projectionProblem("DYNAMIC_PROJECTION_INTEGRITY","动态结果展示投影身份不一致");
        return chart;
    }

    /** 每次最多16条。每条完整校验后写入，worker中断时下次只重试缺失项。 */
    public int backfillChartProjections(int limit) {
        if(limit<1||limit>MAX_PROJECTION_BACKFILL_BATCH)throw new IllegalArgumentException("动态结果投影回填批次必须在1到16之间");
        var ids=jdbc.query("""
                SELECT r.result_id FROM biz_dynamic_electricity_result r
                LEFT JOIN biz_dynamic_electricity_chart_projection p ON p.result_id=r.result_id
                WHERE r.grain<>'LOCK' AND p.result_id IS NULL
                ORDER BY r.created_at,r.result_id LIMIT ?
                """,(row,n)->row.getString(1),limit);
        for(String id:ids) {
            try {
                var stored=jdbc.query("""
                        SELECT result_id,building_id,grain,start_ms,end_ms,source_version,
                               CASE WHEN OCTET_LENGTH(payload_json)<=? THEN payload_json ELSE NULL END AS payload_json,
                               payload_hash,OCTET_LENGTH(payload_json) AS payload_bytes
                        FROM biz_dynamic_electricity_result WHERE result_id=? AND grain<>'LOCK'
                        """,(row,n)->decodeFullForProjection(row),MAX_RESULT_PAYLOAD_BYTES,id);
                if(stored.size()!=1)throw new IllegalStateException("动态结果在回填时缺失或重复");
                saveChartProjection(stored.getFirst().result(),stored.getFirst().evidenceHash());
            } catch(DataAccessException ex) {
                throw ex;
            } catch(RuntimeException ex) {
                markProjectionFailure(id,integrityCode(ex));
                org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                        "Dynamic chart projection backfill rejected: resultId={}, code={}",id,integrityCode(ex));
            }
        }
        return ids.size();
    }

    private void markProjectionFailure(String id,String reason) {
        jdbc.update("""
                INSERT INTO biz_dynamic_electricity_chart_projection
                (result_id,building_id,grain,start_ms,end_ms,source_version,source_kind,evidence_hash,chart_json,chart_hash,
                 last_verified_at,integrity_error,created_at)
                SELECT result_id,building_id,grain,start_ms,end_ms,source_version,'UNKNOWN',payload_hash,'{}',?,
                       0,?,created_at
                FROM biz_dynamic_electricity_result WHERE result_id=? AND grain<>'LOCK'
                ON DUPLICATE KEY UPDATE result_id=result_id
                """,hash("{}"),reason,id);
    }

    private String integrityCode(RuntimeException ex) {
        String code=ex instanceof com.platform.framework.exception.BusinessException business
                ? Objects.toString(business.getErrorCode(),"INTEGRITY_FAILED") : "INTEGRITY_FAILED";
        return code.length()<=64?code:"INTEGRITY_FAILED";
    }

    public record IntegrityAudit(int checked,int failed) { }
    private static com.platform.framework.exception.BusinessException projectionProblem(String code,String message) {
        return new com.platform.framework.exception.BusinessException(503,code,message);
    }
    private static String semanticResultHash(Result result) {
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)CarbonEvidence.tree(result);
        value.remove("capturedAt");return CarbonEvidence.canonicalHash(value);
    }

    /** 每次最多校验16条24小时未复核的投影，隔离坏记录后继续处理其余记录。 */
    public IntegrityAudit verifyChartProjectionIntegrity(int limit) {
        if(limit<1||limit>16)throw new IllegalArgumentException("动态结果完整性巡检批次必须在1到16之间");
        long now=System.currentTimeMillis(),cutoff=now-24L*60*60*1000;
        var ids=jdbc.query("""
                SELECT result_id FROM biz_dynamic_electricity_chart_projection
                WHERE integrity_error IS NULL AND last_verified_at<?
                ORDER BY last_verified_at,result_id LIMIT ?
                """,(row,n)->row.getString(1),cutoff,limit);
        int failed=0;
        for(String id:ids) {
            try {
                var source=jdbc.query("""
                        SELECT result_id,building_id,grain,start_ms,end_ms,source_version,
                               CASE WHEN OCTET_LENGTH(payload_json)<=? THEN payload_json ELSE NULL END AS payload_json,
                               payload_hash,OCTET_LENGTH(payload_json) AS payload_bytes
                        FROM biz_dynamic_electricity_result WHERE result_id=? AND grain<>'LOCK'
                        """,(row,n)->decodeFullForProjection(row),MAX_RESULT_PAYLOAD_BYTES,id);
                var projections=jdbc.query("""
                        SELECT building_id,grain,start_ms,end_ms,source_version,source_kind,evidence_hash,chart_json,chart_hash,integrity_error
                        FROM biz_dynamic_electricity_chart_projection WHERE result_id=?
                        """,(row,n)->new ProjectionAudit(decodeProjectionRow(row,row.getString("building_id"),id),
                                row.getString("evidence_hash"),row.getString("chart_hash"),row.getString("integrity_error")),id);
                if(source.size()!=1||projections.size()!=1)throw new IllegalStateException("动态结果或展示投影缺失/重复");
                var stored=source.getFirst();var projection=projections.getFirst();
                String expectedChartHash=hash(encode(ChartResult.from(stored.result())));
                if(projection.integrityError()!=null
                        ||!Objects.equals(projection.evidenceHash(),stored.evidenceHash())
                        ||!Objects.equals(projection.chartHash(),expectedChartHash))
                    throw new IllegalStateException("动态结果展示投影与完整证据不一致");
                int verified=jdbc.update("""
                        UPDATE biz_dynamic_electricity_chart_projection p SET last_verified_at=?
                        WHERE p.result_id=? AND p.chart_hash=? AND p.evidence_hash=? AND p.integrity_error IS NULL
                          AND EXISTS (SELECT 1 FROM biz_dynamic_electricity_result r WHERE r.result_id=p.result_id AND r.payload_hash=p.evidence_hash)
                        """,now,id,projection.chartHash(),projection.evidenceHash());
                if(verified==0)jdbc.update("""
                        UPDATE biz_dynamic_electricity_chart_projection p SET integrity_error='SOURCE_CHANGED'
                        WHERE p.result_id=? AND p.integrity_error IS NULL
                          AND EXISTS (SELECT 1 FROM biz_dynamic_electricity_result r WHERE r.result_id=p.result_id AND r.payload_hash<>p.evidence_hash)
                        """,id);
            } catch(RuntimeException ex) {
                if(ex instanceof DataAccessException dataAccess)throw dataAccess;
                String reason=ex instanceof com.platform.framework.exception.BusinessException business
                        ? Objects.toString(business.getErrorCode(),"INTEGRITY_FAILED") : "INTEGRITY_FAILED";
                jdbc.update("UPDATE biz_dynamic_electricity_chart_projection SET integrity_error=? WHERE result_id=? AND integrity_error IS NULL",
                        reason,id);
                org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                        "Dynamic chart evidence audit failed: resultId={}, code={}",id,reason);
                failed++;
            }
        }
        return new IntegrityAudit(ids.size(),failed);
    }

    private record ProjectionAudit(ChartResult chart,String evidenceHash,String chartHash,String integrityError) { }

    private record FullProjectionSource(Result result,String evidenceHash) { }
    private FullProjectionSource decodeFullForProjection(java.sql.ResultSet row) throws java.sql.SQLException {
        String id=row.getString("result_id"),building=row.getString("building_id"),grain=row.getString("grain");
        long start=row.getLong("start_ms"),end=row.getLong("end_ms");
        String version=row.getString("source_version");
        long bytes=row.getLong("payload_bytes");
        if(row.wasNull()||bytes<0)throw new IllegalStateException("动态碳记录字节数无效");
        if(bytes>MAX_RESULT_PAYLOAD_BYTES)throw DynamicElectricityCore.problem("DYNAMIC_CAPACITY","动态结果证据超过读取上限");
        String payload=row.getString("payload_json"),digest=row.getString("payload_hash");
        if(payload==null)throw new IllegalStateException("动态碳记录正文缺失");
        Result result=decode(payload,digest,Result.class);
        if(!id.equals(result.id())||!building.equals(result.building())||!grain.equals(result.grain())
                ||start!=result.start().toEpochMilli()||end!=result.end().toEpochMilli()
                ||!version.equals(result.sourceVersion()))
            throw projectionProblem("DYNAMIC_PROJECTION_INTEGRITY","动态结果正文与索引身份不一致");
        return new FullProjectionSource(result,digest);
    }

    public Result byId(String building, String id) {
        return jdbc.query("SELECT payload_json,payload_hash FROM biz_dynamic_electricity_result WHERE building_id=? AND result_id=? AND grain<>'LOCK'",
                (r, n) -> decode(r.getString(1), r.getString(2), Result.class), building, id).stream().findFirst().orElse(null);
    }

    void visitIntervalResults(String building,long from,long to,java.util.function.Consumer<Result> visitor) {
        jdbc.query("""
                SELECT payload_json,payload_hash FROM biz_dynamic_electricity_result
                WHERE building_id=? AND grain='INTERVAL' AND start_ms=? AND end_ms=?
                """,r->{visitor.accept(decode(r.getString(1),r.getString(2),Result.class));},building,from,to);
    }

    private List<FactorRow> factorRows(String sql, Object... args) {
        var values = jdbc.query(sql, (r, n) -> new FactorRow(decode(r.getString("payload_json"), r.getString("payload_hash"),
                DynamicElectricityCore.Factor.class), r.getInt("revision"), r.getString("import_id"), r.getString("request_hash"), r.getString("payload_hash")), args);
        if (values.size() > 50000) throw DynamicElectricityCore.problem("DYNAMIC_CAPACITY", "动态因子超过读取上限");
        return values;
    }

    private <T> T decode(String payload, String expected, Class<T> type) {
        DynamicElectricityQueryBudget.check();
        if (!hash(payload).equals(expected)) throw new IllegalStateException("动态碳记录摘要不一致");
        try {
            T value = json.readValue(payload, type);
            DynamicElectricityQueryBudget.check();
            return value;
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("动态碳记录无法读取", ex); }
    }

    static String encode(Object value) { return CarbonEvidence.canonical(CarbonEvidence.tree(value)); }
    static String hash(String value) { return CarbonCalculationCore.sha256(value); }
}
