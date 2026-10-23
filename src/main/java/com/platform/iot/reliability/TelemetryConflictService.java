package com.platform.iot.reliability;

import com.platform.audit.*;
import com.platform.energy.aggregation.EnergyAggregationAuthorization;
import com.platform.energy.aggregation.EnergyAggregationGovernanceService;
import com.platform.energy.aggregation.api.EnergyAggregationContracts.CreateCorrectionVersionRequest;
import com.platform.framework.exception.BusinessException;
import com.platform.framework.web.PageResponse;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import com.platform.system.service.BuildingScopeService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** 日常冲突由现有建筑负责人处理；能源事实只提交既有更正审核，不能旁路生效。 */
@Service
public class TelemetryConflictService {
    private final JdbcTemplate jdbc;
    private final RawTelemetryLedger ledger;
    private final RawTelemetryCodec codec;
    private final EnergyAggregationAuthorization authorization;
    private final EnergyAggregationGovernanceService energy;
    private final BuildingScopeService scope;
    private final AuditEvidenceWriter audit;
    private final AuditGovernanceProperties auditProperties;
    @org.springframework.beans.factory.annotation.Value("${telemetry-conflicts.max-pending-derived-tasks:50000}")
    private int maxDerived=50000;

    public TelemetryConflictService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
            RawTelemetryLedger ledger, RawTelemetryCodec codec, EnergyAggregationAuthorization authorization,
            EnergyAggregationGovernanceService energy, BuildingScopeService scope,
            AuditEvidenceWriter audit, AuditGovernanceProperties auditProperties) {
        this.jdbc=jdbc; this.ledger=ledger; this.codec=codec; this.authorization=authorization;
        this.energy=energy; this.scope=scope; this.audit=audit; this.auditProperties=auditProperties;
    }

    public record ResolveRequest(String evidenceId, int expectedRevision, String reason, String evidenceReference) { }
    public record OwnerRequest(String buildingId, long userId) { }
    public record OwnerView(String buildingId, Long userId, List<String> allowedActions) { }
    public record RecoveryView(String kind,String id,String pointId,Long eventTime,int retryCount,String errorCode) { }
    public record RetryRequest(String kind,String id) { }
    public record Variant(String evidenceId, RawTelemetryEvent event, long firstSeenAt, long lastSeenAt, long attempts) { }
    public record ConflictView(String pointId, String buildingId, long eventTime, String state, int revision,
            RawTelemetryEvent original, List<Variant> variants, double effectiveValue, int selectionVersion,
            String mode, Long ownerId, List<String> allowedActions, String correctionVersionId) { }

    public PageResponse<ConflictView> list(long user, Collection<String> roles, String building,
            String state, int page, int size) {
        read(user, roles, building);
        if (page<1 || page>10000 || size<1 || size>100) bad("分页参数无效");
        if (state!=null && !Set.of("OPEN","EXPIRED","RESOLVED","PENDING_REVIEW").contains(state)) bad("冲突状态无效");
        List<Object> args=new ArrayList<>(); args.add(building);
        String where=" WHERE building_id=? AND conflict_state<>'NONE'";
        if (state!=null) {where+=" AND conflict_state=?"; args.add(state);}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_raw_intent"+where, Long.class,args.toArray());
        args.add(size); args.add((page-1)*size);
        List<RawTelemetryLedger.Fact> facts=jdbc.query("SELECT * FROM biz_telemetry_raw_intent"+where
                +" ORDER BY first_conflict_ms,point_key,event_ms LIMIT ? OFFSET ?",RawTelemetryLedger::mapFact,args.toArray());
        return new PageResponse<>(page,size,total,facts.stream().map(f->view(user,roles,f)).toList());
    }

    public ConflictView detail(long user, Collection<String> roles, String point, long time) {
        var fact=require(point,time); read(user,roles,fact.buildingId());
        return view(user,roles,fact);
    }

    public OwnerView owner(long user, Collection<String> roles, String building) {
        read(user,roles,building);
        return new OwnerView(building,ownerId(building),roles.contains("PLATFORM_ADMIN")?List.of("ASSIGN_OWNER"):List.of());
    }

    public List<RecoveryView> failed(long user,Collection<String> roles,String building) {
        read(user,roles,building);
        List<RecoveryView> result=new ArrayList<>();
        result.addAll(jdbc.query("SELECT point_id,event_ms,retry_count,last_error FROM biz_telemetry_raw_intent WHERE building_id=? AND storage_state='FAILED' ORDER BY point_id,event_ms LIMIT 100",
                (rs,n)->new RecoveryView("RAW",RawTelemetryLedger.identity(rs.getString(1),rs.getLong(2)),rs.getString(1),rs.getLong(2),rs.getInt(3),rs.getString(4)),building));
        result.addAll(jdbc.query("SELECT canonical_message_id,retry_count,last_error FROM biz_telemetry_message_intent WHERE building_id=? AND state='FAILED' ORDER BY created_ms LIMIT 100",
                (rs,n)->new RecoveryView("MESSAGE",rs.getString(1),null,null,rs.getInt(2),rs.getString(3)),building));
        result.addAll(jdbc.query("SELECT task_id,point_id,event_ms,retry_count,last_error FROM biz_telemetry_change_task WHERE building_id=? AND state='FAILED' ORDER BY event_ms LIMIT 100",
                (rs,n)->new RecoveryView("DERIVED",rs.getString(1),rs.getString(2),rs.getLong(3),rs.getInt(4),rs.getString(5)),building));
        result.addAll(jdbc.query("SELECT c.canonical_message_id,MAX(c.retry_count),MAX(c.last_error) FROM biz_telemetry_message_conflict c JOIN biz_telemetry_message_intent i ON i.canonical_message_id=c.canonical_message_id WHERE i.building_id=? AND c.materialized=-1 GROUP BY c.canonical_message_id ORDER BY c.canonical_message_id LIMIT 100",
                (rs,n)->new RecoveryView("MESSAGE_CONFLICT",rs.getString(1),null,null,rs.getInt(2),rs.getString(3)),building));
        return List.copyOf(result);
    }

    @Transactional(rollbackFor=Exception.class)
    public void retry(long user,Collection<String> roles,RetryRequest request) {
        if("MESSAGE_CONFLICT".equals(request.kind())) {
            String id=text(request.id(),64);
            var buildings=jdbc.queryForList("SELECT i.building_id FROM biz_telemetry_message_intent i WHERE i.canonical_message_id=? AND EXISTS(SELECT 1 FROM biz_telemetry_message_conflict c WHERE c.canonical_message_id=i.canonical_message_id AND c.materialized=-1) FOR UPDATE",String.class,id);
            if(buildings.isEmpty())throw new BusinessException(409,"失败任务已变化，请刷新");
            String building=buildings.getFirst();read(user,roles,building);
            if(!roles.contains("PLATFORM_ADMIN")&&!Objects.equals(ownerId(building),user))forbidden();
            jdbc.update("UPDATE biz_telemetry_message_conflict SET materialized=0,retry_count=0,next_retry_ms=0,last_error=NULL WHERE canonical_message_id=? AND materialized=-1",id);
            audit(user,building,"RETRY_TELEMETRY_RECOVERY",id,null,"kind=MESSAGE_CONFLICT");
            return;
        }
        String table,key,state;List<Object> args=new ArrayList<>();
        switch(text(request.kind(),20)) {
            case "RAW" -> {
                table="biz_telemetry_raw_intent";state="storage_state";key="point_id=? AND event_ms=?";
                String id=text(request.id(),64);int split=id.lastIndexOf('@');
                if(split<1)bad("恢复身份无效");
                args.add(id.substring(0,split));
                try{args.add(Long.parseLong(id.substring(split+1)));}catch(NumberFormatException invalid){bad("恢复时间无效");}
            }
            case "MESSAGE" -> {table="biz_telemetry_message_intent";state="state";key="canonical_message_id=?";args.add(text(request.id(),64));}
            case "DERIVED" -> {table="biz_telemetry_change_task";state="state";key="task_id=?";args.add(text(request.id(),64));}
            default -> throw new BusinessException(400,"恢复类型无效");
        }
        var buildings=jdbc.queryForList("SELECT building_id FROM "+table+" WHERE "+key+" AND "+state+"='FAILED' FOR UPDATE",String.class,args.toArray());
        if(buildings.isEmpty())throw new BusinessException(409,"失败任务已变化，请刷新");
        String building=buildings.getFirst();read(user,roles,building);
        if(!roles.contains("PLATFORM_ADMIN")&&!Objects.equals(ownerId(building),user))forbidden();
        String revision="DERIVED".equals(request.kind())?",request_revision=request_revision+1":"";
        jdbc.update("UPDATE "+table+" SET "+state+"='PENDING',retry_count=0,next_retry_ms=0,last_error=NULL"+revision+" WHERE "+key,args.toArray());
        audit(user,building,"RETRY_TELEMETRY_RECOVERY",request.id(),null,"kind="+request.kind());
    }

    public List<Map<String,Object>> messageEvidence(long user,Collection<String> roles,String id) {
        if(!roles.contains("PLATFORM_ADMIN"))forbidden();
        var buildings=jdbc.queryForList("SELECT building_id FROM biz_telemetry_message_intent WHERE canonical_message_id=?",String.class,id);
        if(buildings.isEmpty())throw new BusinessException(404,"消息证据不存在");
        read(user,roles,buildings.getFirst());
        return jdbc.queryForList("SELECT payload_hash,snapshot_json,first_seen_ms,last_seen_ms,attempt_count,materialized,last_error FROM biz_telemetry_message_conflict WHERE canonical_message_id=? ORDER BY first_seen_ms LIMIT 16",id);
    }

    @Transactional(rollbackFor=Exception.class)
    public OwnerView assign(long user, Collection<String> roles, OwnerRequest request) {
        read(user,roles,request.buildingId());
        if (!roles.contains("PLATFORM_ADMIN")) forbidden();
        List<String> targetRoles=jdbc.queryForList("""
                SELECT r.role_key FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id
                JOIN sys_role r ON r.id=ur.role_id
                WHERE u.id=? AND u.status=1 AND u.del_flag=0 AND r.status=1
                """,String.class,request.userId());
        authorization.requireReader(targetRoles);
        scope.checkAccess(request.userId(),targetRoles,request.buildingId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM building WHERE building_id=? AND del_flag=0",Integer.class,request.buildingId())!=1)
            throw new BusinessException(404,"建筑不存在");
        jdbc.update("INSERT INTO biz_telemetry_conflict_owner VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id),assigned_by=VALUES(assigned_by),assigned_at_ms=VALUES(assigned_at_ms)",request.buildingId(),request.userId(),user,System.currentTimeMillis());
        audit(user,request.buildingId(),"ASSIGN_TELEMETRY_CONFLICT_OWNER",request.buildingId(),null,
                "owner="+request.userId());
        return owner(user,roles,request.buildingId());
    }

    @Transactional(rollbackFor=Exception.class)
    public ConflictView resolve(long user, Collection<String> roles, String point, long time, ResolveRequest request) {
        var initial=require(point,time); read(user,roles,initial.buildingId());
        if (!Objects.equals(ownerId(initial.buildingId()),user)) forbidden();
        long floor=jdbc.queryForObject("SELECT before_ms FROM biz_telemetry_retention_boundary WHERE point_key=? FOR UPDATE",Long.class,codec.key(point));
        if(time<floor)throw new BusinessException(409,"原始数据已退役，请通过既有历史导入及核算审核处理，不能伪造更正生效");
        var fact=ledger.locked(codec.key(point),time);
        if (fact.conflictRevision()!=request.expectedRevision() || !Set.of("OPEN","EXPIRED","RESOLVED").contains(fact.conflictState())) {
            throw new BusinessException(409,"TELEMETRY_CONFLICT_REVISION","冲突已变化，请刷新后核实");
        }
        String reason=text(request.reason(),500), reference=text(request.evidenceReference(),500);
        RawTelemetryEvent original=codec.decode(fact.snapshotJson()), candidate=original;
        if (request.evidenceId()!=null) {
            var evidence=ledger.evidence(fact).stream().filter(e->e.evidenceId().equals(request.evidenceId())).findFirst()
                    .orElseThrow(()->new BusinessException(400,"候选证据不属于该原始事实"));
            candidate=codec.decode(evidence.snapshotJson());
        }
        // 只允许选择数值。来源、建筑、测点、质量或事件身份不同必须回到设备/配置治理。
        if (!codec.hash(codec.withValue(candidate,original.value())).equals(codec.hash(original))) {
            bad("候选包含身份或质量差异，不能按数值更正采用");
        }
        int version=fact.selectionVersion()+1;
        String correction=null, state="RESOLVED";
        boolean energyPoint=isEnergy(point);
        boolean changesEnergy=energyPoint && (Double.compare(candidate.value(),original.value())!=0
                || !ledger.selections(List.of(point),time,time+1).isEmpty());
        if (changesEnergy) {
            BigDecimal base=exactDecimal(original.value()), value=exactDecimal(candidate.value());
            var ids=jdbc.queryForList("SELECT correction_id FROM biz_energy_activity_correction WHERE building_id=? AND meter_point_id=? AND original_fact_identity=?",
                    String.class,fact.buildingId(),point,RawTelemetryLedger.identity(point,time));
            var created=energy.createCorrection(user,roles,new CreateCorrectionVersionRequest(ids.isEmpty()?null:ids.getFirst(),
                    fact.buildingId(),point,RawTelemetryLedger.identity(point,time),base,value,reason,"DEVICE",reference));
            correction=created.correctionVersionId(); state="PENDING_REVIEW";
        }
        jdbc.update("""
                INSERT INTO biz_telemetry_resolution
                (resolution_id,point_key,event_ms,version_no,conflict_revision,evidence_id,selected_json,reason,operator_id,resolved_at_ms,energy_correction_version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,RawTelemetryLedger.id(),fact.pointKey(),time,version,fact.conflictRevision()+1,request.evidenceId(),codec.encode(candidate),
                reason,user,System.currentTimeMillis(),correction);
        // 转为能源治理后，待审版本不能提前撤销原有普通选择；批准事务再原子移交有效值来源。
        jdbc.update("UPDATE biz_telemetry_raw_intent SET conflict_state=?,selection_version=?,selected_json=CASE WHEN ? THEN selected_json ELSE ? END,conflict_revision=conflict_revision+1 WHERE point_key=? AND event_ms=?",
                state,version,energyPoint,codec.encode(candidate),fact.pointKey(),time);
        if (!energyPoint) enqueue(jdbc,point,fact.buildingId(),time,"telemetry:"+version,maxDerived);
        audit(user,fact.buildingId(),"RESOLVE_TELEMETRY_CONFLICT",RawTelemetryLedger.identity(point,time),correction,
                "state="+state+";selection="+version+";evidence="+request.evidenceId()+";reference="+reference+";reason="+reason);
        return view(user,roles,require(point,time));
    }

    private ConflictView view(long user, Collection<String> roles, RawTelemetryLedger.Fact f) {
        var original=codec.decode(f.snapshotJson());
        var selection=ledger.selections(List.of(f.pointId()),f.eventTime(),f.eventTime()+1)
                .get(RawTelemetryLedger.identity(f.pointId(),f.eventTime()));
        Long owner=ownerId(f.buildingId());
        var versions=jdbc.queryForList("SELECT energy_correction_version FROM biz_telemetry_resolution WHERE point_key=? AND event_ms=? AND version_no=?",
                String.class,f.pointKey(),f.eventTime(),f.selectionVersion());
        String correction=versions.isEmpty()?null:versions.getFirst();
        String state=f.conflictState();
        return new ConflictView(f.pointId(),f.buildingId(),f.eventTime(),state,f.conflictRevision(),original,
                ledger.evidence(f).stream().map(e->new Variant(e.evidenceId(),codec.decode(e.snapshotJson()),e.firstSeenAt(),e.lastSeenAt(),e.attempts())).toList(),
                selection==null?original.value():selection.selectedValue(),f.selectionVersion(),isEnergy(f.pointId())?"ENERGY_REVIEW":"DIRECT",owner,
                Objects.equals(owner,user) && !"PENDING_REVIEW".equals(state)?List.of("RESOLVE"):List.of(),correction);
    }

    boolean isEnergy(String point) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM biz_energy_point_profile WHERE point_id=?",Integer.class,point)>0
                || jdbc.queryForObject("SELECT COUNT(*) FROM biz_energy_activity_correction WHERE meter_point_id=?",Integer.class,point)>0;
    }
    private Long ownerId(String building) {
        var ids=jdbc.queryForList("SELECT user_id FROM biz_telemetry_conflict_owner WHERE building_id=?",Long.class,building);
        return ids.isEmpty()?null:ids.getFirst();
    }
    private RawTelemetryLedger.Fact require(String point,long time) {
        var f=ledger.find(point,time); if (f==null) throw new BusinessException(404,"冲突事实不存在"); return f;
    }
    private void read(long user,Collection<String> roles,String building) {
        authorization.requireReader(roles); text(building,32); scope.checkAccess(user,roles,building);
    }
    private static BigDecimal exactDecimal(double value) {
        BigDecimal decimal=BigDecimal.valueOf(value);
        if (decimal.stripTrailingZeros().scale()>12 || decimal.precision()-decimal.scale()>18) bad("该值超出既有能源更正精度，不能静默舍入");
        return decimal;
    }
    private static String text(String value,int max) { if(value==null||value.isBlank()||value.length()>max) bad("必填文本无效"); return value.trim(); }
    private static void bad(String message) { throw new BusinessException(400,message); }
    private static void forbidden() { throw new BusinessException(403,"无权处置该建筑冲突"); }
    private void audit(long user,String building,String action,String object,String version,String summary) {
        audit.append(new AuditEvidence("TELEMETRY_CONFLICT",building,"USER",user,action,"TELEMETRY_FACT",object,version,
                null,null,summary,"SUCCESS",null,TraceContext.current(),LocalDateTime.now(),auditProperties.getEnvironmentMode(),false));
    }
    public static void enqueue(JdbcTemplate jdbc,String point,String building,long time,String version) {
        enqueue(jdbc,point,building,time,version,50000);
    }

    /** 同一事实只保留一个派生待办；失败仍占额度，满额时回滚选择/审批而不丢失补偿。 */
    public static void enqueue(JdbcTemplate jdbc,String point,String building,long time,String version,int capacity) {
        if(capacity<1)throw new IllegalArgumentException("Invalid derived capacity");
        var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        tx.executeWithoutResult(status->{
            String key=point.toLowerCase(Locale.ROOT);
            jdbc.update("INSERT INTO biz_telemetry_retention_boundary(point_key,before_ms) VALUES (?,0) ON DUPLICATE KEY UPDATE point_key=VALUES(point_key)",key);
            var rows=jdbc.queryForList("SELECT task_id,building_id,state FROM biz_telemetry_change_task WHERE point_id=? AND event_ms=? FOR UPDATE",point,time);
            if(!rows.isEmpty()&&!building.equals(rows.getFirst().get("building_id")))throw new IllegalStateException("Derived building changed");
            if(rows.isEmpty()||"COMPLETE".equals(rows.getFirst().get("state"))) {
                if(jdbc.update("UPDATE biz_telemetry_conflict_budget SET used_rows=used_rows+1 WHERE budget_key='PENDING_DERIVED' AND used_rows<?",capacity)!=1)
                    throw new org.springframework.dao.DataAccessResourceFailureException("Pending derived capacity reached");
            }
            if(rows.isEmpty())jdbc.update("INSERT INTO biz_telemetry_change_task (task_id,point_id,building_id,event_ms,state) VALUES (?,?,?,?,'PENDING')",RawTelemetryLedger.id(),point,building,time);
            else jdbc.update("UPDATE biz_telemetry_change_task SET request_revision=request_revision+1,state=CASE WHEN state='FAILED' THEN 'FAILED' ELSE 'PENDING' END,next_retry_ms=0 WHERE task_id=?",rows.getFirst().get("task_id"));
        });
    }
}
