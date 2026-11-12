package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static com.platform.carbon.HourlyMonitoringCalculator.ZONE;

/** 逐小时版本守卫、持久租约和不可变证据；在线读取只检查摘要及当前来源水位。 */
@Repository
class HourlyMonitoringRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate reads,writes;
    private static final List<String> DOMAINS=List.of("DAILY_INPUT","DYNAMIC_INPUT","DYNAMIC_RAW","HOURLY_RULE","CATALOG");
    record Target(String building,LocalDate date,long actor) { }
    record Stored(long hour,String version,String summary,String hash,String evidence,long updatedAt) { }
    record Snapshot(Map<Long,String> versions,Map<Long,Stored> results) { }

    HourlyMonitoringRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,ObjectMapper json) {
        this.jdbc=jdbc;this.json=json;
        var tx=new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));
        reads=new TransactionTemplate(tx);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);reads.setTimeout(3);
        writes=new TransactionTemplate(tx);writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);writes.setTimeout(3);
    }
    void register(String building,LocalDate date,long actor) {
        jdbc.update("INSERT INTO biz_hourly_monitor_target(building_id,day_date,actor_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE building_id=building_id",building,java.sql.Date.valueOf(date),actor);
    }
    void registerConfigured(String building,LocalDate date,long actor) {
        jdbc.update("INSERT INTO biz_hourly_monitor_target(building_id,day_date,actor_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE actor_id=VALUES(actor_id)",building,java.sql.Date.valueOf(date),actor);
    }
    List<Target> due(long now,boolean currentDay) {
        return due(now,currentDay,null);
    }
    List<Target> due(long now,boolean currentDay,Target after) {
        var today=java.sql.Date.valueOf(Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate());
        var args=new ArrayList<Object>(List.of(now,now,today));
        String seek="";
        if(after!=null) {
            seek=" AND (building_id>? OR (building_id=? AND day_date>?))";
            args.add(after.building());args.add(after.building());args.add(java.sql.Date.valueOf(after.date()));
        }
        // 稳定主键游标让空闲目标也能向后扫描，不靠反复写next_check维持公平性。
        return jdbc.query("SELECT building_id,day_date,actor_id FROM biz_hourly_monitor_target WHERE next_check<=? AND lease_until<? AND day_date"+(currentDay?"=":"<")+"?"+seek+" ORDER BY building_id,day_date LIMIT 8",
                (r,n)->new Target(r.getString(1),r.getDate(2).toLocalDate(),r.getLong(3)),args.toArray());
    }
    boolean claim(Target target,String token,long now) {
        return jdbc.update("UPDATE biz_hourly_monitor_target SET lease_token=?,lease_until=? WHERE building_id=? AND day_date=? AND lease_until<?",
                token,now+120_000,target.building(),java.sql.Date.valueOf(target.date()),now)==1;
    }
    void release(Target target,String token,long delay) {
        jdbc.update("UPDATE biz_hourly_monitor_target SET lease_token=NULL,lease_until=0,next_check=? WHERE building_id=? AND day_date=? AND lease_token=?",
                System.currentTimeMillis()+delay,target.building(),java.sql.Date.valueOf(target.date()),token);
    }
    long configuredActor(String building,CarbonDisplayQueryProperties properties) {
        for(var target:properties.getPrewarmTargets())if(target.actorId()>0&&jdbc.queryForObject(
                "SELECT COUNT(*) FROM building WHERE building_id=? AND building_code=? AND del_flag=0",Integer.class,building,target.buildingCode())==1)return target.actorId();
        return 0;
    }
    List<String> configuredBuildings(String code) {
        return jdbc.queryForList("SELECT building_id FROM building WHERE building_code=? AND del_flag=0 ORDER BY building_id LIMIT 2",String.class,code);
    }
    String sourceKind(String building,Instant from,Instant to) {
        // 已接入的原始来源优先；即使原始事实冲突/缺失也不改用人工数掩盖。
        // 尚未接入原始电表的历史小时仍可使用既有已审核精确区间。物理键规则与RawTelemetryCodec一致。
        var raw=jdbc.queryForList("""
                SELECT p.point_id FROM biz_dynamic_raw_electricity_point p WHERE p.building_id=?
                  AND EXISTS (SELECT 1 FROM biz_telemetry_raw_intent r
                    WHERE r.point_key=CONVERT(LOWER(p.point_id) USING ascii) COLLATE ascii_bin
                      AND r.event_ms>=? AND r.event_ms<=?) LIMIT 1
                """,String.class,building,from.minusSeconds(360).toEpochMilli(),to.toEpochMilli());
        return raw.isEmpty()?"REVIEWED_INTERVAL":"RAW_CUMULATIVE";
    }
    Snapshot snapshot(String building,LocalDate date,Instant now,String policy) {
        return reads.execute(status->{
            long start=date.atStartOfDay(ZONE).toInstant().toEpochMilli();
            String global=global(building,date,false);
            Map<Long,Long> revisions=new HashMap<>();
            jdbc.query("SELECT hour_ms,revision FROM biz_hourly_monitor_revision WHERE building_id=? AND hour_ms>=? AND hour_ms<?",r->{revisions.put(r.getLong(1),r.getLong(2));},building,start,start+86_400_000);
            Map<Long,String> versions=new HashMap<>();
            for(int hour=0;hour<24;hour++) {long at=start+hour*3_600_000L;versions.put(at,version(global,revisions.getOrDefault(at,0L),at,now,policy));}
            Map<Long,Stored> values=new HashMap<>();
            jdbc.query("SELECT hour_ms,source_version,summary_json,summary_hash,evidence_id,updated_at FROM biz_hourly_monitor_result WHERE building_id=? AND hour_ms>=? AND hour_ms<?",r->{
                var row=new Stored(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getLong(6));values.put(row.hour(),row);
            },building,start,start+86_400_000);
            return new Snapshot(Map.copyOf(versions),Map.copyOf(values));
        });
    }
    boolean publish(Target target,String token,long hour,String expected,String policy,HourlyMonitoringCalculator.Calculated result) {
        String evidenceText=encode(result.evidence());
        if(evidenceText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16_000_000)
            throw new BusinessException(409,"HOURLY_MONITOR_CAPACITY","小时证据超出容量，不截断保存");
        String evidenceId=hash(target.building()+":"+hour+":"+expected+":"+evidenceText);
        var p=result.point();
        var point=new HourlyMonitoringContracts.Point(p.hour(),p.startInclusive(),p.endExclusive(),p.actualStart(),p.actualEnd(),p.electricityKwh(),
                p.staticEmissionTco2e(),p.staticFactorKgCo2ePerKwh(),p.dynamicEmissionTco2(),p.dynamicFactorKgCo2PerKwh(),p.electricityStatus(),p.staticStatus(),p.dynamicStatus(),
                p.approximate(),p.staticGasCoverage(),p.sourceNatures(),evidenceId,p.reasonCode());
        String summary=encode(point),summaryHash=hash(evidenceId+":"+summary);
        return Boolean.TRUE.equals(writes.execute(status->{
            String global=global(target.building(),target.date(),true);
            jdbc.update("INSERT INTO biz_hourly_monitor_revision(building_id,hour_ms,revision) VALUES(?,?,0) ON DUPLICATE KEY UPDATE revision=revision",target.building(),hour);
            Long revision=jdbc.queryForObject("SELECT revision FROM biz_hourly_monitor_revision WHERE building_id=? AND hour_ms=? FOR UPDATE",Long.class,target.building(),hour);
            if(!expected.equals(version(global,Objects.requireNonNull(revision),hour,Instant.now(),policy)))return false;
            var owners=jdbc.queryForList("SELECT actor_id FROM biz_hourly_monitor_target WHERE building_id=? AND day_date=? AND lease_token=? AND lease_until>? FOR UPDATE",Long.class,
                    target.building(),java.sql.Date.valueOf(target.date()),token,System.currentTimeMillis());
            if(owners.size()!=1||owners.getFirst()!=target.actor())return false;
            long now=System.currentTimeMillis();
            jdbc.update("INSERT INTO biz_hourly_monitor_evidence(evidence_id,building_id,hour_ms,source_version,evidence_json,created_at) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE evidence_id=evidence_id",evidenceId,target.building(),hour,expected,evidenceText,now);
            jdbc.update("INSERT INTO biz_hourly_monitor_result(building_id,hour_ms,source_version,evidence_id,summary_json,summary_hash,updated_at) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE source_version=VALUES(source_version),evidence_id=VALUES(evidence_id),summary_json=VALUES(summary_json),summary_hash=VALUES(summary_hash),updated_at=VALUES(updated_at)",
                    target.building(),hour,expected,evidenceId,summary,summaryHash,now);
            return true;
        }));
    }
    HourlyMonitoringContracts.Point decode(Stored row) {
        if(!row.hash().equals(hash(row.evidence()+":"+row.summary())))throw integrity();
        try {
            var point=json.readValue(row.summary(),HourlyMonitoringContracts.Point.class);
            if(point.startInclusive().toEpochMilli()!=row.hour()||!point.endExclusive().equals(point.startInclusive().plusSeconds(3600))
                    ||point.hour()!=point.startInclusive().atZone(ZONE).getHour()||!row.evidence().equals(point.evidenceId()))throw integrity();
            return point;
        } catch(com.fasterxml.jackson.core.JsonProcessingException e){throw integrity();}
    }
    JsonNode evidence(String building,String id) {
        var values=jdbc.queryForList("SELECT hour_ms,source_version,evidence_json FROM biz_hourly_monitor_evidence WHERE evidence_id=? AND building_id=?",id,building);
        if(values.isEmpty())throw new BusinessException(404,"HOURLY_MONITOR_EVIDENCE_NOT_FOUND","小时监测证据不存在");
        var row=values.getFirst();String text=(String)row.get("evidence_json");
        if(!id.equals(hash(building+":"+row.get("hour_ms")+":"+row.get("source_version")+":"+text)))throw integrity();
        return CarbonEvidence.parse(text);
    }
    private String global(String building,LocalDate date,boolean lock) {
        if(lock)for(String owner:List.of("*",building))for(String domain:DOMAINS)
            jdbc.update("INSERT INTO biz_carbon_display_source_revision(building_id,domain_key,scope_key,revision) VALUES(?,?,'*',0) ON DUPLICATE KEY UPDATE revision=revision",owner,domain);
        var rows=jdbc.query("SELECT building_id,domain_key,revision FROM biz_carbon_display_source_revision WHERE building_id IN ('*',?) AND domain_key IN ('DAILY_INPUT','DYNAMIC_INPUT','DYNAMIC_RAW','HOURLY_RULE','CATALOG') AND scope_key='*' ORDER BY building_id,domain_key"+(lock?" FOR UPDATE":""),
                (r,n)->r.getString(1)+":"+r.getString(2)+":"+r.getLong(3),building);
        // 缺失零版本行与发布时补建的零版本行必须等价。
        Map<String,String> values=new TreeMap<>();
        for(String owner:List.of("*",building))for(String domain:DOMAINS)values.put(owner+":"+domain,"0");
        for(String row:rows){int index=row.lastIndexOf(':');values.put(row.substring(0,index),row.substring(index+1));}
        if(!"0".equals(values.get("*:CATALOG")))values.put("*:CATALOG",MetadataSnapshot.catalog(jdbc).version(date.atStartOfDay(ZONE).toInstant(),date.plusDays(1).atStartOfDay(ZONE).toInstant()));
        return hash(encode(values));
    }
    private static String version(String global,long revision,long hour,Instant now,String policy) {
        String clock=hour>now.toEpochMilli()?"FUTURE":hour+3_600_000<=now.toEpochMilli()?"CLOSED":"OPEN";
        return hash(policy+":"+global+":"+hour+":"+revision+":"+clock);
    }
    String encode(Object value){try{return json.writeValueAsString(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("小时监测序列化失败",e);}}
    private static String hash(String value){return CarbonCalculationCore.sha256(value);}
    private static BusinessException integrity(){return new BusinessException(409,"HOURLY_MONITOR_INTEGRITY","小时监测摘要或证据校验失败");}
}
