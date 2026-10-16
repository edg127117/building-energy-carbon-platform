package com.platform.iot.daikin.control;

import com.platform.iot.daikin.client.*;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.platform.iot.daikin.control.DaikinBatchContracts.*;
import static com.platform.iot.daikin.control.DaikinControlContracts.*;
import static org.assertj.core.api.Assertions.*;

/** H2 和本机 HTTPS 厂家替身；覆盖发送窗口、持续回读、真实套接字并发及异常隔离。 */
class DaikinBatchParallelTest {
    private DaikinBatchStore.Work work(DaikinBatchControlTest.Fixture t,Batch batch,int i) {
        return t.store.work(batch.items().stream().filter(item->item.equipmentId().equals(t.device(i))).findFirst().orElseThrow().itemId());
    }
    private void send(DaikinBatchControlTest.Fixture t,Batch batch,int i) {
        var claimed=t.store.claim(work(t,batch,i),t.f.clock,4); assertThat(claimed).isNotNull(); t.service.dispatch(claimed);
    }
    private void await(java.util.function.BooleanSupplier ready,Runnable progress) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(50);
        while(!ready.getAsBoolean() && System.nanoTime()<end) { progress.run(); Thread.sleep(10); }
        assertThat(ready.getAsBoolean()).isTrue();
    }

    @ParameterizedTest @ValueSource(ints={3,15,100})
    void everyDeviceCanWaitForReadbackWithoutHoldingASendLane(int count) throws Exception {
        try(var t=new DaikinBatchControlTest.Fixture(count,count)) {
            t.delayedRun.addAll(t.devices(count)); var batch=t.submit(count,Action.ON_OFF,"on");
            for(int i=0;i<count;i++) send(t,batch,i);
            assertThat(t.sends).hasValue(count);
            assertThat(t.service.get(7,batch.batchId()).activeCount()).isEqualTo(count);
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_batch_lane",Integer.class)).isZero();
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot",Integer.class)).isEqualTo(count);
            t.units.values().forEach(u->u.put("unitStatus","operating"));
            assertThat(t.drain(batch.batchId()).completedCount()).isEqualTo(count);
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot",Integer.class)).isZero();
            assertThat(t.f.jdbc.queryForList("SELECT equipment_id FROM biz_daikin_control_operation",String.class)).doesNotHaveDuplicates().hasSize(count);
            System.out.printf("PARALLEL_SCALE devices=%d sends=%d completed=%d%n",count,t.sends.get(),count);
        }
    }

    @Test void twoStoresCanClaimThreeSameLcItemsBeforeAnyAcceptanceAndRespectSendBudget() throws Exception {
        try(var t=new DaikinBatchControlTest.Fixture(5,5);var pool=Executors.newFixedThreadPool(2)) {
            var batch=t.submit(5,Action.ON_OFF,"on");var other=t.anotherStore();var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return t.store.claim(work(t,batch,0),t.f.clock,3);});
            var b=pool.submit(()->{start.await();return other.claim(work(t,batch,1),t.f.clock,3);});start.countDown();
            assertThat(a.get()).isNotNull();assertThat(b.get()).isNotNull();
            assertThat(t.store.claim(work(t,batch,2),t.f.clock,3)).isNotNull();
            assertThat(other.claim(work(t,batch,3),t.f.clock,3)).isNull();
            assertThat(t.sends).hasValue(0);
            t.f.clock.advance(120_001);t.store.synchronize(t.f.clock);
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_batch_lane",Integer.class)).isZero();
        }
    }

    @ParameterizedTest @ValueSource(strings={"10003","10008","UNKNOWN"})
    void failureStopsUnsentButDoesNotWithdrawAlreadySentPeersOrReplay(String failure) throws Exception {
        AtomicReference<String> code=new AtomicReference<>("10000");
        try(var t=new DaikinBatchControlTest.Fixture(5,5,fixture->new DaikinControlGateway() {
            public boolean supports(String source) { return true; }
            public Snapshot read(DaikinControlAccess.Target target) { return new Snapshot(fixture.f.gateway.controller.deepCopy().put("lcNo","c0"),fixture.units.get(target.equipmentId()).deepCopy()); }
            public Reply send(DaikinControlAccess.Target target,Action action,String value) {
                fixture.sends.incrementAndGet();if(code.get().equals("UNKNOWN")) throw new IllegalStateException("响应丢失");
                return new Reply(code.get());
            }
        })) {
            var batch=t.submit(5,Action.ON_OFF,"on");send(t,batch,0);send(t,batch,1);
            var claimed=t.store.claim(work(t,batch,3),t.f.clock,4);assertThat(claimed).isNotNull();
            code.set(failure);send(t,batch,2);t.service.dispatch(claimed);t.store.synchronize(t.f.clock);
            assertThat(t.sends).hasValue(3);
            assertThat(t.store.claim(work(t,batch,4),t.f.clock,4)).isNull();
            assertThat(work(t,batch,3).item().status()).isEqualTo(ItemStatus.NOT_SENT);
            t.units.values().forEach(u->u.put("onOff","on").put("unitStatus","operating"));
            for(int i=0;i<6;i++) t.round();
            assertThat(work(t,batch,0).item().status()).isEqualTo(ItemStatus.MATCHED);
            assertThat(work(t,batch,1).item().status()).isEqualTo(ItemStatus.MATCHED);
            assertThat(work(t,batch,4).item().status()).isEqualTo(ItemStatus.NOT_SENT);
            assertThat(t.sends).hasValue(3);
        }
    }

    @Test void threeUnconfirmedPeersCanBeRecoveredIndividuallyWithoutRevivingOldQueue() throws Exception {
        try(var t=new DaikinBatchControlTest.Fixture(4,4)) {
            t.noApply.addAll(t.devices(4));var batch=t.submit(4,Action.ON_OFF,"on");
            for(int i=0;i<3;i++) send(t,batch,i);
            t.f.clock.advance(60_001);t.controls.reconcile();t.store.synchronize(t.f.clock);
            var reader=new DaikinControlGrantReader() {
                public boolean supports(String source) { return true; }
                public DaikinControlGateway.Snapshot read(DaikinControlAccess.Target target) {
                    return new DaikinControlGateway.Snapshot(t.f.gateway.controller.deepCopy().put("lcNo","c0"),t.units.get(target.equipmentId()).deepCopy());
                }
            };
            var recovery=new DaikinControlRecoveryService(t.f.access,t.f.store,t.f.policy,List.of(reader),t.f.clock);
            for(int i=0;i<3;i++) recovery.continueCurrent(7,work(t,batch,i).item().operationId(),new Close("确认当前模拟状态后继续"),"offline");
            for(int i=0;i<3;i++) t.round();assertThat(t.sends).hasValue(3);
            assertThat(work(t,batch,3).item().status()).isEqualTo(ItemStatus.NOT_SENT);
            assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot",Integer.class)).isZero();
        }
    }

    @Test void blockedReadbackDoesNotBlockSendingTheRemainingSameLcDevices() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
        try(var t=new DaikinBatchControlTest.Fixture(6,6)) {
            var batch=t.submit(6,Action.ON_OFF,"on");send(t,batch,0);
            t.onRead=()->{
                if(once.compareAndSet(false,true)) {
                    entered.countDown();
                    try { if(!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("回读等待超时"); }
                    catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new RuntimeException(failure); }
                }
            };
            try {
                t.controls.scheduledReconcile();assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
                await(()->t.sends.get()==6,t.service::tick);
                assertThat(release.getCount()).isEqualTo(1);
            } finally { release.countDown(); }
            await(()->t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_operation WHERE lease_until>0",Integer.class)==0,()->{});
            assertThat(t.drain(batch.batchId()).completedCount()).isEqualTo(6);
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(ints={3,15,100})
    void actualHttpsRequestsReachManufacturerWhileFirstResponseIsBlocked(int count) throws Exception {
        var fixture=new AtomicReference<DaikinBatchControlTest.Fixture>();
        var release=new CountDownLatch(1);var arrivals=new CopyOnWriteArrayList<String>();
        var initialWindow=new CountDownLatch(Math.min(count,4));
        var responseSent=new AtomicBoolean();var beforeFirstResponse=new AtomicInteger();
        var inFlight=new AtomicInteger();var maxInFlight=new AtomicInteger();var tokens=new AtomicInteger();
        try(var server=new DaikinLoopbackHttps(exchange->{
            try {
                String path=exchange.getRequestURI().getPath();String body;
                if(path.equals("/token")) { tokens.incrementAndGet(); body="{\"code\":\"10000\",\"data\":{\"token\":\"test-token\",\"refreshKey\":\"refresh\",\"expireAt\":\"2099-01-01T00:00:00Z\"}}"; }
                else if(exchange.getRequestMethod().equals("GET")) {
                    String vendor=path.substring(path.lastIndexOf('/')+1);var t=fixture.get();
                    var unit=t.units.get(t.device(Integer.parseInt(vendor.substring(1))));
                    var envelope=t.f.json.createObjectNode().put("code","10000");
                    var controller=envelope.putObject("data").putArray("sites").addObject().put("siteId","site")
                            .putArray("controlers").addObject();
                    controller.setAll(t.f.gateway.controller.deepCopy().put("lcNo","c0"));controller.putArray("units").add(unit.deepCopy());
                    body=envelope.toString();
                } else {
                    int running=inFlight.incrementAndGet();maxInFlight.accumulateAndGet(running,Math::max);
                    String vendor=path.substring(path.lastIndexOf('/')+1);
                    if(!responseSent.get()) beforeFirstResponse.incrementAndGet();
                    arrivals.add(vendor);
                    initialWindow.countDown();
                    // Fill the entire configured window before ANY acceptance response, proving the HTTP path has no two-device cap.
                    if(!initialWindow.await(10,TimeUnit.SECONDS)) throw new AssertionError("发送窗口依赖前台受理或仍有两台限制");
                    // Only the first response is held. Other send slots must continue filling without acceptance from it.
                    if(arrivals.getFirst().equals(vendor) && !release.await(55,TimeUnit.SECONDS)) throw new AssertionError("首响应等待超时");
                    body="{\"code\":\"10000\"}";
                    if(arrivals.getFirst().equals(vendor)) responseSent.set(true);
                    respond(exchange,body);inFlight.decrementAndGet();return;
                }
                respond(exchange,body);
            } catch(Exception failure) { exchange.close(); throw new RuntimeException(failure); }
        })) {
            try(var t=new DaikinBatchControlTest.Fixture(count,count,f->{
                fixture.set(f);
                var config=new DaikinClientConfiguration(true,true,server.uri,
                        new DaikinClientConfiguration.Credentials("test-app","test-password","test-salt","0123456789abcdef"),
                        Duration.ofSeconds(2),Duration.ofSeconds(60),Duration.ofSeconds(10),Duration.ofSeconds(1),1_000_000,4,1000);
                return new DaikinControlClient("source",new DaikinSession(config,new DaikinControlWireCodec(f.f.json),server.transport,f.f.json,f.f.clock));
            })) {
                var batch=t.submit(count,Action.ON_OFF,"on");
                try {
                    try { await(()->arrivals.size()==count,t.service::tick); }
                    catch(AssertionError failed) { throw new AssertionError("arrivals="+arrivals+";items="+t.service.get(7,batch.batchId()).items()+";ops="+t.f.jdbc.queryForList("SELECT equipment_id,status,reason_code FROM biz_daikin_control_operation"),failed); }
                    assertThat(responseSent).isFalse();
                    assertThat(beforeFirstResponse).hasValue(count);
                    assertThat(arrivals).doesNotHaveDuplicates().hasSize(count);
                    assertThat(maxInFlight.get()).isEqualTo(Math.min(count,4));
                    assertThat(t.f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_event WHERE status='ACCEPTED'",Integer.class)).isLessThan(count);
                    assertThat(tokens).hasValue(1);
                    System.out.printf("HTTPS_PARALLEL_PROOF devices=%d arrivalsBeforeFirstResponse=%d maxPutInFlight=%d%n",count,beforeFirstResponse.get(),maxInFlight.get());
                } finally { release.countDown(); }
                await(()->t.service.get(7,batch.batchId()).items().stream().allMatch(i->i.status()==ItemStatus.ACTIVE),t.service::tick);
                // Finish real readback through the same HTTPS/codec/identity path without sending another PUT.
                t.units.values().forEach(u->u.put("onOff","on").put("unitStatus","operating"));
                assertThat(t.drain(batch.batchId()).completedCount()).isEqualTo(count);
                assertThat(arrivals).hasSize(count);
            }
        } finally { release.countDown(); }
    }

    private static void respond(HttpExchange exchange,String body) throws java.io.IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);
        try(var output=exchange.getResponseBody()) { output.write(bytes); }
    }
}
