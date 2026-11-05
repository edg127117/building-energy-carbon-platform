package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.Collections;

/** 完整正文按引用保护清理；旧V2轻量标记核对自包含输入后删除，其他入口保留原消息指纹。 */
@Component
@Profile("!test")
final class DynamicElectricityEvidenceRetention {
    private static final Logger log=LoggerFactory.getLogger(DynamicElectricityEvidenceRetention.class);
    private static final long RETENTION_MS=Duration.ofDays(180).toMillis();
    private static final long RUN_NANOS=Duration.ofSeconds(3).toNanos();
    private static final int BATCH_SIZE=128;
    private final JdbcTemplate jdbc;
    private final DynamicElectricityLiveRepository tasks;
    private final DynamicElectricityRepository results;
    private final TransactionTemplate transaction;
    private Cursor cursor=new Cursor(0,"");
    private Cursor markerCursor=new Cursor(0,"");

    DynamicElectricityEvidenceRetention(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
            DynamicElectricityLiveRepository tasks,DynamicElectricityRepository results,PlatformTransactionManager transactions) {
        this.jdbc=jdbc;
        this.tasks=tasks;
        this.results=results;
        this.transaction=new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(3);
    }

    private record Cursor(long created,String id) { }
    private record Window(String building,long from,long to) { }
    private record Candidate(Window window,Cursor key,String point,long version,String outcome) { }
    private record Marker(Candidate candidate,String related) { }

    @Scheduled(initialDelay=60000,fixedDelay=60000)
    void run() {
        try { sweep(System.currentTimeMillis()); sweepMarkers(System.currentTimeMillis()); }
        catch(RuntimeException ex) { log.warn("动态区间证据清理暂不可用",ex); }
    }

    void sweep(long now) {
        long cutoff=now-RETENTION_MS;
        long started=System.nanoTime();
        var batch=jdbc.query("""
                SELECT event_id,building_id,start_ms,end_ms,point_id,version_no,outcome,created_ms
                FROM biz_dynamic_interval_evidence
                WHERE snapshot_state='FULL' AND created_ms<?
                  AND (created_ms>? OR (created_ms=? AND event_id>?))
                  AND (archived_ms IS NULL OR archived_ms<?)
                ORDER BY created_ms,event_id LIMIT ?
                """,(r,n)->new Candidate(new Window(r.getString("building_id"),r.getLong("start_ms"),r.getLong("end_ms")),
                new Cursor(r.getLong("created_ms"),r.getString("event_id")),r.getString("point_id"),r.getLong("version_no"),
                r.getString("outcome")),cutoff,cursor.created(),cursor.created(),cursor.id(),cutoff,BATCH_SIZE);
        if(batch.isEmpty()) {
            cursor=new Cursor(0,"");
            return;
        }
        int start=0;
        while(start<batch.size()&&System.nanoTime()-started<RUN_NANOS) {
            var window=batch.get(start).window();
            int end=start+1;
            while(end<batch.size()&&window.equals(batch.get(end).window()))end++;
            var group=batch.subList(start,end);
            try { transaction.executeWithoutResult(status->retire(window,group,cutoff)); }
            catch(RuntimeException ex) {
                // 同窗口摘要异常或暂时失败时整批保留，游标回卷后可重试。
                log.warn("保留动态区间证据，无法完成窗口引用复核 {}",window,ex);
            }
            cursor=batch.get(end-1).key();
            start=end;
        }
    }

    void sweepMarkers(long now) {
        long cutoff=now-Duration.ofDays(7).toMillis();
        long started=System.nanoTime();
        var batch=jdbc.query("""
                SELECT event_id,building_id,start_ms,end_ms,point_id,version_no,outcome,created_ms,related_event_id
                FROM biz_dynamic_interval_evidence
                WHERE snapshot_state='LIGHT' AND created_ms<?
                  AND (created_ms>? OR (created_ms=? AND event_id>?))
                ORDER BY created_ms,event_id LIMIT 512
                """,(r,n)->new Marker(new Candidate(new Window(r.getString("building_id"),r.getLong("start_ms"),r.getLong("end_ms")),
                new Cursor(r.getLong("created_ms"),r.getString("event_id")),r.getString("point_id"),r.getLong("version_no"),
                r.getString("outcome")),r.getString("related_event_id")),
                cutoff,markerCursor.created(),markerCursor.created(),markerCursor.id());
        if(batch.isEmpty()) {markerCursor=new Cursor(0,"");return;}
        int start=0;
        while(start<batch.size()&&System.nanoTime()-started<RUN_NANOS) {
            var window=batch.get(start).candidate().window();
            int end=start+1;
            while(end<batch.size()&&window.equals(batch.get(end).candidate().window()))end++;
            var group=batch.subList(start,end);
            try {transaction.executeWithoutResult(status->removeMarkers(window,group,cutoff));}
            catch(RuntimeException ex) {log.warn("旧动态首报标记引用复核未完成 {}",window,ex);}
            markerCursor=batch.get(end-1).candidate().key();
            start=end;
        }
    }

    private void removeMarkers(Window window,List<Marker> group,long cutoff) {
        var task=tasks.lockForEvidenceRetention(window.building(),window.from(),window.to());
        if(task==null)return;
        var owners=new HashMap<String,JsonNode>();
        task.readings().forEach((point,reading)->rememberReading(point,CarbonEvidence.tree(reading),owners));
        results.visitIntervalResults(window.building(),window.from(),window.to(),result->{
            if(!"DEVICE_INTERVAL".equals(result.sourceKind()))return;
            var evidence=result.electricityEvidence();
            if(evidence==null||!evidence.isObject())throw new IllegalStateException("历史区间缺少自包含输入");
            evidence.fields().forEachRemaining(entry->rememberReading(entry.getKey(),entry.getValue(),owners));
        });
        var related=group.stream().map(Marker::related).filter(Objects::nonNull).distinct().toList();
        if(!related.isEmpty()) {
            var arguments=new ArrayList<Object>();
            arguments.add(window.building());arguments.add(window.from());arguments.add(window.to());arguments.addAll(related);
            jdbc.query("""
                    SELECT event_id,point_id,snapshot_json,snapshot_hash FROM biz_dynamic_interval_evidence
                    WHERE building_id=? AND start_ms=? AND end_ms=? AND snapshot_state='FULL' AND event_id IN (
                    """+String.join(",",Collections.nCopies(related.size(),"?"))+")",r->{
                String payload=r.getString("snapshot_json");
                if(!DynamicElectricityRepository.hash(payload).equals(r.getString("snapshot_hash")))
                    throw new IllegalStateException("关联采用正文摘要不一致");
                var body=CarbonEvidence.parse(payload);
                var reading=body.has("adopted")?body.get("adopted"):body.get("reading");
                if(reading!=null)rememberReading(r.getString("point_id"),reading,owners);
            },arguments.toArray());
        }
        var removable=new ArrayList<Marker>();
        for(var marker:group) {
            var candidate=marker.candidate();
            String reference="ADOPTED".equals(candidate.outcome())?candidate.key().id():marker.related();
            var reading=owners.get(reference);
            if(reading==null||!"MQTT_STANDARD_V2".equals(reading.path("transport").asText()))continue;
            if(!candidate.point().equals(reading.path("_point").asText())
                    ||candidate.version()!=Math.max(reading.path("quantityRevision").asLong(),reading.path("factorRevision").asLong()))
                throw new IllegalStateException("首报标记与自包含版本不匹配");
            removable.add(marker);
        }
        if(removable.isEmpty())return;
        // 旧ID保留在原始JSON中作为历史关联标识；数值和来源已在同一快照中，删除标记不改动任何历史摘要。
        jdbc.batchUpdate("""
                DELETE FROM biz_dynamic_interval_evidence
                WHERE event_id=? AND snapshot_state='LIGHT' AND created_ms<?
                """,removable,removable.size(),(statement,marker)->{
            statement.setString(1,marker.candidate().key().id());statement.setLong(2,cutoff);
        });
    }

    private static void rememberReading(String point,JsonNode reading,Map<String,JsonNode> owners) {
        String id=reading.path("evidenceId").asText(null);
        if(id==null||!"MQTT_STANDARD_V2".equals(reading.path("transport").asText()))return;
        if(!reading.path("quantityRevision").canConvertToLong()||!reading.path("factorRevision").canConvertToLong()
                ||!reading.hasNonNull("quantity")||!reading.hasNonNull("factor")
                ||!reading.hasNonNull("identityId")||!reading.hasNonNull("nature")||!reading.hasNonNull("reference"))
            throw new IllegalStateException("采用版本缺少完整数值或来源");
        var copy=CarbonEvidence.parse(CarbonEvidence.canonical(reading));
        // 正文可能使用JSON整数，冻结结果使用十进制字符串；按数值核对，不能因存储表示不同阻止清理。
        for(String field:List.of("quantity","factor"))
            ((com.fasterxml.jackson.databind.node.ObjectNode)copy).put(field,
                    new java.math.BigDecimal(reading.path(field).asText()).stripTrailingZeros().toPlainString());
        ((com.fasterxml.jackson.databind.node.ObjectNode)copy).put("_point",point);
        var previous=owners.putIfAbsent(id,copy);
        if(previous!=null&&!previous.equals(copy))throw new IllegalStateException("同一证据引用对应不同采用版本");
    }

    private void retire(Window window,List<Candidate> group,long cutoff) {
        // 采用、重新入队和结果发布都锁定同一任务行。先锁定再读已发布结果，避免清掉并发生成的引用。
        var task=tasks.lockForEvidenceRetention(window.building(),window.from(),window.to());
        List<Candidate> eligible;
        if(task==null||!"READY".equals(task.state())) {
            // 冲突候选从未进入采用值或计算任务，任务行不存在或正被锁定也可清其到期正文。
            eligible=group.stream().filter(candidate->"MESSAGE_CONFLICT".equals(candidate.outcome())).toList();
        } else {
            var referenced=new HashSet<String>();
            var legacy=new HashMap<String,Set<Long>>();
            task.readings().forEach((point,reading)->collect(point,reading.evidenceId(),
                    Math.max(reading.quantityRevision(),reading.factorRevision()),referenced,legacy));
            eligible=new ArrayList<>();
            for(var candidate:group)
                if(!referenced(candidate,referenced,legacy))eligible.add(candidate);
            if(eligible.stream().anyMatch(candidate->!"MESSAGE_CONFLICT".equals(candidate.outcome()))) {
                results.visitIntervalResults(window.building(),window.from(),window.to(),result->{
                    if(!"DEVICE_INTERVAL".equals(result.sourceKind()))return;
                    JsonNode evidence=result.electricityEvidence();
                    if(evidence==null||!evidence.isObject())throw new IllegalStateException("动态区间结果证据无效");
                    evidence.fields().forEachRemaining(entry->{
                        var reading=entry.getValue();
                        if(!reading.path("quantityRevision").canConvertToLong()||!reading.path("factorRevision").canConvertToLong())
                            throw new IllegalStateException("动态区间结果版本引用无效");
                        collect(entry.getKey(),reading.path("evidenceId").asText(null),
                                Math.max(reading.path("quantityRevision").longValue(),reading.path("factorRevision").longValue()),
                                referenced,legacy);
                    });
                });
                eligible.removeIf(candidate->referenced(candidate,referenced,legacy));
            }
        }
        if(eligible.isEmpty())return;
        jdbc.batchUpdate("""
                UPDATE biz_dynamic_interval_evidence SET snapshot_json=NULL,snapshot_state='RETIRED'
                WHERE event_id=? AND snapshot_state='FULL' AND created_ms<? AND (archived_ms IS NULL OR archived_ms<?)
                """,eligible,eligible.size(),(statement,candidate)->{
            statement.setString(1,candidate.key().id());
            statement.setLong(2,cutoff);
            statement.setLong(3,cutoff);
        });
    }

    private static void collect(String point,String id,long version,Set<String> referenced,Map<String,Set<Long>> legacy) {
        if(id!=null)referenced.add(id);
        // 已清理首报标记的历史引用仍由自包含版本保护后补的基线正文。
        legacy.computeIfAbsent(point,ignored->new HashSet<>()).add(version);
    }

    private static boolean referenced(Candidate candidate,Set<String> referenced,Map<String,Set<Long>> legacy) {
        return referenced.contains(candidate.key().id())||"LEGACY_BASELINE".equals(candidate.outcome())
                &&legacy.getOrDefault(candidate.point(),Set.of()).contains(candidate.version());
    }
}
