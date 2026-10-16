package com.platform.iot.daikin.control;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.audit.*;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.platform.iot.daikin.control.DaikinBatchContracts.*;
import static com.platform.iot.daikin.control.DaikinControlContracts.*;
import static org.assertj.core.api.Assertions.*;

/** H2、内存厂家与可控时钟；所有规模和竞态验证均不连接真实设备/数据库/网络。 */
class DaikinBatchControlTest {
    static class Fixture implements AutoCloseable {
        final ControlFixture f;
        final Map<String,ObjectNode> units=new ConcurrentHashMap<>();
        final Set<String> unknown=ConcurrentHashMap.newKeySet(),reject=ConcurrentHashMap.newKeySet(),noApply=ConcurrentHashMap.newKeySet(),delayedRun=ConcurrentHashMap.newKeySet(),readFailure=ConcurrentHashMap.newKeySet();
        final AtomicInteger sends=new AtomicInteger(),reads=new AtomicInteger();
        final DaikinControlService controls;
        final DaikinBatchStore store;
        final DaikinBatchService service;
        Runnable onSend=()->{};
        Runnable onRead=()->{};
        Fixture(int count,int perController) throws Exception { this(count,perController,null); }
        Fixture(int count,int perController,java.util.function.Function<Fixture,DaikinControlGateway> factory) throws Exception {
            f=new ControlFixture(); f.props.setTestOpenControls(true);
            f.jdbc.execute("ALTER TABLE biz_equipment ADD equip_name VARCHAR(100)");
            f.jdbc.execute("ALTER TABLE biz_equipment ADD equip_code VARCHAR(100)");
            f.jdbc.update("DELETE FROM biz_daikin_control_grant");
            String sql=(Files.readString(Path.of("src/env/init/V70__mysql_daikin_batch_control.sql"))
                    + Files.readString(Path.of("src/env/init/V71__mysql_daikin_batch_overlap.sql"))
                    + Files.readString(Path.of("src/env/init/V72__mysql_daikin_batch_parallel.sql")))
                    .replace(" COLLATE utf8mb4_bin","").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","");
            // MySQL 原子替换主键；H2 只拆分 DDL，真实迁移另由隔离 MySQL 执行。
            for (String table : List.of("biz_daikin_control_controller_slot", "biz_daikin_control_batch_lane"))
                sql = sql.replace("ALTER TABLE " + table + "\n DROP PRIMARY KEY,", "ALTER TABLE " + table + " DROP PRIMARY KEY;\nALTER TABLE " + table);
            try(var c=f.jdbc.getDataSource().getConnection()) { ScriptUtils.executeSqlScript(c,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))); }
            for(int i=0;i<count;i++) {
                String id=device(i),vendor="v"+i,unit="u"+i,controller="c"+(i/perController);
                f.jdbc.update("INSERT INTO biz_equipment(equip_id,building_id,product_id,del_flag,equip_name,equip_code) VALUES (?,'building','product',0,?,?)",id,"模拟空调"+i,"TEST-"+i);
                f.jdbc.update("INSERT INTO biz_device_identity VALUES (?,?,'building','DAIKIN_UNIT',1,'DAIKIN_INDOOR')","i"+i,id);
                f.jdbc.update("INSERT INTO biz_pending_device VALUES (?,?,'BOUND')","p"+i,"i"+i);
                f.jdbc.update("INSERT INTO biz_daikin_directory VALUES (?,'source','site',?,?,?,'INDOOR',0)","p"+i,controller,unit,vendor);
                var node=f.gateway.unit.deepCopy().put("id",vendor).put("unitId",unit);
                units.put(id,node);
            }
            var gateway=new DaikinControlGateway() {
                public boolean supports(String source) { return "source".equals(source); }
                public Snapshot read(DaikinControlAccess.Target target) {
                    reads.incrementAndGet();
                    onRead.run();
                    if(readFailure.contains(target.equipmentId())) throw new IllegalStateException("模拟状态读取失败");
                    return new Snapshot(f.gateway.controller.deepCopy().put("lcNo",target.controllerId()),units.get(target.equipmentId()).deepCopy());
                }
                public Reply send(DaikinControlAccess.Target target,Action action,String value) {
                    sends.incrementAndGet(); onSend.run();
                    String id=target.equipmentId();
                    if(reject.contains(id)) return new Reply("10001");
                    if(!noApply.contains(id)) {
                        var node=units.get(id);
                        if(action==Action.TEMPERATURE) node.put(action.observedField,Integer.parseInt(value)); else node.put(action.observedField,value);
                        if(action==Action.ON_OFF && !delayedRun.contains(id)) node.put("unitStatus","on".equals(value)?"operating":"stopped");
                    }
                    if(unknown.contains(id)) throw new IllegalStateException("模拟丢失受理响应");
                    return new Reply("10000");
                }
            };
            controls=new DaikinControlService(f.props,f.access,f.policy,f.store,List.of(factory==null?gateway:factory.apply(this)),f.clock);
            AuditEvidenceWriter writer=new JdbcSecurityAuditEvidenceWriter(f.jdbc,new AuditSummarySanitizer());
            store=new DaikinBatchStore(f.jdbc,new TransactionTemplate(new DataSourceTransactionManager(f.jdbc.getDataSource())),f.json,f.store,
                    e->{if(f.auditFails) throw new IllegalStateException("模拟审计事务失败");writer.append(e);},new AuditGovernanceProperties());
            service=new DaikinBatchService(store,controls,f.access,f.policy,f.props,f.jdbc,f.clock);
        }
        String device(int n) { return String.format("e%03d",n); }
        List<String> devices(int count) { return java.util.stream.IntStream.range(0,count).mapToObj(this::device).toList(); }
        Preview preview(int count) { return service.preview(7,new PreviewRequest(devices(count))); }
        SubmitBatch request(Preview p,Action action,String value,String key) { return new SubmitBatch(p.previewId(),action,value,key,false); }
        Batch submit(int count,Action action,String value) { return service.submit(7,request(preview(count),action,value,"batch-request-"+UUID.randomUUID()),"offline"); }
        void round() {
            store.synchronize(f.clock);
            for(var w:store.candidates()) {
                var claimed=store.claim(w,f.clock,4);
                if(claimed!=null) service.dispatch(claimed);
            }
            controls.reconcile(); store.synchronize(f.clock); f.clock.advance(2000);
        }
        Batch drain(String id) {
            for(int i=0;i<120;i++) { round(); if(service.get(7,id).completed()) return service.get(7,id); }
            return service.get(7,id);
        }
        DaikinBatchStore anotherStore() {
            var jdbc=new org.springframework.jdbc.core.JdbcTemplate(f.jdbc.getDataSource());
            return new DaikinBatchStore(jdbc,new TransactionTemplate(new DataSourceTransactionManager(f.jdbc.getDataSource())),f.json,f.store,
                    new JdbcSecurityAuditEvidenceWriter(jdbc,new AuditSummarySanitizer()),new AuditGovernanceProperties());
        }
        public void close() { service.stop(); controls.stop(); f.service.stop(); }
    }

    @ParameterizedTest @ValueSource(ints={1,3,15,100})
    void persistedBatchReturnsBeforeAnySendAndCompletesPerDevice(int count) throws Exception {
        try(var t=new Fixture(count,3)) {
            var preview=t.preview(count); int reads=t.reads.get();
            var req=t.request(preview,Action.TEMPERATURE,"26","scale-request-123");
            long started=System.nanoTime(); var batch=t.service.submit(7,req,"offline");
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            assertThat(elapsed).as("模拟%d台提交(ms)",count).isLessThan(2000);
            assertThat(batch.queuedCount()).isEqualTo(count); assertThat(t.sends).hasValue(0); assertThat(t.reads).hasValue(reads);
            assertThat(t.service.submit(7,req,"repeat").batchId()).isEqualTo(batch.batchId());
            var result=t.drain(batch.batchId());
            assertThat(result.completedCount()).isEqualTo(count); assertThat(result.attentionCount()).isZero();
            assertThat(t.sends).hasValue(count);
            assertThat(result.items()).allSatisfy(i->{assertThat(i.observedValue()).isEqualTo("26");assertThat(i.readbackMillis()).isNotNull();assertThat(i.acceptanceMillis()).isNotNull();});
            System.out.printf("BATCH_SIMULATION devices=%d submitMillis=%d sends=%d completed=%d%n",count,elapsed,t.sends.get(),result.completedCount());
        }
    }
    @Test void allFiveActionsUseOnlySupportedValuesAndAlreadyTargetDoesNotSend() throws Exception {
        for(var entry:Map.of(Action.ON_OFF,"on",Action.TEMPERATURE,"26",Action.MODE,"heating",Action.FAN_SPEED,"high",Action.AIR_FLOW,"airFlowTwo").entrySet())
            try(var t=new Fixture(3,1)) {
                if(entry.getKey()==Action.FAN_SPEED) t.units.get(t.device(1)).putArray("fanSpeedSetList").add("low");
                if(entry.getKey()==Action.TEMPERATURE) t.units.get(t.device(1)).with("DefaultSetpointRange").put("max",25);
                var result=t.drain(t.submit(3,entry.getKey(),entry.getValue()).batchId());
                int supported=Set.of(Action.TEMPERATURE,Action.FAN_SPEED).contains(entry.getKey())?2:3;
                assertThat(result.completedCount()).isEqualTo(supported); assertThat(result.attentionCount()).isEqualTo(3-supported);
                assertThat(t.sends).hasValue(supported);
            }
        try(var t=new Fixture(3,1)) {
            var result=t.drain(t.submit(3,Action.TEMPERATURE,"24").batchId());
            assertThat(result.items()).allSatisfy(i->assertThat(i.status()).isEqualTo(ItemStatus.ALREADY_TARGET));
            assertThat(t.sends).hasValue(0);
        }
    }
    @Test void sameControllerWaitsUntilReadbackAndOtherControllersProceed() throws Exception {
        try(var t=new Fixture(3,2)) {
            var batch=t.submit(3,Action.TEMPERATURE,"26");
            var candidates=batch.items().stream().map(i->t.store.work(i.itemId())).toList();
            var first=t.store.claim(candidates.stream().filter(w->w.item().equipmentId().equals(t.device(0))).findFirst().orElseThrow(),t.f.clock,4);
            t.service.dispatch(first);
            assertThat(t.store.claim(candidates.stream().filter(w->w.item().equipmentId().equals(t.device(1))).findFirst().orElseThrow(),t.f.clock,4)).isNull();
            var independent=t.store.claim(candidates.stream().filter(w->w.item().equipmentId().equals(t.device(2))).findFirst().orElseThrow(),t.f.clock,4);
            assertThat(independent).isNotNull(); t.service.dispatch(independent);
            assertThat(t.sends).hasValue(2);
            t.controls.reconcile(); t.store.synchronize(t.f.clock);
            assertThat(t.drain(batch.batchId()).completedCount()).isEqualTo(3);
        }
    }
    @Test void unknownNeverRetriesAndItsControllerQueueIsTerminatedEvenAfterRelease() throws Exception {
        try(var t=new Fixture(3,2)) {
            t.unknown.add(t.device(0)); var batch=t.submit(3,Action.TEMPERATURE,"26");
            var first=batch.items().stream().map(i->t.store.work(i.itemId())).filter(w->w.item().equipmentId().equals(t.device(0))).findFirst().orElseThrow();
            t.service.dispatch(t.store.claim(first,t.f.clock,4)); t.store.synchronize(t.f.clock);
            t.controls.reconcile();t.store.synchronize(t.f.clock);
            var unknownRead=t.service.get(7,batch.batchId()).items().stream().filter(i->i.equipmentId().equals(t.device(0))).findFirst().orElseThrow();
            assertThat(unknownRead.observedValue()).isEqualTo("26");assertThat(unknownRead.status()).isEqualTo(ItemStatus.UNCONFIRMED);
            var result=t.drain(batch.batchId());
            assertThat(result.items().stream().filter(i->i.equipmentId().equals(t.device(1))).findFirst().orElseThrow().status()).isEqualTo(ItemStatus.NOT_SENT);
            assertThat(result.completedCount()).isEqualTo(1); assertThat(t.sends).hasValue(2);
            t.f.jdbc.update("DELETE FROM biz_daikin_control_slot"); t.f.jdbc.update("DELETE FROM biz_daikin_control_controller_slot");
            for(int i=0;i<5;i++) t.round(); assertThat(t.sends).hasValue(2);
            assertThat(t.service.get(7,batch.batchId()).items().stream().filter(i->i.equipmentId().equals(t.device(0))).findFirst().orElseThrow().status()).isEqualTo(ItemStatus.UNCONFIRMED);
        }
    }
    @Test void timeoutDoesNotRestoreSuccessfulDevicesOrResend() throws Exception {
        try(var t=new Fixture(3,1)) {
            t.noApply.add(t.device(1)); t.reject.add(t.device(2));
            var batch=t.submit(3,Action.MODE,"heating"); var result=t.drain(batch.batchId());
            assertThat(result.completedCount()).isEqualTo(1); assertThat(result.attentionCount()).isEqualTo(2); assertThat(t.sends).hasValue(3);
            assertThat(t.units.get(t.device(0)).path("mode").asText()).isEqualTo("heating");
            assertThat(t.units.get(t.device(1)).path("mode").asText()).isEqualTo("cooling");
        }
    }
    @Test void switchFieldReadbackAndRunningStateRemainSeparate() throws Exception {
        try(var t=new Fixture(1,1)) {
            t.delayedRun.add(t.device(0));var batch=t.submit(1,Action.ON_OFF,"on");t.round();
            var waiting=t.service.get(7,batch.batchId()).items().getFirst();
            assertThat(waiting.observedValue()).isEqualTo("on");assertThat(waiting.unitStatus()).isEqualTo("stopped");
            assertThat(waiting.readbackAt()).isNotNull();assertThat(waiting.status()).isEqualTo(ItemStatus.ACTIVE);
            t.units.get(t.device(0)).put("unitStatus","operating");t.round();
            assertThat(t.service.get(7,batch.batchId()).items().getFirst().status()).isEqualTo(ItemStatus.MATCHED);assertThat(t.sends).hasValue(1);
        }
    }
    @Test void oldSingleDeviceUnknownWithoutControllerSlotStopsBatchQueue() throws Exception {
        try(var t=new Fixture(3,2)) {
            t.f.jdbc.update("UPDATE biz_daikin_directory SET controller_id='c0' WHERE pending_id='pending'");
            t.f.gateway.controller.put("lcNo","c0");t.f.gateway.loseResponse=true;
            t.f.service.submit(7,"equipment",new Submit(Action.TEMPERATURE,"26","single-unknown-123",t.f.service.options(7,"equipment").revision()),"offline");
            t.f.jdbc.update("DELETE FROM biz_daikin_control_controller_slot");
            var result=t.drain(t.submit(3,Action.TEMPERATURE,"26").batchId());
            assertThat(result.completedCount()).isEqualTo(1);assertThat(result.items().stream().filter(i->i.status()==ItemStatus.NOT_SENT).count()).isEqualTo(2);
            assertThat(t.f.gateway.writes).hasValue(1);assertThat(t.sends).hasValue(1);
        }
    }
    @Test void releaseBeforeNextScanStopsOldQueueButAllowsExplicitNewBatch() throws Exception {
        try(var t=new Fixture(2,2)) {
            var old=t.submit(2,Action.TEMPERATURE,"26");
            t.f.jdbc.update("UPDATE biz_daikin_directory SET controller_id='c0' WHERE pending_id='pending'");
            t.f.gateway.controller.put("lcNo","c0");t.f.gateway.loseResponse=true;t.f.clock.advance(1);
            var uncertain=t.f.service.submit(7,"equipment",new Submit(Action.TEMPERATURE,"26","single-race-unknown",t.f.service.options(7,"equipment").revision()),"offline");
            var recovery=new DaikinControlRecoveryService(t.f.access,t.f.store,t.f.policy,List.of(new DaikinControlGrantReader() {
                public boolean supports(String source) { return true; }
                public DaikinControlGateway.Snapshot read(DaikinControlAccess.Target target) { return t.f.gateway.read(target); }
            }),t.f.clock);
            t.f.clock.advance(1);recovery.continueCurrent(7,uncertain.operationId(),new Close("按当前状态继续新的测试"),"offline");
            t.f.clock.advance(1);var fresh=t.submit(1,Action.TEMPERATURE,"27");
            assertThat(t.drain(old.batchId()).items().stream().filter(i->i.equipmentId().equals(t.device(1))).findFirst().orElseThrow().status()).isEqualTo(ItemStatus.NOT_SENT);
            assertThat(t.drain(fresh.batchId()).completedCount()).isEqualTo(1);
            assertThat(t.sends).hasValue(1);assertThat(t.f.gateway.writes).hasValue(1);
        }
    }
    @Test void cancelAndReplacementOnlyChangeQueuedItemsAndRetainHistory() throws Exception {
        try(var t=new Fixture(3,1)) {
            var old=t.submit(3,Action.TEMPERATURE,"26");
            var work=t.store.claim(t.store.candidates().getFirst(),t.f.clock,4); t.service.dispatch(work);
            var newer=t.submit(3,Action.TEMPERATURE,"27");
            assertThat(t.service.get(7,old.batchId()).items().stream().filter(i->i.status()==ItemStatus.SUPERSEDED).count()).isEqualTo(2);
            var cancelled=t.service.cancel(7,newer.batchId());
            assertThat(cancelled.items()).allSatisfy(i->assertThat(i.status()).isEqualTo(ItemStatus.CANCELLED));
            assertThat(t.drain(old.batchId()).completedCount()).isEqualTo(1); assertThat(t.sends).hasValue(1);
            assertThat(t.service.history(7,1,10).total()).isEqualTo(2);
        }
    }
    @Test void batchTransactionAndAuditRollbackAlsoRestoreSupersededQueue() throws Exception {
        try(var t=new Fixture(3,1)) {
            var old=t.submit(3,Action.TEMPERATURE,"26"); var preview=t.preview(3); t.f.auditFails=true;
            assertThatThrownBy(()->t.service.submit(7,t.request(preview,Action.TEMPERATURE,"27","rollback-request"),"offline")).isInstanceOf(IllegalStateException.class);
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_batch",Integer.class)).isEqualTo(1);
            assertThat(t.service.get(7,old.batchId()).queuedCount()).isEqualTo(3); assertThat(t.sends).hasValue(0);
        }
    }
    @Test void multiInstanceClaimAndExpiredWorkerCannotSendAgain() throws Exception {
        try(var t=new Fixture(1,1);var pool=Executors.newFixedThreadPool(2)) {
            var batch=t.submit(1,Action.TEMPERATURE,"26");var work=t.store.candidates().getFirst();
            var second=t.anotherStore();
            var start=new CountDownLatch(1);
            Callable<DaikinBatchStore.Work> claim=()->{start.await();return t.store.claim(work,t.f.clock,4);};
            var a=pool.submit(claim);var b=pool.submit(()->{start.await();return second.claim(work,t.f.clock,4);});start.countDown();
            var claims=Arrays.asList(a.get(),b.get());assertThat(claims.stream().filter(Objects::nonNull).count()).isEqualTo(1);
            var claimed=claims.stream().filter(Objects::nonNull).findFirst().orElseThrow();
            t.f.clock.advance(120_001); second.synchronize(t.f.clock); t.service.dispatch(claimed);
            assertThat(t.sends).hasValue(0);assertThat(t.service.get(7,batch.batchId()).items().getFirst().status()).isEqualTo(ItemStatus.NOT_SENT);
        }
    }
    @Test void concurrentDuplicateSubmissionCreatesOneBatchAndGlobalLimitIsFourControllers() throws Exception {
        try(var t=new Fixture(15,1);var pool=Executors.newFixedThreadPool(2)) {
            var secondStore=t.anotherStore();
            var secondService=new DaikinBatchService(secondStore,t.controls,t.f.access,t.f.policy,t.f.props,t.f.jdbc,t.f.clock);
            var request=t.request(t.preview(15),Action.TEMPERATURE,"26","concurrent-request");
            var start=new CountDownLatch(1);
            Callable<Batch> submit=()->{start.await();return t.service.submit(7,request,"offline");};
            var a=pool.submit(submit);var b=pool.submit(()->{start.await();return secondService.submit(7,request,"offline");});start.countDown();
            assertThat(a.get().batchId()).isEqualTo(b.get().batchId());
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_batch",Integer.class)).isEqualTo(1);
            int claimed=0;int index=0;for(var w:t.store.candidates()) if((index++%2==0?t.store:secondStore).claim(w,t.f.clock,4)!=null) claimed++;
            assertThat(claimed).isEqualTo(4);
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_batch_lane",Integer.class)).isEqualTo(4);
            assertThat(t.sends).hasValue(0);
            secondService.stop();
        }
    }
    @Test void cancelRaceCannotCancelAnAlreadyClaimedDevice() throws Exception {
        try(var t=new Fixture(1,1);var pool=Executors.newFixedThreadPool(2)) {
            var batch=t.submit(1,Action.TEMPERATURE,"26");var candidate=t.store.candidates().getFirst();var start=new CountDownLatch(1);
            var claim=pool.submit(()->{start.await();return t.store.claim(candidate,t.f.clock,4);});
            var cancel=pool.submit(()->{start.await();return t.service.cancel(7,batch.batchId());});start.countDown();
            var work=claim.get();cancel.get();
            if(work!=null) t.service.dispatch(work);
            var result=t.drain(batch.batchId());
            if(work==null) { assertThat(result.items().getFirst().status()).isEqualTo(ItemStatus.CANCELLED);assertThat(t.sends).hasValue(0); }
            else { assertThat(result.items().getFirst().status()).isEqualTo(ItemStatus.MATCHED);assertThat(t.sends).hasValue(1); }
        }
    }
    @Test void crashAfterSendRecoversExistingOperationInsteadOfReplaying() throws Exception {
        try(var t=new Fixture(1,1)) {
            var batch=t.submit(1,Action.TEMPERATURE,"26");var w=t.store.claim(t.store.candidates().getFirst(),t.f.clock,4);
            t.controls.submitBatch(w.user(),w.item().equipmentId(),new Submit(w.action(),w.value(),w.operationKey(),w.revision()),w.trace(),w.relationshipHash(),()->t.store.requireDispatch(w,t.f.clock));
            // 模拟进程在单台持久化完成、批次关联写入之前退出。
            t.f.clock.advance(120_001);t.store.synchronize(t.f.clock);t.controls.reconcile();t.store.synchronize(t.f.clock);
            t.service.dispatch(w);assertThat(t.sends).hasValue(1);assertThat(t.service.get(7,batch.batchId()).completed()).isTrue();
        }
    }
    @Test void crashBeforeSendOrAfterSendingIntentNeverReplays() throws Exception {
        for(boolean sendingIntent:List.of(false,true)) try(var t=new Fixture(1,1)) {
            var batch=t.submit(1,Action.TEMPERATURE,"26");var w=t.store.claim(t.store.candidates().getFirst(),t.f.clock,4);
            var reserved=t.f.store.reserve(w.user(),w.target(),new Submit(w.action(),w.value(),w.operationKey(),w.revision()),w.operationHash(),w.trace(),t.f.clock.millis(),true,()->t.store.requireDispatch(w,t.f.clock));
            if(sendingIntent) t.f.store.markSending(reserved.row().view().operationId(),"24","{\"_executionPolicy\":\"TEST_FAST_V1\"}",w.user(),t.f.clock.millis(),60_000,()->t.store.requireDispatch(w,t.f.clock));
            t.f.clock.advance(120_001);t.store.synchronize(t.f.clock);t.controls.reconcile();t.store.synchronize(t.f.clock);t.service.dispatch(w);
            assertThat(t.sends).hasValue(0);
            assertThat(t.service.get(7,batch.batchId()).items().getFirst().status()).isEqualTo(sendingIntent?ItemStatus.UNCONFIRMED:ItemStatus.NOT_SENT);
        }
    }
    @Test void stalePreviewPermissionsAndIdentityPreventSend() throws Exception {
        try(var t=new Fixture(1,1)) {
            var p=t.preview(1);t.f.clock.advance(120_001);
            assertThatThrownBy(()->t.service.submit(7,t.request(p,Action.ON_OFF,"on","stale-preview-123"),"offline")).isInstanceOf(BusinessException.class);
            var batch=t.submit(1,Action.ON_OFF,"on");t.f.jdbc.update("UPDATE sys_user SET status=0 WHERE id=7");
            t.round();assertThat(t.sends).hasValue(0);
            assertThatThrownBy(()->t.service.get(8,batch.batchId())).isInstanceOf(BusinessException.class);
        }
    }
    @Test void revocationDuringPreviewCannotReturnOrPersistEarlierCapabilities() throws Exception {
        try(var t=new Fixture(3,1)) {
            t.onRead=()->t.f.jdbc.update("UPDATE sys_user SET status=0 WHERE id=7");
            assertThatThrownBy(()->t.preview(3)).isInstanceOf(BusinessException.class);
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_preview",Integer.class)).isZero();
            assertThat(t.sends).hasValue(0);
        }
    }
    @Test void unknownLinkedScopeOnlySkipsThatDeviceAndUnsupportedModeDoesNotDemandConfirmation() throws Exception {
        try(var t=new Fixture(3,1)) {
            t.units.get(t.device(1)).remove("masterSlaveIds");
            var result=t.drain(t.submit(3,Action.ON_OFF,"on").batchId());
            assertThat(result.completedCount()).isEqualTo(2);assertThat(result.attentionCount()).isEqualTo(1);assertThat(t.sends).hasValue(2);
            assertThat(result.items().stream().filter(i->i.equipmentId().equals(t.device(1))).findFirst().orElseThrow().status()).isEqualTo(ItemStatus.UNSUPPORTED);
        }
        try(var t=new Fixture(2,1)) {
            t.f.jdbc.update("UPDATE biz_daikin_directory SET controller_id='c0' WHERE equipment_id='v1'");
            t.units.get(t.device(0)).putArray("masterSlaveIds").add("v1");t.units.get(t.device(0)).putArray("modeSetList").add("cooling");
            var result=t.drain(t.submit(1,Action.MODE,"heating").batchId());
            assertThat(result.items().getFirst().status()).isEqualTo(ItemStatus.UNSUPPORTED);assertThat(t.sends).hasValue(0);
        }
    }
    @Test void linkedModeRequiresSingleConfirmationAndUnknownOrChangedScopeDoesNotSend() throws Exception {
        try(var t=new Fixture(3,1)) {
            // 关联设备必须在同控制器且已绑定可授权。
            t.f.jdbc.update("UPDATE biz_daikin_directory SET controller_id='c0' WHERE equipment_id='v1'");
            t.units.get(t.device(0)).putArray("masterSlaveIds").add("v1");
            var p=t.preview(1);assertThat(p.items().getFirst().impactedEquipmentIds()).containsExactly(t.device(1));
            assertThat(p.items().getFirst().impactedEquipmentLabels()).containsEntry(t.device(1),"模拟空调1");
            assertThatThrownBy(()->t.service.submit(7,t.request(p,Action.MODE,"heating","linked-request-1"),"offline")).isInstanceOf(BusinessException.class);
            assertThat(p.items().getFirst().independentOnOff()).isTrue();
            var batch=t.service.submit(7,new SubmitBatch(p.previewId(),Action.MODE,"heating","linked-request-2",true),"offline");
            t.units.get(t.device(0)).putArray("masterSlaveIds").add("unknown-unit");
            assertThat(t.drain(batch.batchId()).items().getFirst().status()).isEqualTo(ItemStatus.NOT_SENT);assertThat(t.sends).hasValue(0);
            var previewUnknown=t.preview(1);
            assertThat(previewUnknown.items().getFirst().actions().stream().filter(a->a.action()==Action.MODE).findFirst().orElseThrow().allowed()).isFalse();
        }
    }
}
