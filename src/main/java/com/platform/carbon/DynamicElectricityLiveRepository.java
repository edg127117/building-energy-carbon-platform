package com.platform.carbon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.platform.carbon.DynamicElectricityRepository.*;

/** MySQL 内设备周期量与待算任务共用时段行。修订与租约条件发布保证重投、并发和重启安全。 */
@Repository
public class DynamicElectricityLiveRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DynamicElectricityIntervalEvidence evidence;
    public DynamicElectricityLiveRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,ObjectMapper json) {
        this.jdbc=jdbc; this.json=json;this.evidence=new DynamicElectricityIntervalEvidence(jdbc,json);
    }
    DynamicElectricityIntervalEvidence evidence(){return evidence;}
    public record Reading(BigDecimal quantity,long quantityRevision,BigDecimal factor,long factorRevision,
                          String nature,String reference,String identityId,String transport,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) DynamicElectricityContracts.SourceOrder sourceOrder,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String evidenceId) {
        public Reading(BigDecimal quantity,long quantityRevision,BigDecimal factor,long factorRevision,String nature,String reference,String identityId,String transport,DynamicElectricityContracts.SourceOrder sourceOrder) {
            this(quantity,quantityRevision,factor,factorRevision,nature,reference,identityId,transport,sourceOrder,null);
        }
        Reading withEvidence(String id){return new Reading(quantity,quantityRevision,factor,factorRevision,nature,reference,identityId,transport,sourceOrder,id);}
        public Reading(BigDecimal quantity,long quantityRevision,BigDecimal factor,long factorRevision,String nature,String reference,String identityId,String transport) {
            this(quantity,quantityRevision,factor,factorRevision,nature,reference,identityId,transport,null);
        }
        public Reading(BigDecimal quantity,long quantityRevision,BigDecimal factor,long factorRevision,String nature,String reference) {
            this(quantity,quantityRevision,factor,factorRevision,nature,reference,null,"HTTP");
        }
    }
    public record Task(String building,long from,long to,long user,String inputHash,long revision,String state,
                       String token,long leaseUntil,String metadata,String resultId,String sourceKind,String grain,Map<String,Reading> readings) { }
    private Task row(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        String input=r.getString("input_json");
        if(!hash(input).equals(r.getString("input_hash")))throw new IllegalStateException("设备周期输入摘要不一致");
        try {
            return new Task(r.getString("building_id"),r.getLong("start_ms"),r.getLong("end_ms"),r.getLong("requested_by"),
                    r.getString("input_hash"),r.getLong("revision"),r.getString("state"),r.getString("lease_token"),
                    r.getLong("lease_until"),r.getString("metadata_version"),r.getString("result_id"),r.getString("source_kind"),r.getString("grain"),
                    json.readValue(input,new TypeReference<TreeMap<String,Reading>>(){}));
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException("设备周期输入无法读取",e);}
    }
    Task locked(String building,Instant from,Instant to,long user) {
        jdbc.update("""
                INSERT INTO biz_dynamic_electricity_task(building_id,start_ms,end_ms,requested_by,input_json,input_hash,updated_at)
                VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE building_id=building_id
                """,building,from.toEpochMilli(),to.toEpochMilli(),user,"{}",hash("{}"),System.currentTimeMillis());
        return jdbc.query("SELECT * FROM biz_dynamic_electricity_task WHERE building_id=? AND start_ms=? AND end_ms=? FOR UPDATE",
                this::row,building,from.toEpochMilli(),to.toEpochMilli()).getFirst();
    }
    Task lockForEvidenceRetention(String building,long from,long to) {
        return jdbc.query("""
                SELECT * FROM biz_dynamic_electricity_task WHERE building_id=? AND start_ms=? AND end_ms=?
                FOR UPDATE SKIP LOCKED
                """,this::row,building,from,to).stream().findFirst().orElse(null);
    }
    void update(Task task,Map<String,Reading> inputs,long user) {
        String payload=encode(inputs);
        jdbc.update("""
                UPDATE biz_dynamic_electricity_task SET input_json=?,input_hash=?,requested_by=?,revision=revision+1,
                state='PENDING',lease_token=NULL,lease_until=0,next_check=?,last_error=NULL,updated_at=?
                WHERE building_id=? AND start_ms=? AND end_ms=?
                """,payload,hash(payload),user,System.currentTimeMillis()+1000,System.currentTimeMillis(),task.building(),task.from(),task.to());
    }
    boolean pointOnDevice(String building,String point,String equipment) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM biz_data_point WHERE building_id=? AND point_id=? AND equip_id=? AND del_flag=0 AND UPPER(unit)='KWH' AND status='ONLINE'",
                Integer.class,building,point,equipment)==1;
    }
    List<Task> due(long now) {
        return jdbc.query("SELECT * FROM biz_dynamic_electricity_task WHERE (state<>'READY' OR source_kind<>'DEVICE_INTERVAL') AND next_check<=? AND (state<>'RUNNING' OR lease_until<?) ORDER BY CASE WHEN state='READY' THEN 1 ELSE 0 END,next_check,start_ms LIMIT 100",
                this::row,now,now);
    }
    String claim(Task t,long now) {
        String token=UUID.randomUUID().toString().replace("-","");
        int count=jdbc.update("""
                UPDATE biz_dynamic_electricity_task SET state='RUNNING',lease_token=?,lease_until=?
                WHERE building_id=? AND start_ms=? AND end_ms=? AND revision=? AND (state<>'RUNNING' OR lease_until<?)
                """,token,now+30000,t.building(),t.from(),t.to(),t.revision(),now);
        return count==1?token:null;
    }
    boolean publish(Task t,String token,String metadata,Result result) {
        // 发布与输入修订争用同一行；旧任务即使计算成功，也不能覆盖新修订的任务与结果指针。
        return jdbc.update("""
                UPDATE biz_dynamic_electricity_task SET state='READY',metadata_version=?,result_id=?,summary_json=?,
                lease_token=NULL,lease_until=0,last_error=NULL,updated_at=?
                WHERE building_id=? AND start_ms=? AND end_ms=? AND revision=? AND lease_token=? AND lease_until>=?
                """,metadata,result.id(),encode(result),System.currentTimeMillis(),t.building(),t.from(),t.to(),t.revision(),token,System.currentTimeMillis())==1;
    }
    void failed(Task t,String token,String code) {
        jdbc.update("""
                UPDATE biz_dynamic_electricity_task SET state='WAITING',lease_token=NULL,lease_until=0,last_error=?,next_check=?
                WHERE building_id=? AND start_ms=? AND end_ms=? AND revision=? AND lease_token=?
                """,code,System.currentTimeMillis()+5000,t.building(),t.from(),t.to(),t.revision(),token);
    }
    List<String> buildings() {return jdbc.queryForList("SELECT DISTINCT building_id FROM biz_dynamic_electricity_task",String.class);}
    void metadataChanged(String building,String metadata) {
        jdbc.update("UPDATE biz_dynamic_electricity_task SET state='PENDING',revision=revision+1,lease_token=NULL,lease_until=0,next_check=? WHERE building_id=? AND source_kind='DEVICE_INTERVAL' AND state='READY' AND metadata_version<>?",
                System.currentTimeMillis(),building,metadata);
    }
    public String metadataVersion(String building,DynamicElectricityRepository repository) {
        var values=new ArrayList<Object>();
        values.add(repository.inputMetadataVersion(building,"DEVICE_INTERVAL"));values.add(repository.projectVersion(building));
        values.add(jdbc.queryForList("SELECT version_id,revision,status,effective_from,effective_to FROM biz_energy_classification_version WHERE building_id=? AND status='APPROVED' ORDER BY version_id",building));
        values.add(jdbc.queryForList("SELECT version_id,config_revision,status,snapshot_sha256,effective_at,superseded_at FROM biz_relation_version WHERE building_id=? AND effective_at IS NOT NULL ORDER BY version_id",building));
        values.add(jdbc.queryForList("SELECT model_id,governance_mode,active_version_id FROM biz_relation_model WHERE building_id=? ORDER BY model_id",building));
        values.add(jdbc.queryForList("SELECT scope_id,relation_version_id,start_epoch_ms,end_epoch_ms,status FROM biz_historical_metering_scope WHERE building_id=? AND status='APPROVED' ORDER BY scope_id",building));
        return hash(encode(values));
    }
    void invalidateLegacy(String building,Instant from,Instant to) {
        jdbc.update("UPDATE biz_dynamic_electricity_task SET state='PENDING',revision=revision+1,next_check=0,lease_token=NULL,lease_until=0 WHERE building_id=? AND source_kind<>'DEVICE_INTERVAL' AND start_ms<? AND end_ms>?",building,to.toEpochMilli(),from.toEpochMilli());
    }
    void registerCalculation(Result result,long user) {
        jdbc.update("""
                INSERT INTO biz_dynamic_electricity_task(building_id,start_ms,end_ms,requested_by,source_kind,grain,input_json,input_hash,
                state,metadata_version,result_id,next_check,updated_at)
                VALUES (?,?,?,?,?,?,'{}',?,'READY',?,?,?,?)
                ON DUPLICATE KEY UPDATE source_kind=VALUES(source_kind),grain=VALUES(grain),requested_by=VALUES(requested_by),
                state='READY',metadata_version=VALUES(metadata_version),result_id=VALUES(result_id),lease_token=NULL,lease_until=0,next_check=VALUES(next_check)
                """,result.building(),result.start().toEpochMilli(),result.end().toEpochMilli(),user,result.sourceKind(),result.grain(),hash("{}"),
                result.sourceVersion(),result.id(),System.currentTimeMillis()+5000,System.currentTimeMillis());
    }
    void checked(Task task,String token) {
        jdbc.update("UPDATE biz_dynamic_electricity_task SET state='READY',lease_token=NULL,lease_until=0,next_check=? WHERE building_id=? AND start_ms=? AND end_ms=? AND lease_token=?",
                System.currentTimeMillis()+5000,task.building(),task.from(),task.to(),token);
    }
    /** 任一设备输入（含待配齐）声明该准确时段，重叠的人工粗周期结果不再作为设备缺口的兜底。 */
    public List<Instant[]> deviceCoverage(String building,Instant from,Instant to) {
        var windows=jdbc.query("SELECT start_ms,end_ms FROM biz_dynamic_electricity_task WHERE building_id=? AND source_kind='DEVICE_INTERVAL' AND start_ms<? AND end_ms>? ORDER BY start_ms",
                (r,n)->new Instant[]{Instant.ofEpochMilli(r.getLong(1)),Instant.ofEpochMilli(r.getLong(2))},building,to.toEpochMilli(),from.toEpochMilli());
        var merged=new ArrayList<Instant[]>();
        for(var window:windows) {
            if(!merged.isEmpty()&&!window[0].isAfter(merged.getLast()[1]))merged.getLast()[1]=window[1];
            else merged.add(window);
        }
        return merged;
    }
    public boolean hasData(String building,Instant from,Instant to) {
        return !jdbc.queryForList("SELECT start_ms FROM biz_dynamic_electricity_task WHERE building_id=? AND source_kind='DEVICE_INTERVAL' AND start_ms>=? AND end_ms<=? LIMIT 1",building,from.toEpochMilli(),to.toEpochMilli()).isEmpty();
    }
    /** 只载入轻量当前值；原始每表证据保存在不可变结果中，年查询不加载数百万条设备输入。 */
    public List<Result> current(String building,Instant from,Instant to,String metadata) {
        return jdbc.query("SELECT summary_json FROM biz_dynamic_electricity_task WHERE building_id=? AND start_ms>=? AND end_ms<=? AND source_kind='DEVICE_INTERVAL' AND state='READY' AND metadata_version=? ORDER BY start_ms",
                (r,n)->{try{return json.readValue(r.getString(1),Result.class);}catch(Exception e){throw new IllegalStateException(e);}},building,from.toEpochMilli(),to.toEpochMilli(),metadata);
    }
}
