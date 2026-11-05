package com.platform.carbon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import static com.platform.carbon.DynamicElectricityContracts.*;
import static com.platform.carbon.DynamicElectricityLiveRepository.*;
import static com.platform.carbon.DynamicElectricityRepository.hash;
import static com.platform.carbon.DynamicElectricityCore.problem;

/** 消息指纹负责重投判断，数值快照仅在更正和冲突时补存。采用记录与时段任务同事务。 */
final class DynamicElectricityIntervalEvidence {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    DynamicElectricityIntervalEvidence(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}
    record Input(String id,String hash,DeviceIntervalRequest request,boolean identifiable,boolean receiptOwned) { }
    private record Recorded(String building,long from,long to,long taskRevision,String requestHash,
                            String snapshot,String snapshotHash,long version,String snapshotState) { }
    Input prepare(DeviceIntervalRequest request,String transport,String building,String identity) {
        String source=request.ingestionId();
        if(source==null&&request.sourceOrder()!=null&&request.sourceOrder().messageId()!=null&&!request.sourceOrder().messageId().isBlank())
            source=hash(transport+"|"+request.deviceIdentity()+"|"+request.profileCode()+"|"+request.sourceOrder().messageId());
        boolean receiptOwned="MQTT_STANDARD_V2".equals(transport);
        // V2的消息去重由平台回执负责。窗口外再次采用是新的业务事件，完整更正证据不能复用旧主键。
        String id=hash(transport+"|"+building+"|"+identity+"|"+(source==null||receiptOwned?UUID.randomUUID().toString():source));
        // 规范化输入保留十进制精度；比较忽略等值数值的尾零，不把接收时钟用作数据版本。
        var semantic=new LinkedHashMap<String,Object>();
        semantic.put("identity",request.deviceIdentity());semantic.put("profile",request.profileCode());semantic.put("point",request.pointId());
        semantic.put("from",request.startInclusive());semantic.put("to",request.endExclusive());semantic.put("revision",request.revision());
        semantic.put("q",request.electricityKwh()==null?null:request.electricityKwh().stripTrailingZeros());
        semantic.put("f",request.factorKgCo2PerKwh()==null?null:request.factorKgCo2PerKwh().stripTrailingZeros());
        semantic.put("nature",request.sourceNature());semantic.put("reference",request.sourceReference());semantic.put("source",request.sourceOrder());
        return new Input(id,hash(encode(semantic)),request,source!=null&&!receiptOwned,receiptOwned);
    }
    DeviceIntervalReceipt replay(Input input,PlatformTransactionManager transactions) {
        if(!input.identifiable())return null;
        var rows=jdbc.query("""
                SELECT building_id,start_ms,end_ms,task_revision,request_hash,snapshot_json,snapshot_hash,version_no,snapshot_state
                FROM biz_dynamic_interval_evidence WHERE event_id=?
                """,(r,n)->new Recorded(r.getString(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getString(5),r.getString(6),r.getString(7),r.getLong(8),r.getString(9)),input.id());
        if(rows.isEmpty())return null;
        var row=rows.getFirst();
        boolean valid=switch(row.snapshotState()) {
            case "FULL" -> row.snapshot()!=null&&hash(row.snapshot()).equals(row.snapshotHash());
            case "LIGHT" -> row.snapshot()==null&&row.snapshotHash()==null;
            case "RETIRED" -> row.snapshot()==null&&row.snapshotHash()!=null;
            default -> false;
        };
        if(!valid)throw new IllegalStateException("动态输入证据摘要或存储状态不一致");
        if(!input.hash().equals(row.requestHash())) {
            // 拒绝覆盖仍须留存规范化候选；独立事务使证据不随业务拒绝回滚，不改变当前采用值。
            var transaction=new TransactionTemplate(transactions);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            String payload=encode(Map.of("request",input.request(),"conflictWith",input.id()));
            transaction.executeWithoutResult(status->jdbc.update("""
                    INSERT INTO biz_dynamic_interval_evidence(event_id,building_id,point_id,start_ms,end_ms,version_no,task_revision,outcome,
                    request_hash,snapshot_json,snapshot_hash,created_ms,related_event_id)
                    VALUES (?,?,?,?,?,?,?,'MESSAGE_CONFLICT',?,?,?,?,?) ON DUPLICATE KEY UPDATE event_id=event_id
                    """,hash("CONFLICT|"+input.id()+"|"+input.hash()),row.building(),input.request().pointId(),
                    input.request().startInclusive().toEpochMilli(),input.request().endExclusive().toEpochMilli(),
                    row.version(),row.taskRevision(),input.hash(),payload,hash(payload),System.currentTimeMillis(),input.id()));
            throw problem("DYNAMIC_SOURCE_MESSAGE_CONFLICT","同一消息身份对应不同区间内容，已留存冲突且保留采用版本");
        }
        return new DeviceIntervalReceipt(row.building(),Instant.ofEpochMilli(row.from()),Instant.ofEpochMilli(row.to()),row.taskRevision(),"PENDING");
    }
    private String archivePrevious(Task task,String point,Reading prior) {
        if(prior.evidenceId()!=null) {
            // 首报指纹已有稳定身份，补正文时保持键和原受理结果，历史计算引用仍能定位到同一版本。
            String payload=encode(Map.of("policy","INITIAL_ACCEPTANCE","reading",prior));
            int promoted=jdbc.update("""
                    UPDATE biz_dynamic_interval_evidence SET snapshot_json=?,snapshot_hash=?,snapshot_state='FULL',archived_ms=?
                    WHERE event_id=? AND building_id=? AND point_id=? AND start_ms=? AND end_ms=?
                      AND snapshot_state='LIGHT' AND outcome='ADOPTED'
                    """,payload,hash(payload),System.currentTimeMillis(),prior.evidenceId(),task.building(),point,task.from(),task.to());
            if(promoted==1||Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT COUNT(*)>0 FROM biz_dynamic_interval_evidence WHERE event_id=?",Boolean.class,prior.evidenceId())))
                return prior.evidenceId();
            // 旧V2首报标记可已到期删除。Reading自身仍保存原值，按原内容归档基线，不改写历史结果。
            if(!"MQTT_STANDARD_V2".equals(prior.transport()))
                throw new IllegalStateException("动态采用版本的证据引用缺失");
        }
        String payload=encode(Map.of("policy","LEGACY_BASELINE","reading",prior));
        String id=hash("BASELINE|"+task.building()+"|"+point+"|"+task.from()+"|"+task.to()+"|"+hash(payload));
        jdbc.update("""
                INSERT INTO biz_dynamic_interval_evidence(event_id,building_id,point_id,start_ms,end_ms,version_no,task_revision,outcome,request_hash,snapshot_json,snapshot_hash,created_ms)
                VALUES (?,?,?,?,?,?,?,'LEGACY_BASELINE',?,?,?,?) ON DUPLICATE KEY UPDATE event_id=event_id
                """,id,task.building(),point,task.from(),task.to(),version(prior),task.revision(),hash(payload),payload,hash(payload),System.currentTimeMillis());
        return id;
    }

    Reading adopt(Task task,Input input,Reading prior,Reading next) {
        if(prior==null&&!input.identifiable())return next;
        var adopted=next.withEvidence(input.id());
        String previous=null;
        String snapshot=null;
        if(prior!=null) {
            previous=archivePrevious(task,input.request().pointId(),prior);
            snapshot=encode(Map.of("request",input.request(),"adopted",adopted,
                    "policy",input.request().revision()==0?"PLATFORM_ACCEPTANCE_V1":"EXPLICIT_REVISION"));
        }
        record(task,input,adopted,"ADOPTED",task.revision()+1,previous,snapshot);
        return adopted;
    }

    void mark(Task task,Input input,Reading adopted,String outcome) {
        if(input.receiptOwned())return;
        record(task,input,adopted,outcome,task.revision(),adopted.evidenceId(),null);
    }

    private void record(Task task,Input input,Reading adopted,String outcome,long taskRevision,String related,String snapshot) {
        try {
            jdbc.update("""
                    INSERT INTO biz_dynamic_interval_evidence(event_id,building_id,point_id,start_ms,end_ms,version_no,task_revision,
                    outcome,request_hash,snapshot_json,snapshot_hash,created_ms,snapshot_state,related_event_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,input.id(),task.building(),input.request().pointId(),task.from(),task.to(),version(adopted),
                    taskRevision,outcome,input.hash(),snapshot,snapshot==null?null:hash(snapshot),System.currentTimeMillis(),
                    snapshot==null?"LIGHT":"FULL",related);
        } catch(DuplicateKeyException concurrent) {
            // 另一时段同时复用了消息键时，回滚本次采用；重投后由已持久证据稳定判定重复或冲突。
            throw new com.platform.framework.exception.BusinessException(503,"DYNAMIC_EVENT_CONCURRENT","消息受理竞争，请按原消息重试");
        }
    }
    private static long version(Reading value){return Math.max(value.quantityRevision(),value.factorRevision());}
    private String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(JsonProcessingException failure){throw new IllegalStateException("区间证据序列化失败",failure);}
    }
}
