package com.platform.iot.reliability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.identity.DeviceIdentityBinding;
import com.platform.iot.ingest.v2.AckModeResolution;
import com.platform.iot.ingest.v2.TelemetryV2Message;
import com.platform.iot.ingest.v2.V2ProcessingResult;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.*;
import java.util.function.Function;

/** 消息冻结先提交，回执收口另起短事务；崩溃后回放原绑定及首次接收时间。 */
@Service
public class TelemetryMessageJournal {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Value("${telemetry-conflicts.max-pending-intents:50000}")
    private int maxPending=50000;
    @org.springframework.beans.factory.annotation.Value("${telemetry-conflicts.max-pending-derived-tasks:50000}")
    private int maxDerived=50000;
    private final int maxVariants;
    private final long maxEvidence;
    public TelemetryMessageJournal(JdbcTemplate jdbc,ObjectMapper mapper) {this(jdbc,mapper,16,50000);}
    @org.springframework.beans.factory.annotation.Autowired
    public TelemetryMessageJournal(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,ObjectMapper mapper,
            @org.springframework.beans.factory.annotation.Value("${telemetry-conflicts.max-variants-per-fact:16}") int maxVariants,
            @org.springframework.beans.factory.annotation.Value("${telemetry-conflicts.max-evidence-rows:50000}") long maxEvidence) {
        if(maxVariants<1||maxVariants>100||maxEvidence<1)throw new IllegalArgumentException("Invalid message conflict capacity");
        this.maxVariants=maxVariants;this.maxEvidence=maxEvidence;
        this.jdbc=jdbc;this.mapper=mapper;
        tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(60);
    }
    public record Snapshot(TelemetryV2Message message,DeviceIdentityBinding binding,AckModeResolution mode,
            long receivedAt,List<RawTelemetryEvent> events) { }
    public record Entry(String id,String hash,Snapshot snapshot,String state,V2ProcessingResult terminal) { }

    public Entry find(String id) {
        var list=jdbc.query("SELECT * FROM biz_telemetry_message_intent WHERE canonical_message_id=?",(rs,n)->
                new Entry(rs.getString("canonical_message_id"),rs.getString("payload_hash"),decode(rs.getString("snapshot_json")),rs.getString("state"),terminal(rs.getString("terminal_json"))),id);
        return list.isEmpty()?null:list.getFirst();
    }
    public Entry freeze(String hash,Snapshot snapshot) {
        tx.executeWithoutResult(status->{
            try {jdbc.update("INSERT INTO biz_telemetry_message_intent (canonical_message_id,building_id,payload_hash,snapshot_json,created_ms) VALUES (?,?,?,?,?)",
                    snapshot.message().canonicalMessageId(),snapshot.binding().buildingId(),hash,encode(snapshot),System.currentTimeMillis());
                if(jdbc.update("UPDATE biz_telemetry_conflict_budget SET used_rows=used_rows+1 WHERE budget_key='PENDING_MESSAGE' AND used_rows<?",maxPending)!=1)
                    throw new IllegalStateException("Pending message capacity exceeded");
            }
            catch(DuplicateKeyException exists) { /* 已提交快照决定恢复内容。 */ }
        });
        return Objects.requireNonNull(find(snapshot.message().canonicalMessageId()));
    }
    public Entry freezeLegacy(String hash,Snapshot snapshot,V2ProcessingResult proof) {
        tx.executeWithoutResult(status->{
            try {jdbc.update("INSERT INTO biz_telemetry_message_intent (canonical_message_id,building_id,payload_hash,snapshot_json,terminal_json,state,created_ms) VALUES (?,?,?,?,?,'COMPLETE',?)",
                    snapshot.message().canonicalMessageId(),snapshot.binding().buildingId(),hash,encode(snapshot),encode(proof),System.currentTimeMillis());}
            catch(DuplicateKeyException exists) { }
        });
        return Objects.requireNonNull(find(snapshot.message().canonicalMessageId()));
    }

    public void conflict(String id,String hash,TelemetryV2Message message) {
        tx.executeWithoutResult(status->{
            jdbc.queryForObject("SELECT canonical_message_id FROM biz_telemetry_message_intent WHERE canonical_message_id=? FOR UPDATE",String.class,id);
            long now=System.currentTimeMillis();
            if(jdbc.update("UPDATE biz_telemetry_message_conflict SET last_seen_ms=?,attempt_count=attempt_count+1 WHERE canonical_message_id=? AND payload_hash=?",now,id,hash)==1)return;
            if(jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_message_conflict WHERE canonical_message_id=?",Integer.class,id)>=maxVariants
                    || jdbc.update("UPDATE biz_telemetry_conflict_budget SET used_rows=used_rows+1 WHERE budget_key='MESSAGE_EVIDENCE' AND used_rows<?",maxEvidence)!=1)
                throw new IllegalStateException("Message conflict evidence capacity exceeded");
            jdbc.update("INSERT INTO biz_telemetry_message_conflict (canonical_message_id,payload_hash,snapshot_json,first_seen_ms,last_seen_ms,attempt_count) VALUES (?,?,?,?,?,1)",id,hash,encode(message),now,now);
        });
    }

    /** 两类证据分别限额，避免消息占满自身物化所需空间；失败有界且重传不重置失败次数。 */
    public void materializeConflicts(String id,RawTelemetryLedger ledger,RawTelemetryCodec codec) {
        Entry original=find(id);
        if(original==null)return;
        var variants=jdbc.queryForList("SELECT snapshot_json,payload_hash FROM biz_telemetry_message_conflict WHERE canonical_message_id=? AND materialized=0 ORDER BY first_seen_ms LIMIT 16",id);
        for(var variant:variants) {
            try {
            String json=(String)variant.get("snapshot_json");
            boolean ready=true,applicable=false,retired=false;
            TelemetryV2Message candidate;
            try{candidate=mapper.readValue(json,TelemetryV2Message.class);}catch(JsonProcessingException failure){throw new IllegalStateException(failure);}
            var base=original.snapshot();
            long time=candidate.collectedAt()==null?candidate.adapterReceivedAt():candidate.collectedAt();
            if(!Objects.equals(candidate.deviceIdentity(),base.message().deviceIdentity())
                    || !Objects.equals(candidate.profileCode(),base.message().profileCode())
                    || candidate.profileVersion()!=base.message().profileVersion()) {
                jdbc.update("UPDATE biz_telemetry_message_conflict SET materialized=2 WHERE canonical_message_id=? AND payload_hash=?",id,variant.get("payload_hash"));
                continue; // 身份差异只能审查消息证据，不能换成原点的数值。
            }
            for(int i=0;i<base.events().size();i++) {
                var event=base.events().get(i);
                if(event.eventTime()!=time)continue;
                var metric=base.message().metrics().get(i);
                var value=candidate.metrics().stream().filter(m->metric.code().equals(m.code())&&metric.unit().equals(m.unit())).findFirst();
                if(value.isPresent()&&Double.isFinite(value.get().value().doubleValue())) {
                    var retained=ledger.retainIfKnown(codec.withValue(event,value.get().value().doubleValue()));
                    if(retained==RawTelemetryLedger.RetentionResult.UNKNOWN)ready=false;
                    else if(retained==RawTelemetryLedger.RetentionResult.RETIRED)retired=true;
                    else applicable=true;
                }
            }
            if(!ready)throw new IllegalStateException("Message conflict original unavailable");
            // 退役不补造原始事实，也不宣称更正已采用；完整报文归档至证据保留期，供既有历史审核使用。
            jdbc.update("UPDATE biz_telemetry_message_conflict SET materialized=?,retry_count=0,last_error=?,next_retry_ms=0 WHERE canonical_message_id=? AND payload_hash=? AND materialized=0",retired?3:applicable?1:2,retired?"ORIGINAL_RETIRED_USE_HISTORICAL_REVIEW":null,id,variant.get("payload_hash"));
            } catch(RuntimeException failure) {
                deferVariant(id,(String)variant.get("payload_hash"));
            }
        }
    }

    public void deferConflicts(String id) {
        jdbc.update("UPDATE biz_telemetry_message_conflict SET materialized=CASE WHEN retry_count>=9 THEN -1 ELSE 0 END,retry_count=retry_count+1,last_error='MESSAGE_CONFLICT_RECOVERY_FAILED',next_retry_ms=? WHERE canonical_message_id=? AND materialized=0",System.currentTimeMillis()+60000,id);
    }

    private void deferVariant(String id,String hash) {
        jdbc.update("UPDATE biz_telemetry_message_conflict SET materialized=CASE WHEN retry_count>=9 THEN -1 ELSE 0 END,retry_count=retry_count+1,last_error='MESSAGE_CONFLICT_RECOVERY_FAILED',next_retry_ms=? WHERE canonical_message_id=? AND payload_hash=? AND materialized=0",System.currentTimeMillis()+60000,id,hash);
    }

    public List<String> conflictMessages() {
        return jdbc.queryForList("SELECT canonical_message_id FROM biz_telemetry_message_conflict WHERE materialized=0 AND next_retry_ms<=? GROUP BY canonical_message_id ORDER BY MIN(next_retry_ms),MIN(first_seen_ms) LIMIT 50",String.class,System.currentTimeMillis());
    }
    /** 同一消息跨实例串行收口，TDengine 冻结意图使用独立事务，回执失败不能撤销原始仲裁。 */
    public <T> T locked(String id,Function<Entry,T> action) {
        return tx.execute(status->{
            jdbc.queryForObject("SELECT canonical_message_id FROM biz_telemetry_message_intent WHERE canonical_message_id=? FOR UPDATE",String.class,id);
            return action.apply(Objects.requireNonNull(find(id)));
        });
    }
    public void queueRecovered(List<RawTelemetryEvent> events) {
        for(var event:events.stream().sorted(java.util.Comparator.comparing(e->e.pointId().toLowerCase(Locale.ROOT))).toList())
            TelemetryConflictService.enqueue(jdbc,event.pointId(),event.buildingId(),event.eventTime(),"message-recovery",maxDerived);
    }
    public void complete(String id,V2ProcessingResult result) {
        int changed=jdbc.update("UPDATE biz_telemetry_message_intent SET terminal_json=?,state='COMPLETE',retry_count=0,next_retry_ms=0 WHERE canonical_message_id=? AND state IN ('PENDING','FAILED')",encode(result),id);
        if(changed==1)jdbc.update("UPDATE biz_telemetry_conflict_budget SET used_rows=used_rows-1 WHERE budget_key='PENDING_MESSAGE'");
    }
    public List<Entry> pending(long now,int limit) {
        return jdbc.queryForList("SELECT canonical_message_id FROM biz_telemetry_message_intent WHERE state='PENDING' AND next_retry_ms<=? ORDER BY next_retry_ms,created_ms LIMIT ?",String.class,now,limit)
                .stream().map(this::find).toList();
    }
    public void retry(String id,long now) {jdbc.update("UPDATE biz_telemetry_message_intent SET state=CASE WHEN retry_count>=9 THEN 'FAILED' ELSE 'PENDING' END,last_error='MESSAGE_RECOVERY_FAILED',retry_count=retry_count+1,next_retry_ms=? WHERE canonical_message_id=? AND state='PENDING'",now+60000,id);}
    private V2ProcessingResult terminal(String json) {if(json==null)return null;try{return mapper.readValue(json,V2ProcessingResult.class);}catch(JsonProcessingException ex){throw new IllegalStateException(ex);}}
    private String encode(Object value) {try{String json=mapper.writeValueAsString(value);if(json.length()>1_048_576)throw new IllegalArgumentException("Telemetry snapshot too large");return json;}catch(JsonProcessingException ex){throw new IllegalStateException(ex);}}
    private Snapshot decode(String json) {try{return mapper.readValue(json,Snapshot.class);}catch(JsonProcessingException ex){throw new IllegalStateException(ex);}}
}
