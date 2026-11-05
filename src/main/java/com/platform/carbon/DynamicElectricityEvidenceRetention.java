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

/** 过期正文可以清理，原消息身份和摘要仍用于重投判断与历史定位。 */
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

    @Scheduled(initialDelay=60000,fixedDelay=60000)
    void run() {
        try { sweep(System.currentTimeMillis()); }
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
        else legacy.computeIfAbsent(point,ignored->new HashSet<>()).add(version);
    }

    private static boolean referenced(Candidate candidate,Set<String> referenced,Map<String,Set<Long>> legacy) {
        return referenced.contains(candidate.key().id())||"LEGACY_BASELINE".equals(candidate.outcome())
                &&legacy.getOrDefault(candidate.point(),Set.of()).contains(candidate.version());
    }
}
