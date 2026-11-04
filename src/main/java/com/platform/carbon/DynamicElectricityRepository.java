package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;

/** 只保存因子与独立监测结果。发布锁属于事务，避免重复导入或并发修订产生重叠的当前因子。 */
@Repository
public class DynamicElectricityRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
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
        return factorRows("""
                SELECT f.* FROM biz_dynamic_electricity_factor f
                WHERE f.building_id=? AND f.start_ms<? AND f.end_ms>?
                AND NOT EXISTS (SELECT 1 FROM biz_dynamic_electricity_factor n
                  WHERE n.building_id=f.building_id AND n.point_id=f.point_id
                    AND n.start_ms=f.start_ms AND n.end_ms=f.end_ms AND n.revision>f.revision)
                ORDER BY f.start_ms,f.point_id LIMIT 50001
                """, building, to.toEpochMilli(), from.toEpochMilli());
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
        heads.add(versionHeads("SELECT point_id,unit FROM biz_data_point WHERE building_id=? ORDER BY point_id", building));
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

    public void save(Result result) {
        String payload = encode(result);
        if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16_000_000)
            throw DynamicElectricityCore.problem("DYNAMIC_CAPACITY", "动态结果证据超过存储上限");
        jdbc.update("""
                INSERT INTO biz_dynamic_electricity_result
                (result_id,building_id,grain,start_ms,end_ms,source_version,payload_json,payload_hash,created_at)
                VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE result_id=result_id
                """, result.id(), result.building(), result.grain(), result.start().toEpochMilli(),
                result.end().toEpochMilli(), result.sourceVersion(), payload, hash(payload), result.capturedAt());
    }

    public List<Result> results(String building, Instant from, Instant to) {
        var values = jdbc.query("""
                SELECT payload_json,payload_hash FROM biz_dynamic_electricity_result
                WHERE building_id=? AND grain<>'LOCK' AND start_ms>=? AND end_ms<=?
                ORDER BY start_ms,end_ms DESC,created_at DESC LIMIT 50001
                """, (r, n) -> decode(r.getString(1), r.getString(2), Result.class), building, from.toEpochMilli(), to.toEpochMilli());
        if (values.size() > 50000) throw DynamicElectricityCore.problem("DYNAMIC_CAPACITY", "动态结果超过读取上限");
        return values;
    }

    public Result byId(String building, String id) {
        return jdbc.query("SELECT payload_json,payload_hash FROM biz_dynamic_electricity_result WHERE building_id=? AND result_id=? AND grain<>'LOCK'",
                (r, n) -> decode(r.getString(1), r.getString(2), Result.class), building, id).stream().findFirst().orElse(null);
    }

    private List<FactorRow> factorRows(String sql, Object... args) {
        var values = jdbc.query(sql, (r, n) -> new FactorRow(decode(r.getString("payload_json"), r.getString("payload_hash"),
                DynamicElectricityCore.Factor.class), r.getInt("revision"), r.getString("import_id"), r.getString("request_hash"), r.getString("payload_hash")), args);
        if (values.size() > 50000) throw DynamicElectricityCore.problem("DYNAMIC_CAPACITY", "动态因子超过读取上限");
        return values;
    }

    private <T> T decode(String payload, String expected, Class<T> type) {
        if (!hash(payload).equals(expected)) throw new IllegalStateException("动态碳记录摘要不一致");
        try { return json.readValue(payload, type); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("动态碳记录无法读取", ex); }
    }

    static String encode(Object value) { return CarbonEvidence.canonical(CarbonEvidence.tree(value)); }
    static String hash(String value) { return CarbonCalculationCore.sha256(value); }
}
