package com.platform.energy.efficiency;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** 只访问冷站模块自己的配置和任务表；条件更新阻止旧执行提交新结果。 */
@Repository
@RequiredArgsConstructor
public class EerpRepository {
    private final JdbcTemplate jdbc;
    public record ConfigRow(String id, String building, String station, String status, long revision,
            Instant from, Instant to, String json, String relationEvidence, long creator, Long submitter, Long reviewer) {}
    public record TaskRow(String id, String building, String station, String kind, String key,
            String requestHash, String request, String predecessor, String status, long revision,
            long creator, Long submitter, Long reviewer, String lease, Instant leaseUntil, String stage,
            String evidenceHash, String failure, Instant createdAt, String traceId) {}
    private final RowMapper<ConfigRow> configs = (r,n) -> new ConfigRow(r.getString("version_id"),
            r.getString("building_id"),r.getString("station_id"),r.getString("status"),r.getLong("revision"),
            r.getTimestamp("effective_from").toInstant(),r.getTimestamp("effective_to").toInstant(),
            r.getString("config_json"),r.getString("relation_evidence"),r.getLong("created_by"),
            r.getObject("submitted_by",Long.class),r.getObject("approved_by",Long.class));
    private final RowMapper<TaskRow> tasks = (r,n) -> new TaskRow(r.getString("task_id"),r.getString("building_id"),
            r.getString("station_id"),r.getString("task_kind"),r.getString("idempotency_key"),r.getString("request_hash"),
            r.getString("request_json"),r.getString("predecessor_task_id"),r.getString("status"),r.getLong("revision"),
            r.getLong("created_by"),r.getObject("submitted_by",Long.class),r.getObject("approved_by",Long.class),r.getString("lease_token"),
            r.getTimestamp("lease_until") == null ? null : r.getTimestamp("lease_until").toInstant(),
            r.getString("stage_json"),r.getString("evidence_hash"),r.getString("failure_code"),r.getTimestamp("created_at").toInstant(),r.getString("trace_id"));
    public void stationGuard(String building, String station) {
        jdbc.update("INSERT INTO energy_eerp_station_guard(building_id,station_id) VALUES (?,?) ON DUPLICATE KEY UPDATE station_id=station_id", building,station);
        jdbc.queryForObject("SELECT station_id FROM energy_eerp_station_guard WHERE building_id=? AND station_id=? FOR UPDATE",String.class,building,station);
    }
    public ConfigRow config(String id) { return one(jdbc.query("SELECT * FROM energy_eerp_config WHERE version_id=?",configs,id)); }
    public List<ConfigRow> configs(String b,String s,int limit) {
        return jdbc.query("SELECT * FROM energy_eerp_config WHERE building_id=? AND station_id=? ORDER BY created_at DESC,version_id LIMIT ?",configs,b,s,limit);
    }
    public void insertConfig(ConfigRow c) {
        jdbc.update("INSERT INTO energy_eerp_config(version_id,building_id,station_id,status,revision,effective_from,effective_to,config_json,relation_evidence,created_by,created_at) VALUES (?,?,?,'DRAFT',0,?,?,?,?,?,?)",
                c.id(),c.building(),c.station(),Timestamp.from(c.from()),Timestamp.from(c.to()),c.json(),c.relationEvidence(),c.creator(),Timestamp.from(Instant.now()));
    }
    public boolean configOverlap(ConfigRow c) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM energy_eerp_config WHERE building_id=? AND station_id=? AND status='ACTIVE' AND effective_from<? AND effective_to>?",Integer.class,
                c.building(),c.station(),Timestamp.from(c.to()),Timestamp.from(c.from())) > 0;
    }
    public int transitionConfig(String id,long rev,String before,String after,long user,String reason) {
        if ("ACTIVE".equals(after)) return jdbc.update("UPDATE energy_eerp_config SET status=?,revision=revision+1,review_reason=? WHERE version_id=? AND revision=? AND status=?",after,reason,id,rev,before);
        String actor = "SUBMITTED".equals(after) ? "submitted_by" : "approved_by";
        return jdbc.update("UPDATE energy_eerp_config SET status=?,revision=revision+1,"+actor+"=?,review_reason=? WHERE version_id=? AND revision=? AND status=?",after,user,reason,id,rev,before);
    }
    public TaskRow task(String id) { return one(jdbc.query("SELECT * FROM energy_eerp_task WHERE task_id=?",tasks,id)); }
    public TaskRow byKey(String b,String s,String key) { return one(jdbc.query("SELECT * FROM energy_eerp_task WHERE building_id=? AND station_id=? AND idempotency_key=?",tasks,b,s,key)); }
    public List<TaskRow> tasks(String b,String s,int limit) { return jdbc.query("SELECT * FROM energy_eerp_task WHERE building_id=? AND station_id=? ORDER BY created_at DESC,task_id LIMIT ?",tasks,b,s,limit); }
    public void insertTask(TaskRow t) {
        jdbc.update("INSERT INTO energy_eerp_task(task_id,building_id,station_id,task_kind,idempotency_key,request_hash,request_json,predecessor_task_id,status,revision,created_by,created_at,trace_id) VALUES (?,?,?,?,?,?,?,?,?,0,?,?,?)",
                t.id(),t.building(),t.station(),t.kind(),t.key(),t.requestHash(),t.request(),t.predecessor(),t.status(),t.creator(),Timestamp.from(t.createdAt()),t.traceId());
    }
    public void executionGuard() { jdbc.queryForObject("SELECT guard_id FROM energy_eerp_execution_guard WHERE guard_id=1 FOR UPDATE",Integer.class); }
    public int running(Instant now) { return jdbc.queryForObject("SELECT COUNT(*) FROM energy_eerp_task WHERE status='RUNNING' AND lease_until>?",Integer.class,Timestamp.from(now)); }
    public int claim(String id,long rev,String token,Instant until,Instant now) {
        return jdbc.update("UPDATE energy_eerp_task SET status='RUNNING',revision=revision+1,lease_token=?,lease_until=?,failure_code=NULL WHERE task_id=? AND revision=? AND (status IN ('READY','FAILED') OR (status='RUNNING' AND lease_until<=?))",
                token,Timestamp.from(until),id,rev,Timestamp.from(now));
    }
    public int stage(String id,String token,String stage,String hash) {
        return jdbc.update("UPDATE energy_eerp_task SET stage_json=?,evidence_hash=? WHERE task_id=? AND lease_token=? AND status='RUNNING' AND stage_json IS NULL",stage,hash,id,token);
    }
    public int finish(String id,String token,String status,String failure) {
        return jdbc.update("UPDATE energy_eerp_task SET status=?,revision=revision+1,failure_code=?,lease_token=NULL,lease_until=NULL WHERE task_id=? AND lease_token=? AND status='RUNNING'",status,failure,id,token);
    }
    public int reviewTask(String id,long rev,String before,String after,long user,String reason) {
        String actor = "PENDING_SEAL".equals(after) ? "submitted_by" : "approved_by";
        return jdbc.update("UPDATE energy_eerp_task SET status=?,revision=revision+1,"+actor+"=?,review_reason=? WHERE task_id=? AND revision=? AND status=?",after,user,reason,id,rev,before);
    }
    private static <T>T one(List<T> rows) { return rows.isEmpty() ? null : rows.getFirst(); }
}
