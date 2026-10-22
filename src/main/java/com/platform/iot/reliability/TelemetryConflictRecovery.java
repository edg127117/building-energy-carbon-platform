package com.platform.iot.reliability;

import com.platform.energy.period.EnergyPeriodResultRepository;
import com.platform.iot.dataquality.LateRealMinuteCorrectionService;
import com.platform.iot.dataquality.event.HvacLateRealEventStoredEvent;
import com.platform.iot.ingest.v2.TelemetryV2IngestionService;
import com.platform.iot.temporal.impl.TdengineHvacRawEventRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.MeterRegistry;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** 有界恢复原始意图、消息回执和更正派生结果；过期待办不自动获得“正确”结论。 */
@Service
@ConditionalOnProperty(prefix="telemetry-conflicts",name="recovery-enabled",havingValue="true",matchIfMissing=true)
public class TelemetryConflictRecovery {
    private static final Logger log=LoggerFactory.getLogger(TelemetryConflictRecovery.class);
    private final JdbcTemplate jdbc;
    private final RawTelemetryLedger ledger;
    private final TdengineHvacRawEventRepository raw;
    private final TelemetryMessageJournal messages;
    private final TelemetryV2IngestionService ingestion;
    private final ObjectProvider<LateRealMinuteCorrectionService> minutes;
    private final EnergyPeriodResultRepository periods;
    private final TransactionTemplate tx;
    private final long expiryMillis;
    private final MeterRegistry metrics;
    private final RawTelemetryCodec codec;
    @Value("${aggregation.finalization-delay-seconds:30}")
    private int finalizationDelaySeconds=30;

    public TelemetryConflictRecovery(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,RawTelemetryLedger ledger,
            TdengineHvacRawEventRepository raw,TelemetryMessageJournal messages,TelemetryV2IngestionService ingestion,
            ObjectProvider<LateRealMinuteCorrectionService> minutes,EnergyPeriodResultRepository periods,
            MeterRegistry metrics,RawTelemetryCodec codec,@Value("${telemetry-conflicts.unverified-days:7}") int days) {
        if(days<1||days>180)throw new IllegalArgumentException("Invalid conflict expiry");
        this.jdbc=jdbc;this.ledger=ledger;this.raw=raw;this.messages=messages;this.ingestion=ingestion;
        this.minutes=minutes;this.periods=periods;this.metrics=metrics;this.codec=codec;expiryMillis=Duration.ofDays(days).toMillis();
        tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        tx.setTimeout(60);
    }

    @Scheduled(fixedDelayString="${telemetry-conflicts.recovery-delay-ms:60000}",initialDelayString="${telemetry-conflicts.recovery-initial-delay-ms:60000}")
    public void recover() {
        long now=System.currentTimeMillis();
        for(var event:ledger.pending(50,now)) {
            try{raw.replayRecovered(event);}catch(RuntimeException failure){ledger.retryLater(event,now);failure("raw",failure);}
        }
        for(var entry:messages.pending(now,20)) {
            try{var result=ingestion.recover(entry.id());if(result.retryable())messages.retry(entry.id(),now);}
            catch(RuntimeException failure){messages.retry(entry.id(),now);failure("message",failure);}
        }
        for(String id:messages.conflictMessages()) {
            try {messages.materializeConflicts(id,ledger,codec);}
            catch(RuntimeException failure){messages.deferConflicts(id);failure("conflict_evidence",failure);}
        }
        var tasks=jdbc.queryForList("SELECT task_id,request_revision FROM biz_telemetry_change_task WHERE state='PENDING' AND next_retry_ms<=? ORDER BY next_retry_ms,task_id LIMIT 50",now);
        for(var claimed:tasks) {
            String task=(String)claimed.get("task_id");
            try{apply(task);}catch(RuntimeException failure){
                jdbc.update("UPDATE biz_telemetry_change_task SET state=CASE WHEN retry_count>=9 THEN 'FAILED' ELSE 'PENDING' END,last_error='DERIVED_RECOVERY_FAILED',retry_count=retry_count+1,next_retry_ms=? WHERE task_id=? AND state='PENDING' AND request_revision=?",now+60000,task,claimed.get("request_revision"));
                failure("derived",failure);
            }
        }
        expire(now);
    }

    public void apply(String id) {
        tx.executeWithoutResult(status->{
            var rows=jdbc.queryForList("SELECT point_id,building_id,event_ms FROM biz_telemetry_change_task WHERE task_id=? AND state='PENDING'",id);
            if(rows.isEmpty())return;
            var task=rows.getFirst();String point=(String)task.get("point_id"),building=(String)task.get("building_id");
            long time=((Number)task.get("event_ms")).longValue();
            long readyAt=time-Math.floorMod(time,60000)+60000+finalizationDelaySeconds*1000L;
            if(System.currentTimeMillis()<readyAt) {
                jdbc.update("UPDATE biz_telemetry_change_task SET next_retry_ms=? WHERE task_id=?",readyAt,id);return;
            }
            // 跨实例派生重算和选择更正共用点级数据库锁，不依赖 JVM 锁或可能过期的租约。
            jdbc.queryForObject("SELECT point_key FROM biz_telemetry_retention_boundary WHERE point_key=? FOR UPDATE",String.class,point.toLowerCase(Locale.ROOT));
            var state=jdbc.queryForList("SELECT state FROM biz_telemetry_change_task WHERE task_id=? FOR UPDATE",String.class,id);
            if(!state.contains("PENDING"))return;
            var events=raw.findWindow(time,time+1,true).stream().filter(e->point.equals(e.pointId())&&building.equals(e.buildingId())).toList();
            if(events.size()!=1)throw new IllegalStateException("Confirmed correction original unavailable");
            boolean minuteUnavailable=false;
            if(events.getFirst().isForCalc()==1) {
                var service=minutes.getIfAvailable();
                if(service==null)minuteUnavailable=true;
                else service.applyConfirmedCorrection(new HvacLateRealEventStoredEvent(point,building,time-Math.floorMod(time,60000),System.currentTimeMillis()));
            }
            // 更正可能是累计量或积分锚点：取前后真实样本限定受影响区间，不猜固定天数。
            long[] range=raw.correctionInfluence(point,time);
            var affected=jdbc.queryForList("""
                    SELECT DISTINCT period_type,period_start,period_end FROM (
                      SELECT period_type,period_start,period_end FROM biz_energy_period_result_current WHERE building_id=? AND point_id=?
                      UNION SELECT period_type,period_start,period_end FROM biz_energy_period_result_snapshot WHERE building_id=? AND point_id=?
                    ) p WHERE period_end>=? AND period_start<=? ORDER BY period_start,period_type LIMIT 5001
                    """,building,point,building,point,new Timestamp(range[0]),new Timestamp(range[1]));
            if(affected.size()>5000)throw new IllegalStateException("Affected period capacity exceeded");
            for(var period:affected)periods.markDirty(RawTelemetryLedger.id(),building,point,(String)period.get("period_type"),
                    ((Timestamp)period.get("period_start")).toInstant(),((Timestamp)period.get("period_end")).toInstant(),"TELEMETRY_CORRECTION",LocalDateTime.now());
            jdbc.update("UPDATE biz_telemetry_change_task SET state=?,last_error=?,retry_count=0 WHERE task_id=?",
                    minuteUnavailable?"FAILED":"COMPLETE",minuteUnavailable?"MINUTE_CORRECTION_DISABLED":null,id);
            if(!minuteUnavailable)jdbc.update("UPDATE biz_telemetry_conflict_budget SET used_rows=used_rows-1 WHERE budget_key='PENDING_DERIVED'");
        });
    }

    public void expire(long now) {
        var facts=jdbc.query("SELECT * FROM biz_telemetry_raw_intent WHERE conflict_state='OPEN' AND first_conflict_ms<? ORDER BY first_conflict_ms LIMIT 100",RawTelemetryLedger::mapFact,now-expiryMillis);
        for(var fact:facts)jdbc.update("UPDATE biz_telemetry_raw_intent SET conflict_state='EXPIRED',conflict_revision=conflict_revision+1 WHERE point_key=? AND event_ms=? AND conflict_state='OPEN' AND first_conflict_ms<?",fact.pointKey(),fact.eventTime(),now-expiryMillis);
        // 过期状态和证据保留分离。无处置引用的负载到期后只保留内容指纹，重传仍合并。
        long cutoff=now-Duration.ofDays(180).toMillis();
        tx.executeWithoutResult(status->{
            var ids=jdbc.queryForList("SELECT e.evidence_id FROM biz_telemetry_conflict_evidence e WHERE e.snapshot_json IS NOT NULL AND e.last_seen_ms<? AND NOT EXISTS (SELECT 1 FROM biz_telemetry_resolution r WHERE r.evidence_id=e.evidence_id) ORDER BY e.last_seen_ms LIMIT 100",String.class,cutoff);
            for(String id:ids) {
                int changed=jdbc.update("UPDATE biz_telemetry_conflict_evidence SET snapshot_json=NULL WHERE evidence_id=? AND snapshot_json IS NOT NULL AND NOT EXISTS (SELECT 1 FROM biz_telemetry_resolution r WHERE r.evidence_id=?)",id,id);
                if(changed==1)jdbc.update("UPDATE biz_telemetry_conflict_budget SET used_rows=used_rows-1 WHERE budget_key='EVIDENCE'");
            }
        });
        // 成功消息冻结快照沿用热回执期限；争议快照留存 180 天，未完成恢复意图不清理。
        // 每轮最多 20,000 个热快照；固定批次允许清理吞吐超过 200 消息/秒的热数据增量。
        for(int batch=0;batch<40;batch++) {
            var old=jdbc.queryForList("SELECT canonical_message_id FROM biz_telemetry_message_intent WHERE state='COMPLETE' AND created_ms<? AND NOT EXISTS(SELECT 1 FROM biz_telemetry_message_conflict c WHERE c.canonical_message_id=biz_telemetry_message_intent.canonical_message_id) ORDER BY created_ms LIMIT 500",String.class,now-Duration.ofDays(1).toMillis());
            for(String id:old)jdbc.update("DELETE FROM biz_telemetry_message_intent WHERE canonical_message_id=? AND state='COMPLETE' AND NOT EXISTS(SELECT 1 FROM biz_telemetry_message_conflict c WHERE c.canonical_message_id=biz_telemetry_message_intent.canonical_message_id)",id);
            if(old.size()<500)break;
        }
        var messageIds=jdbc.queryForList("SELECT i.canonical_message_id FROM biz_telemetry_message_intent i WHERE i.state='COMPLETE' AND EXISTS(SELECT 1 FROM biz_telemetry_message_conflict c WHERE c.canonical_message_id=i.canonical_message_id AND c.last_seen_ms<? AND c.materialized IN (1,2)) ORDER BY i.created_ms LIMIT 100",String.class,cutoff);
        for(String id:messageIds)tx.executeWithoutResult(status->{
            jdbc.queryForObject("SELECT canonical_message_id FROM biz_telemetry_message_intent WHERE canonical_message_id=? FOR UPDATE",String.class,id);
            int removed=jdbc.update("DELETE FROM biz_telemetry_message_conflict WHERE canonical_message_id=? AND last_seen_ms<? AND materialized IN (1,2)",id,cutoff);
            if(removed>0)jdbc.update("UPDATE biz_telemetry_conflict_budget SET used_rows=used_rows-? WHERE budget_key='MESSAGE_EVIDENCE'",removed);
        });
        var retired=jdbc.query("SELECT i.* FROM biz_telemetry_raw_intent i JOIN biz_telemetry_retention_boundary b ON i.point_key=b.point_key WHERE i.storage_state='PERSISTED' AND i.event_ms<b.before_ms AND i.first_conflict_ms<? AND NOT EXISTS(SELECT 1 FROM biz_telemetry_resolution r WHERE r.point_key=i.point_key AND r.event_ms=i.event_ms) ORDER BY i.first_conflict_ms LIMIT 100",RawTelemetryLedger::mapFact,cutoff);
        for(var fact:retired)tx.executeWithoutResult(status->{
            jdbc.queryForObject("SELECT point_key FROM biz_telemetry_retention_boundary WHERE point_key=? FOR UPDATE",String.class,fact.pointKey());
            var protectedRows=jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_resolution WHERE point_key=? AND event_ms=?",Integer.class,fact.pointKey(),fact.eventTime());
            if(protectedRows>0)return;
            int payloads=jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_conflict_evidence WHERE point_key=? AND event_ms=? AND snapshot_json IS NOT NULL",Integer.class,fact.pointKey(),fact.eventTime());
            jdbc.update("DELETE FROM biz_telemetry_conflict_evidence WHERE point_key=? AND event_ms=?",fact.pointKey(),fact.eventTime());
            jdbc.update("DELETE FROM biz_telemetry_raw_intent WHERE point_key=? AND event_ms=?",fact.pointKey(),fact.eventTime());
            if(payloads>0)jdbc.update("UPDATE biz_telemetry_conflict_budget SET used_rows=used_rows-? WHERE budget_key='EVIDENCE'",payloads);
        });
        var done=jdbc.queryForList("SELECT task_id FROM biz_telemetry_change_task WHERE state='COMPLETE' LIMIT 100",String.class);
        for(String id:done)jdbc.update("DELETE FROM biz_telemetry_change_task WHERE task_id=? AND state='COMPLETE'",id);
    }
    private void failure(String stage,RuntimeException error){metrics.counter("iot.telemetry.conflict.recovery_failed","stage",stage).increment();log.warn("D01恢复暂未完成: stage={}, type={}",stage,error.getClass().getSimpleName());}
}
