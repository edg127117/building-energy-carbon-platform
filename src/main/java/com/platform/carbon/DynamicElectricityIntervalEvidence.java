package com.platform.carbon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static com.platform.carbon.DynamicElectricityContracts.*;
import static com.platform.carbon.DynamicElectricityLiveRepository.*;
import static com.platform.carbon.DynamicElectricityRepository.hash;
import static com.platform.carbon.DynamicElectricityCore.problem;

/** 动态区间的不可变输入证据；与当前采用值、任务共用事务，不进入正式能源更正审核。 */
final class DynamicElectricityIntervalEvidence {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    DynamicElectricityIntervalEvidence(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}
    record Input(String id,String hash,DeviceIntervalRequest request,String requestJson) { }
    Input prepare(DeviceIntervalRequest request,String transport,String building,String identity) {
        String source=request.ingestionId();
        if(source==null&&request.sourceOrder()!=null&&request.sourceOrder().messageId()!=null&&!request.sourceOrder().messageId().isBlank())
            source=hash(transport+"|"+request.deviceIdentity()+"|"+request.profileCode()+"|"+request.sourceOrder().messageId());
        String id=hash(transport+"|"+building+"|"+identity+"|"+(source==null?UUID.randomUUID().toString():source));
        // 规范化输入保留十进制精度；比较忽略等值数值的尾零，不把接收时钟用作数据版本。
        var semantic=new LinkedHashMap<String,Object>();
        semantic.put("identity",request.deviceIdentity());semantic.put("profile",request.profileCode());semantic.put("point",request.pointId());
        semantic.put("from",request.startInclusive());semantic.put("to",request.endExclusive());semantic.put("revision",request.revision());
        semantic.put("q",request.electricityKwh()==null?null:request.electricityKwh().stripTrailingZeros());
        semantic.put("f",request.factorKgCo2PerKwh()==null?null:request.factorKgCo2PerKwh().stripTrailingZeros());
        semantic.put("nature",request.sourceNature());semantic.put("reference",request.sourceReference());semantic.put("source",request.sourceOrder());
        return new Input(id,hash(encode(semantic)),request,encode(request));
    }
    DeviceIntervalReceipt replay(Input input,org.springframework.transaction.PlatformTransactionManager transactions) {
        var rows=jdbc.query("SELECT building_id,start_ms,end_ms,task_revision,request_hash,snapshot_json,snapshot_hash,version_no FROM biz_dynamic_interval_evidence WHERE event_id=?",
                (r,n)->new Object[]{r.getString(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getString(5),r.getString(6),r.getString(7),r.getLong(8)},input.id());
        if(rows.isEmpty())return null;
        var row=rows.getFirst();
        if(!hash((String)row[5]).equals(row[6]))throw new IllegalStateException("动态输入证据摘要不一致");
        if(!input.hash().equals(row[4])) {
            // 拒绝覆盖仍须留存规范化候选；独立事务使证据不随业务拒绝回滚，不改变当前采用值。
            var transaction=new org.springframework.transaction.support.TransactionTemplate(transactions);
            transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            String payload="{\"request\":"+input.requestJson()+",\"conflictWith\":"+encode(input.id())+"}";
            transaction.executeWithoutResult(status->jdbc.update("""
                    INSERT INTO biz_dynamic_interval_evidence(event_id,building_id,point_id,start_ms,end_ms,version_no,task_revision,outcome,request_hash,snapshot_json,snapshot_hash,created_ms)
                    VALUES (?,?,?,?,?,?,?,'MESSAGE_CONFLICT',?,?,?,?) ON DUPLICATE KEY UPDATE event_id=event_id
                    """,hash("CONFLICT|"+input.id()+"|"+input.hash()),row[0],input.request().pointId(),input.request().startInclusive().toEpochMilli(),input.request().endExclusive().toEpochMilli(),row[7],row[3],input.hash(),payload,hash(payload),System.currentTimeMillis()));
            throw problem("DYNAMIC_SOURCE_MESSAGE_CONFLICT","同一消息身份对应不同区间内容，已留存冲突且保留采用版本");
        }
        return new DeviceIntervalReceipt((String)row[0],java.time.Instant.ofEpochMilli((long)row[1]),java.time.Instant.ofEpochMilli((long)row[2]),(long)row[3],"PENDING");
    }
    void baseline(Task task,String point,Reading prior) {
        if(prior==null||prior.evidenceId()!=null)return;
        String payload=encode(Map.of("policy","LEGACY_BASELINE","reading",prior));
        String id=hash("BASELINE|"+task.building()+"|"+point+"|"+task.from()+"|"+task.to()+"|"+hash(payload));
        jdbc.update("""
                INSERT INTO biz_dynamic_interval_evidence(event_id,building_id,point_id,start_ms,end_ms,version_no,task_revision,outcome,request_hash,snapshot_json,snapshot_hash,created_ms)
                VALUES (?,?,?,?,?,?,?,'LEGACY_BASELINE',?,?,?,?) ON DUPLICATE KEY UPDATE event_id=event_id
                """,id,task.building(),point,task.from(),task.to(),Math.max(prior.quantityRevision(),prior.factorRevision()),task.revision(),hash(payload),payload,hash(payload),System.currentTimeMillis());
    }
    void record(Task task,Input input,Reading adopted,String outcome,long taskRevision) {
        String payload="{\"request\":"+input.requestJson()+",\"adopted\":"+encode(adopted)+",\"policy\":"+encode(input.request().revision()==0?"PLATFORM_ACCEPTANCE_V1":"EXPLICIT_REVISION")+"}";
        try {
            jdbc.update("""
                    INSERT INTO biz_dynamic_interval_evidence(event_id,building_id,point_id,start_ms,end_ms,version_no,task_revision,outcome,request_hash,snapshot_json,snapshot_hash,created_ms)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """,input.id(),task.building(),input.request().pointId(),task.from(),task.to(),Math.max(adopted.quantityRevision(),adopted.factorRevision()),taskRevision,outcome,input.hash(),payload,hash(payload),System.currentTimeMillis());
        } catch(DuplicateKeyException concurrent) {
            // 另一时段同时复用了消息键时，回滚本次采用；重投后由已持久证据稳定判定重复或冲突。
            throw new com.platform.framework.exception.BusinessException(503,"DYNAMIC_EVENT_CONCURRENT","消息受理竞争，请按原消息重试");
        }
    }
    private String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(JsonProcessingException failure){throw new IllegalStateException("区间证据序列化失败",failure);}
    }
}
