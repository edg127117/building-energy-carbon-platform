package com.platform.iot.daikin.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.*;
import com.platform.framework.web.PageResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static com.platform.iot.daikin.control.DaikinBatchContracts.*;
import static com.platform.iot.daikin.control.DaikinControlContracts.*;
import static com.platform.iot.daikin.control.DaikinControlErrors.error;

/** 队列/租约/审计只写 MySQL；派发状态永不重新排队，恢复仅关联既有单台操作。 */
@Repository
public class DaikinBatchStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final DaikinControlStore controls;
    private final AuditEvidenceWriter audit;
    private final AuditGovernanceProperties auditProperties;

    public DaikinBatchStore(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json,
                            DaikinControlStore controls, AuditEvidenceWriter audit, AuditGovernanceProperties auditProperties) {
        this.jdbc = jdbc; this.tx = tx; this.json = json; this.controls = controls;
        this.audit = audit; this.auditProperties = auditProperties;
    }
    record Work(Item item, String batchId, long user, Action action, String value, String trace,
                DaikinControlAccess.Target target, String revision, String relationshipHash, List<DaikinControlAccess.Target> impactedTargets, String token, long leaseUntil) {
        String operationKey() { return "batch-" + item.itemId(); }
        String operationHash() { return DaikinControlAccess.hash(item.equipmentId()+"|"+action+"|"+value+"|"+revision); }
    }

    void savePreview(SavedPreview preview) {
        jdbc.update("INSERT INTO biz_daikin_control_preview(preview_id,operator_id,expires_at,payload_json) VALUES (?,?,?,?)",
                preview.previewId(), preview.userId(), preview.expiresAt(), encode(preview));
    }
    SavedPreview preview(long user, String id, long now) {
        var rows = jdbc.queryForList("SELECT payload_json FROM biz_daikin_control_preview WHERE preview_id=? AND operator_id=?",
                String.class, id, user);
        if (rows.size()!=1) throw error(404,"PREVIEW_NOT_FOUND");
        var result = decode(rows.getFirst(), SavedPreview.class);
        if (result.expiresAt()<=now) throw error(409,"PREVIEW_EXPIRED");
        return result;
    }
    String existing(long user, String key, String hash) {
        var rows=jdbc.queryForList("SELECT batch_id,request_hash FROM biz_daikin_control_batch WHERE operator_id=? AND idempotency_key=?",user,key);
        if(rows.isEmpty()) return null;
        if(!hash.equals(rows.getFirst().get("request_hash"))) throw error(409,"IDEMPOTENCY_CONFLICT");
        return (String)rows.getFirst().get("batch_id");
    }

    String create(long user, SubmitBatch request, String hash, SavedPreview preview, String trace, Clock clock,
                  DaikinControlAccess access, DaikinControlPolicy policy, boolean overlap) {
        return tx.execute(status -> {
            // 用户行串行化重复提交，来源锁与单台占用一致；整个批次和所有取消/替换审计一起回滚。
            jdbc.queryForList("SELECT id FROM sys_user WHERE id=? FOR UPDATE",user);
            String previous=existing(user,request.idempotencyKey(),hash);
            if(previous!=null) return previous;
            long now=clock.millis();
            if(preview.expiresAt()<=now) throw error(409,"PREVIEW_EXPIRED");
            preview.items().stream().filter(p->p.target()!=null).map(p->p.target().sourceId()).distinct().sorted()
                    .forEach(s->jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE",s));
            for(var p:preview.items()) {
                access.requireControl(user,p.view().equipmentId());
                if(p.target()!=null && !p.target().equals(access.target(p.view().equipmentId()))) throw error(409,"REVISION_CHANGED");
                if(Set.of(Action.MODE,Action.ON_OFF).contains(request.action())) for(var related:p.impactedTargets()) {
                    access.requireControl(user,related.equipmentId());
                    if(!related.equals(access.target(related.equipmentId()))) throw error(409,"LINKED_SCOPE_CHANGED");
                }
            }
            String batch=id();
            jdbc.update("INSERT INTO biz_daikin_control_batch(batch_id,operator_id,idempotency_key,request_hash,action,requested_value,created_at,trace_id,overlap_allowed) VALUES(?,?,?,?,?,?,?,?,?)",
                    batch,user,request.idempotencyKey(),hash,request.action().name(),request.value(),now,trace,overlap);
            for(var p:preview.items()) {
                ItemStatus next=ItemStatus.QUEUED; String reason=p.view().reasonCode();
                if(p.target()==null || reason!=null) next=ItemStatus.UNSUPPORTED;
                else {
                    try { policy.validate(request.action(),request.value(),p.view().actions()); }
                    catch(com.platform.framework.exception.BusinessException e) { next=ItemStatus.UNSUPPORTED; reason=reason(e); }
                }
                if(next==ItemStatus.QUEUED) {
                    // 只替换未领取的同设备同动作；不撤回已下发项，也不覆盖其他动作。
                    var old=jdbc.query("SELECT i.*,b.operator_id,b.action,b.requested_value,b.trace_id FROM biz_daikin_control_batch_item i JOIN biz_daikin_control_batch b ON b.batch_id=i.batch_id WHERE i.equipment_id=? AND b.action=? AND i.status='QUEUED' FOR UPDATE",
                            this::map,p.view().equipmentId(),request.action().name());
                    for(var row:old) {
                        if(jdbc.update("UPDATE biz_daikin_control_batch_item SET status='SUPERSEDED',reason_code='REPLACED_BY_NEW_BATCH',updated_at=? WHERE item_id=? AND status='QUEUED'",now,row.item().itemId())==1)
                            evidence(row, "SUPERSEDED", user, now);
                    }
                }
                String item=id();
                jdbc.update("""
                        INSERT INTO biz_daikin_control_batch_item(item_id,batch_id,equipment_id,controller_key,target_json,revision,
                         relationship_hash,impacted_targets_json,status,reason_code,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                        """,item,batch,p.view().equipmentId(),p.target()==null?null:p.target().controllerKey(),p.target()==null?null:encode(p.target()),
                        p.view().revision(),p.relationshipHash(),encode(p.impactedTargets()),next.name(),reason,now,now);
                evidence(work(item),next.name(),user,now);
            }
            return batch;
        });
    }

    Batch get(long user, String id, DaikinControlAccess access, long now) {
        var rows=jdbc.queryForList("SELECT * FROM biz_daikin_control_batch WHERE batch_id=? AND operator_id=?",id,user);
        if(rows.size()!=1) throw error(404,"BATCH_NOT_FOUND");
        var row=rows.getFirst(); var work=items(id);
        for(var w:work) {
            access.requireControl(user,w.item().equipmentId());
            if(w.target()!=null && !w.target().buildingId().equals(access.requireRead(user,w.item().equipmentId()))) throw error(403,"FORBIDDEN");
        }
        var values=work.stream().map(Work::item).toList();
        int queued=(int)values.stream().filter(i->i.status()==ItemStatus.QUEUED).count();
        int active=(int)values.stream().filter(i->Set.of(ItemStatus.ACTIVE,ItemStatus.DISPATCHING).contains(i.status())).count();
        int completed=(int)values.stream().filter(i->Set.of(ItemStatus.MATCHED,ItemStatus.ALREADY_TARGET).contains(i.status())).count();
        int attention=(int)values.stream().filter(i->Set.of(ItemStatus.UNSUPPORTED,ItemStatus.FAILED,ItemStatus.UNCONFIRMED,ItemStatus.NOT_SENT).contains(i.status())).count();
        long updated=jdbc.queryForObject("SELECT MAX(updated_at) FROM biz_daikin_control_batch_item WHERE batch_id=?",Long.class,id);
        // 排队时长随查询时间增长，但终止未派发项的时间固定在更新时刻。
        values=values.stream().map(i->new Item(i.itemId(),i.equipmentId(),i.status(),i.reasonCode(),i.operationId(),i.observedValue(),i.unitStatus(),i.createdAt(),
                i.dispatchedAt(),i.acceptedAt(),i.readbackAt(),i.status()==ItemStatus.QUEUED?Math.max(0,now-i.createdAt()):i.queuedMillis(),i.acceptanceMillis(),i.readbackMillis())).toList();
        return new Batch(id,Action.valueOf((String)row.get("action")),(String)row.get("requested_value"),((Number)row.get("created_at")).longValue(),updated,
                queued+active==0,values.size(),completed,queued,active,attention,values);
    }
    PageResponse<Batch> history(long user,int page,int size,DaikinControlAccess access,long now) {
        access.requireAccount(user);
        if(page<1 || size<1 || size>20) throw error(400,"INVALID_REQUEST");
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_batch WHERE operator_id=?",Long.class,user);
        var ids=jdbc.queryForList("SELECT batch_id FROM biz_daikin_control_batch WHERE operator_id=? ORDER BY created_at DESC,batch_id DESC LIMIT ? OFFSET ?",
                String.class,user,size,(long)(page-1)*size);
        return new PageResponse<>(page,size,total,ids.stream().map(id->get(user,id,access,now)).toList());
    }
    Batch cancel(long user,String batch,DaikinControlAccess access,Clock clock) {
        get(user,batch,access,clock.millis());
        tx.executeWithoutResult(status->{
            long now=clock.millis();
            for(var w:items(batch)) {
                access.requireControl(user,w.item().equipmentId());
                if(jdbc.update("UPDATE biz_daikin_control_batch_item SET status='CANCELLED',reason_code='CANCELLED_BY_USER',updated_at=? WHERE item_id=? AND status='QUEUED'",now,w.item().itemId())==1)
                    evidence(w,"CANCELLED",user,now);
            }
        });
        return get(user,batch,access,clock.millis());
    }

    List<Work> candidates() {
        // 每条控制器只扫描最早排队项，避免一个忙控制器的百台队列遮住其他控制器。
        return jdbc.query("""
                SELECT i.*,b.operator_id,b.action,b.requested_value,b.trace_id
                FROM biz_daikin_control_batch_item i JOIN biz_daikin_control_batch b ON b.batch_id=i.batch_id
                WHERE i.status='QUEUED' AND NOT EXISTS (SELECT 1 FROM biz_daikin_control_batch_item older
                  WHERE older.controller_key=i.controller_key AND older.status='QUEUED'
                  AND (older.created_at<i.created_at OR (older.created_at=i.created_at AND older.item_id<i.item_id)))
                ORDER BY i.created_at,i.item_id LIMIT 100
                """,this::map);
    }

    Work claim(Work candidate,Clock clock,int concurrency) {
        return tx.execute(status->{
            jdbc.queryForList("SELECT gate_id FROM biz_daikin_control_batch_gate WHERE gate_id=1 FOR UPDATE");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_batch_lane",Integer.class)>=concurrency) return null;
            jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE",candidate.target().sourceId());
            Work current=lock(candidate.item().itemId());
            if(current.item().status()!=ItemStatus.QUEUED) return null;
            // 恢复可能发生在两次扫描之间；槽已释放也不能让异常之前的旧排队项漏过停止边界。
            if(closedAfterCreation(current)) {
                finish(current,ItemStatus.NOT_SENT,"PREVIOUS_UNKNOWN_OPERATION",null,clock.millis());
                return null;
            }
            if(!controls.canDispatchBatch(current.target(),current.item().itemId(),clock.millis())) return null;
            String token=id(); long now=clock.millis();
            jdbc.update("INSERT INTO biz_daikin_control_batch_lane(controller_key,item_id,lease_token,lease_until) VALUES(?,?,?,?)",
                    current.target().controllerKey(),current.item().itemId(),token,now+120_000);
            jdbc.update("UPDATE biz_daikin_control_batch_item SET status='DISPATCHING',dispatched_at=?,updated_at=?,lease_token=?,lease_until=? WHERE item_id=?",
                    now,now,token,now+120_000,current.item().itemId());
            evidence(current,"DISPATCHING",0,now);
            return work(current.item().itemId());
        });
    }

    /** 在 reserve/markSending 的事务中锁定领取项；防止旧线程在租约恢复之后继续发送。 */
    void requireDispatch(Work expected,Clock clock) {
        jdbc.queryForList("SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE",expected.target().sourceId());
        var current=lock(expected.item().itemId());
        if(current.item().status()!=ItemStatus.DISPATCHING || !Objects.equals(expected.token(),current.token()) || current.leaseUntil()<=clock.millis())
            throw error(409,"DISPATCH_EXPIRED");
        if(closedAfterCreation(current)) throw error(409,"CONTROLLER_RESULT_UNCONFIRMED");
        // 领取后能力读取期间，另一台可能已变为未知；发送意图落库前必须再次检查同批占用。
        if(!controls.canDispatchBatch(current.target(),current.item().itemId(),clock.millis())) throw error(409,"CONTROLLER_BUSY");
    }

    private boolean closedAfterCreation(Work work) {
        var closed=jdbc.queryForList("SELECT target_json FROM biz_daikin_control_operation WHERE source_id=? AND status='CLOSED' AND updated_at>=?",
                String.class,work.target().sourceId(),work.item().createdAt());
        return closed.stream().map(s->decode(s,DaikinControlAccess.Target.class))
                .anyMatch(t->t.controllerKey().equals(work.target().controllerKey()));
    }

    void dispatched(Work expected,Operation operation,Clock clock) {
        tx.executeWithoutResult(status->{
            Work current=lock(expected.item().itemId());
            if(current.item().status()!=ItemStatus.DISPATCHING || !Objects.equals(current.token(),expected.token())) return;
            jdbc.update("UPDATE biz_daikin_control_batch_item SET operation_id=?,status='ACTIVE',updated_at=? WHERE item_id=?",
                    operation.operationId(),clock.millis(),current.item().itemId());
        });
    }
    void failDispatch(Work expected,String reason,Clock clock) {
        tx.executeWithoutResult(status->{
            Work current=lock(expected.item().itemId());
            if(current.item().status()!=ItemStatus.DISPATCHING || !Objects.equals(current.token(),expected.token())) return;
            var operation=controls.existing(current.user(),current.operationKey(),current.operationHash());
            if(operation!=null) {
                jdbc.update("UPDATE biz_daikin_control_batch_item SET operation_id=?,status='ACTIVE',updated_at=? WHERE item_id=?",
                        operation.view().operationId(),clock.millis(),current.item().itemId());
            } else finish(current,ItemStatus.NOT_SENT,reason,null,clock.millis());
        });
    }

    /** 只读单台记录恢复批次；UNKNOWN 保留原隔离，同控制器旧队列立即终止，解除后不会补发。 */
    void synchronize(Clock clock) {
        var work=jdbc.query("""
                SELECT i.*,b.operator_id,b.action,b.requested_value,b.trace_id FROM biz_daikin_control_batch_item i
                JOIN biz_daikin_control_batch b ON b.batch_id=i.batch_id
                LEFT JOIN biz_daikin_control_operation o ON o.operation_id=i.operation_id
                WHERE i.status IN ('ACTIVE','DISPATCHING') OR (i.status='UNCONFIRMED' AND o.updated_at>=i.updated_at
                  AND (COALESCE(i.observed_value,'')<>COALESCE(o.observed_value,'')
                  OR COALESCE(i.unit_status,'')<>COALESCE(o.unit_status,'')))
                ORDER BY CASE WHEN i.status='UNCONFIRMED' THEN 1 ELSE 0 END,i.created_at,i.item_id LIMIT 100
                """,this::map);
        for(var expected:work) tx.executeWithoutResult(status->{
            Work current=lock(expected.item().itemId()); long now=clock.millis();
            if(current.item().status()==ItemStatus.DISPATCHING && current.leaseUntil()<=now) {
                failDispatch(current,"DISPATCH_INTERRUPTED",clock); current=work(current.item().itemId());
            }
            if(!Set.of(ItemStatus.ACTIVE,ItemStatus.UNCONFIRMED).contains(current.item().status()) || current.item().operationId()==null) return;
            var op=controls.get(current.item().operationId()).view();
            ItemStatus next=switch(op.status()) {
                case CLOUD_VERIFIED, CONFIRMED -> ItemStatus.MATCHED;
                case NOT_SENT -> "ALREADY_TARGET".equals(op.reasonCode())?ItemStatus.ALREADY_TARGET:ItemStatus.NOT_SENT;
                case REJECTED -> ItemStatus.FAILED;
                case UNKNOWN, UNCONFIRMED, CLOSED, OBSERVED_STABLE -> ItemStatus.UNCONFIRMED;
                default -> ItemStatus.ACTIVE;
            };
            Long accepted=jdbc.queryForObject("SELECT MIN(operation_time) FROM biz_daikin_control_event WHERE operation_id=? AND status='ACCEPTED'",Long.class,op.operationId());
            Long readback=current.item().readbackAt();
            if(readback==null && current.value().equals(op.observedValue()) && op.lastReadAt()!=null) readback=op.lastReadAt();
            jdbc.update("UPDATE biz_daikin_control_batch_item SET accepted_at=?,readback_at=?,observed_value=?,unit_status=?,reason_code=?,updated_at=? WHERE item_id=?",
                    accepted,readback,op.observedValue(),op.unitStatus(),op.reasonCode(),now,current.item().itemId());
            // 未确认项仍同步后台后续真实读值；目标相同也不改变批次结论、不恢复已终止队列。
            if(current.item().status()==ItemStatus.UNCONFIRMED) return;
            if(next==ItemStatus.UNCONFIRMED) blockQueued(current.target().controllerKey(),now,
                    op.status()==Status.CLOSED && !op.isolated()?op.updatedAt():now);
            if(next!=ItemStatus.ACTIVE) finish(current,next,op.reasonCode(),op.observedValue(),now);
        });
        // 同时识别单台控制留下的未知占用，而不只检查本批次发出的操作。
        var uncertain=new LinkedHashSet<>(jdbc.queryForList("SELECT s.controller_key FROM biz_daikin_control_controller_slot s JOIN biz_daikin_control_operation o ON o.operation_id=s.operation_id WHERE o.status IN ('UNKNOWN','UNCONFIRMED','CLOSED','OBSERVED_STABLE')",String.class));
        // 升级前的未知操作可能只有设备槽，不能因为缺少 V69 控制器槽而将旧队列留待补发。
        var legacy=jdbc.queryForList("""
                SELECT o.target_json FROM biz_daikin_control_operation o JOIN biz_daikin_control_slot s ON s.operation_id=o.operation_id
                WHERE o.status IN ('UNKNOWN','UNCONFIRMED','CLOSED','OBSERVED_STABLE')
                AND NOT EXISTS (SELECT 1 FROM biz_daikin_control_controller_slot c WHERE c.operation_id=o.operation_id)
                """,String.class);
        for(String target:legacy) uncertain.add(decode(target,DaikinControlAccess.Target.class).controllerKey());
        for(String key:uncertain) tx.executeWithoutResult(status->{
            blockQueued(key,clock.millis(),clock.millis());
        });
    }
    private void blockQueued(String key,long now,long cutoff) {
        var queued=jdbc.query("SELECT i.*,b.operator_id,b.action,b.requested_value,b.trace_id FROM biz_daikin_control_batch_item i JOIN biz_daikin_control_batch b ON b.batch_id=i.batch_id WHERE i.controller_key=? AND i.status='QUEUED' AND i.created_at<=? FOR UPDATE",this::map,key,cutoff);
        for(var w:queued) finish(w,ItemStatus.NOT_SENT,"CONTROLLER_RESULT_UNCONFIRMED",null,now);
    }
    private void finish(Work work,ItemStatus next,String reason,String observed,long now) {
        jdbc.update("UPDATE biz_daikin_control_batch_item SET status=?,reason_code=?,observed_value=COALESCE(?,observed_value),updated_at=?,lease_until=0 WHERE item_id=?",
                next.name(),reason,observed,now,work.item().itemId());
        jdbc.update("DELETE FROM biz_daikin_control_batch_lane WHERE item_id=? AND lease_token=?",work.item().itemId(),work.token());
        evidence(work,next.name(),0,now);
    }
    private List<Work> items(String batch) {
        return jdbc.query("SELECT i.*,b.operator_id,b.action,b.requested_value,b.trace_id FROM biz_daikin_control_batch_item i JOIN biz_daikin_control_batch b ON b.batch_id=i.batch_id WHERE i.batch_id=? ORDER BY i.created_at,i.item_id",this::map,batch);
    }
    Work work(String item) {
        return jdbc.query("SELECT i.*,b.operator_id,b.action,b.requested_value,b.trace_id FROM biz_daikin_control_batch_item i JOIN biz_daikin_control_batch b ON b.batch_id=i.batch_id WHERE i.item_id=?",this::map,item).getFirst();
    }
    private Work lock(String item) {
        jdbc.queryForList("SELECT item_id FROM biz_daikin_control_batch_item WHERE item_id=? FOR UPDATE",item);
        return work(item);
    }
    private Work map(ResultSet rs,int n) throws SQLException {
        Long sent=rs.getObject("dispatched_at",Long.class),accepted=rs.getObject("accepted_at",Long.class),read=rs.getObject("readback_at",Long.class);
        long created=rs.getLong("created_at");
        var item=new Item(rs.getString("item_id"),rs.getString("equipment_id"),ItemStatus.valueOf(rs.getString("status")),rs.getString("reason_code"),rs.getString("operation_id"),
                rs.getString("observed_value"),rs.getString("unit_status"),created,sent,accepted,read,Math.max(0,(sent==null?rs.getLong("updated_at"):sent)-created),
                accepted==null||sent==null?null:Math.max(0,accepted-sent),read==null||sent==null?null:Math.max(0,read-sent));
        return new Work(item,rs.getString("batch_id"),rs.getLong("operator_id"),Action.valueOf(rs.getString("action")),rs.getString("requested_value"),rs.getString("trace_id"),
                rs.getString("target_json")==null?null:decode(rs.getString("target_json"),DaikinControlAccess.Target.class),rs.getString("revision"),rs.getString("relationship_hash"),
                impactedTargets(rs.getString("impacted_targets_json")),rs.getString("lease_token"),rs.getLong("lease_until"));
    }
    private List<DaikinControlAccess.Target> impactedTargets(String value) {
        if(value==null) return List.of();
        try { return json.readValue(value,json.getTypeFactory().constructCollectionType(List.class,DaikinControlAccess.Target.class)); }
        catch(JsonProcessingException e) { throw new IllegalStateException("关联范围快照无法解析",e); }
    }
    private void evidence(Work w,String state,long actor,long now) {
        audit.append(new AuditEvidence("SYSTEM_SECURITY",w.target()==null?null:w.target().buildingId(),actor==0?"SYSTEM":"USER",actor==0?null:actor,
                "HVAC_BATCH_"+state,"HVAC_CONTROL_BATCH",w.batchId(),w.revision(),null,null,
                "equipment="+w.item().equipmentId()+";action="+w.action()+";value="+w.value()+";itemStatus="+state,
                Set.of("UNSUPPORTED","NOT_SENT","SUPERSEDED","CANCELLED").contains(state)?"REJECTED":Set.of("FAILED","UNCONFIRMED").contains(state)?"FAILED":"SUCCESS",
                null,w.trace(),LocalDateTime.ofInstant(Instant.ofEpochMilli(now),ZoneId.systemDefault()),auditProperties.getEnvironmentMode(),false));
    }
    String encode(Object value) { try { return json.writeValueAsString(value); } catch(JsonProcessingException e) { throw new IllegalArgumentException("批次快照无效",e); } }
    private <T> T decode(String value,Class<T> type) { try { return json.readValue(value,type); } catch(JsonProcessingException e) { throw new IllegalStateException("批次快照无法解析",e); } }
    static String id() { return UUID.randomUUID().toString().replace("-",""); }
    static String reason(com.platform.framework.exception.BusinessException e) { return e.getErrorCode()==null?"FORBIDDEN":e.getErrorCode().replaceFirst("^HVAC_CONTROL_",""); }
}
