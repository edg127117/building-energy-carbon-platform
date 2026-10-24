package com.platform.iot.reliability;

import com.platform.energy.period.EnergyPeriodResultRepository;
import com.platform.iot.ingest.v2.TelemetryV2IngestionService;
import com.platform.iot.temporal.impl.TdengineHvacRawEventRepository;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 内存隔离验证派生待办合并、总额、失败保留和事务回滚，不访问真实引擎。 */
class TelemetryDerivedRecoveryTest {
    TelemetryConflictTest f;TdengineHvacRawEventRepository raw;TelemetryConflictRecovery recovery;
    long time=System.currentTimeMillis()-120000;
    @BeforeEach void setup(){
        f=new TelemetryConflictTest();f.setup();raw=mock(TdengineHvacRawEventRepository.class);
        recovery=new TelemetryConflictRecovery(f.jdbc,f.ledger,raw,mock(TelemetryMessageJournal.class),mock(TelemetryV2IngestionService.class),
                new StaticListableBeanFactory().getBeanProvider(com.platform.iot.dataquality.LateRealMinuteCorrectionService.class),mock(EnergyPeriodResultRepository.class),new SimpleMeterRegistry(),f.codec,7);
    }
    void queue(String point,long at){TelemetryConflictService.enqueue(f.jdbc,point,"B1",at,"confirmed",1);}
    long budget(){return f.jdbc.queryForObject("SELECT used_rows FROM biz_telemetry_conflict_budget WHERE budget_key='PENDING_DERIVED'",Long.class);}
    String task(){return f.jdbc.queryForObject("SELECT task_id FROM biz_telemetry_change_task",String.class);}
    @Test void repeatedChangesCoalesceAndFailedWorkStillConsumesOneSlot(){
        queue("P1",time);f.jdbc.update("UPDATE biz_telemetry_change_task SET state='FAILED',retry_count=10");
        for(int i=0;i<20;i++)queue("P1",time);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_change_task",Long.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT state FROM biz_telemetry_change_task",String.class)).isEqualTo("FAILED");
        assertThat(budget()).isEqualTo(1);
        assertThatThrownBy(()->queue("P2",time)).hasMessageContaining("capacity");
    }
    @Test void fullDerivedQueueRollsBackOrdinarySelectionInsteadOfLosingCompensation(){
        f.conflict();queue("P2",time);ReflectionTestUtils.setField(f.conflicts,"maxDerived",1);
        var view=f.conflicts.detail(7,List.of("BUILDING_OWNER"),"P1",f.event(0).eventTime());
        assertThatThrownBy(()->f.transaction(()->f.conflicts.resolve(7,List.of("BUILDING_OWNER"),"P1",view.eventTime(),
                new TelemetryConflictService.ResolveRequest(view.variants().getFirst().evidenceId(),view.revision(),"核实","工单")))).hasMessageContaining("capacity");
        assertThat(f.ledger.effective(List.of(f.event(100))).getFirst().value()).isEqualTo(100);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_resolution",Long.class)).isZero();assertThat(budget()).isEqualTo(1);
    }
    @Test void completedWorkReleasesCapacityAndCanBeRequeuedBeforeCleanup(){
        queue("P1",time);
        var e=f.event(120);
        when(raw.findWindow(time,time+1,true)).thenReturn(List.of(new RawTelemetryEvent(e.pointId(),e.pointCode(),e.sourceSystem(),e.sourcePointCode(),e.sourceDeviceId(),e.buildingId(),e.systemGroupId(),e.equipId(),e.equipCode(),e.familyCode(),e.componentCode(),e.suffixCode(),e.value(),time,time+1,e.dataQuality(),0,false)));
        when(raw.correctionInfluence("P1",time)).thenReturn(new long[]{time,time+60000});
        recovery.apply(task());assertThat(budget()).isZero();
        queue("P1",time);assertThat(budget()).isEqualTo(1);
        recovery.expire(System.currentTimeMillis());assertThat(budget()).isEqualTo(1);
        recovery.apply(task());assertThat(budget()).isZero();
        recovery.expire(System.currentTimeMillis());assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_change_task",Long.class)).isZero();
    }
    @Test void failedDerivedTaskRequiresAuthorizedRetryWithoutFreeingCapacity(){
        queue("P1",time);when(raw.findWindow(time,time+1,true)).thenThrow(new IllegalStateException("downstream unavailable"));
        for(int i=0;i<10;i++){f.jdbc.update("UPDATE biz_telemetry_change_task SET next_retry_ms=0");recovery.recover();}
        var retry=new TelemetryConflictService.RetryRequest("DERIVED",task());
        long revision=f.jdbc.queryForObject("SELECT request_revision FROM biz_telemetry_change_task",Long.class);
        assertThat(f.conflicts.failed(7,List.of("BUILDING_OWNER"),"B1")).extracting(TelemetryConflictService.RecoveryView::kind).containsExactly("DERIVED");
        assertThatThrownBy(()->f.transaction(()->{f.conflicts.retry(8,List.of("BUILDING_OWNER"),retry);return null;})).isInstanceOf(com.platform.framework.exception.BusinessException.class);
        f.transaction(()->{f.conflicts.retry(7,List.of("BUILDING_OWNER"),retry);return null;});assertThat(budget()).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT request_revision FROM biz_telemetry_change_task",Long.class)).isEqualTo(revision+1);
    }
    @Test void concurrentEnqueueForSameFactClaimsOnlyOneBudgetSlot() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->queue("P1",time));var b=pool.submit(()->queue("P1",time));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
        }
        assertThat(budget()).isEqualTo(1);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_change_task",Long.class)).isEqualTo(1);
    }
}
