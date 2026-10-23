package com.platform.iot.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.*;
import com.platform.energy.aggregation.*;
import com.platform.energy.aggregation.api.EnergyAggregationContracts.*;
import com.platform.energy.period.EnergyPeriodResultRepository;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.temporal.impl.TdengineHvacRawEventRepository;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import com.platform.system.service.BuildingScopeService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** H2 隔离契约：仲裁、容量、权限、审计事务和更正取值，不代表真实引擎验收。 */
class TelemetryConflictTest {
    JdbcTemplate jdbc;RawTelemetryLedger ledger;RawTelemetryCodec codec;
    TelemetryConflictService conflicts;TransactionTemplate tx;
    BuildingScopeService scope;AuditEvidenceWriter audit;EnergyAggregationGovernanceService energy;

    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:d01_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema-test.sql"),new ClassPathResource("telemetry-conflict-schema-test.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);codec=new RawTelemetryCodec(new ObjectMapper());ledger=new RawTelemetryLedger(jdbc,codec,16,50000);
        scope=mock(BuildingScopeService.class);audit=mock(AuditEvidenceWriter.class);energy=mock(EnergyAggregationGovernanceService.class);
        var auth=new EnergyAggregationAuthorization(mock(BackendDutyService.class),scope);
        conflicts=new TelemetryConflictService(jdbc,ledger,codec,auth,energy,scope,audit,new AuditGovernanceProperties());
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.update("INSERT INTO biz_telemetry_conflict_owner VALUES ('B1',7,1,0)");
    }
    RawTelemetryEvent event(double value){return new RawTelemetryEvent("P1","TEMP","MQTT_STANDARD_V1","MAC:A:TEMP","DEV1","B1",null,"E1","DEV1","TEMP",null,"TEMP",value,1800000000000L,1800000001000L,0,0,false);}
    <T>T transaction(Supplier<T> action){return tx.execute(status->action.get());}
    void conflict(){var e=event(100);ledger.reserve(e,()->null);ledger.storeFrozen(e,()->null);ledger.reserve(event(120),()->null);}

    @Test void crossInstanceUniqueIntentFreezesOneOfConflictingValues()throws Exception {
        var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);
        try {
            List<Future<RawTelemetryLedger.Reservation>> jobs=new ArrayList<>();
            for(int n=0;n<8;n++){final int i=n;jobs.add(pool.submit(()->{start.await();return new RawTelemetryLedger(jdbc,codec,16,50000).reserve(event(i%2==0?100:120),()->null);}));}
            start.countDown();var results=new ArrayList<RawTelemetryLedger.Reservation>();
            for(var job:jobs)results.add(job.get(20,TimeUnit.SECONDS));
            assertThat(results.stream().map(r->r.original().value()).distinct()).hasSize(1);
            assertThat(results.stream().filter(RawTelemetryLedger.Reservation::conflict).count()).isEqualTo(4);
            var fact=ledger.find("P1",event(0).eventTime());assertThat(ledger.evidence(fact)).hasSize(1);
            assertThat(ledger.evidence(fact).getFirst().attempts()).isEqualTo(4);
        }finally{pool.shutdownNow();}
    }
    @Test void oldHistoricalRecordIsAdoptedBeforeComparingNewArrival(){
        var reservation=ledger.reserve(event(120),()->event(100));
        assertThat(reservation.conflict()).isTrue();assertThat(reservation.original().value()).isEqualTo(100);
        assertThat(ledger.pending(50,Long.MAX_VALUE)).containsExactly(event(100));
    }
    @Test void retriesIgnoreArrivalTimeButNeverIdentityOrQuality(){
        var original=event(1.12345678901234);ledger.reserve(original,()->null);
        var retry=new RawTelemetryEvent(original.pointId(),original.pointCode(),original.sourceSystem(),original.sourcePointCode(),original.sourceDeviceId(),original.buildingId(),null,original.equipId(),original.equipCode(),original.familyCode(),null,original.suffixCode(),original.value(),original.eventTime(),original.receivedTime()+999,0,0,true);
        assertThat(ledger.reserve(retry,()->null).conflict()).isFalse();
        assertThat(ledger.reserve(event(1.12345678901235),()->null).conflict()).isTrue();
        assertThat(ledger.find("P1",original.eventTime()).snapshotJson()).isEqualTo(codec.encode(original));
    }
    @Test void ordinaryResolutionSwitchesEffectiveValueAndKeepsOriginalEvidence(){
        conflict();var first=conflicts.detail(7,List.of("BUILDING_OWNER"),"P1",event(0).eventTime());
        var result=transaction(()->conflicts.resolve(7,List.of("BUILDING_OWNER"),"P1",first.eventTime(),new TelemetryConflictService.ResolveRequest(first.variants().getFirst().evidenceId(),first.revision(),"设备报文核实","工单1")));
        assertThat(result.state()).isEqualTo("RESOLVED");assertThat(result.effectiveValue()).isEqualTo(120);
        assertThat(result.original().value()).isEqualTo(100);assertThat(ledger.effective(List.of(event(100))).getFirst().value()).isEqualTo(120);
        assertThat(ledger.find("P1",first.eventTime()).snapshotJson()).isEqualTo(codec.encode(event(100)));
        verify(audit).append(any());
        assertThatThrownBy(()->transaction(()->conflicts.resolve(7,List.of("BUILDING_OWNER"),"P1",first.eventTime(),new TelemetryConflictService.ResolveRequest(null,first.revision(),"旧页面","工单"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("冲突已变化");
    }
    @Test void changedConflictAndUnauthorizedOperatorCannotChooseValue(){
        conflict();var view=conflicts.detail(7,List.of("ENERGY_MANAGER"),"P1",event(0).eventTime());
        var request=new TelemetryConflictService.ResolveRequest(null,view.revision(),"核实","工单");
        assertThatThrownBy(()->transaction(()->conflicts.resolve(8,List.of("PLATFORM_ADMIN"),"P1",view.eventTime(),request))).isInstanceOf(BusinessException.class);
        ledger.reserve(event(130),()->null);
        assertThatThrownBy(()->transaction(()->conflicts.resolve(7,List.of("ENERGY_MANAGER"),"P1",view.eventTime(),request))).isInstanceOf(BusinessException.class);
        doThrow(new BusinessException(403,"无建筑权限")).when(scope).checkAccess(9L,List.of("BUILDING_OWNER"),"B1");
        assertThatThrownBy(()->conflicts.detail(9,List.of("BUILDING_OWNER"),"P1",view.eventTime())).isInstanceOf(BusinessException.class);
    }
    @Test void auditFailureRollsBackValueAndResolutionAndOutbox(){
        conflict();var view=conflicts.detail(7,List.of("BUILDING_OWNER"),"P1",event(0).eventTime());
        doThrow(new IllegalStateException("audit unavailable")).when(audit).append(any());
        assertThatThrownBy(()->transaction(()->conflicts.resolve(7,List.of("BUILDING_OWNER"),"P1",view.eventTime(),new TelemetryConflictService.ResolveRequest(view.variants().getFirst().evidenceId(),view.revision(),"核实","工单")))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_resolution",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_change_task",Integer.class)).isZero();
        assertThat(ledger.effective(List.of(event(100)))).containsExactly(event(100));
    }
    @Test void distinctCapacityFailureRollsBackAndKnownRetransmitsStillMerge(){
        ledger=new RawTelemetryLedger(jdbc,codec,1,1);ledger.reserve(event(100),()->null);ledger.reserve(event(120),()->null);
        assertThatThrownBy(()->ledger.reserve(event(130),()->null)).hasMessageContaining("capacity");
        assertThat(ledger.reserve(event(120),()->null).conflict()).isTrue();
        assertThat(jdbc.queryForObject("SELECT used_rows FROM biz_telemetry_conflict_budget WHERE budget_key='EVIDENCE'",Long.class)).isEqualTo(1);
        assertThat(ledger.evidence(ledger.find("P1",event(0).eventTime())).getFirst().attempts()).isEqualTo(2);
    }
    @Test void retirementProtectsPendingAndNeverAllowsOldKeyToReappear(){
        var e=event(100);ledger.reserve(e,()->null);
        assertThat(ledger.retireBefore(e.pointId(),e.eventTime()+1)).isEqualTo(e.eventTime());
        ledger.storeFrozen(e,()->null);assertThat(ledger.retireBefore(e.pointId(),e.eventTime()+1)).isEqualTo(e.eventTime()+1);
        assertThat(ledger.reserve(event(120),()->{throw new AssertionError("No retired read");}).expired()).isTrue();
        assertThatThrownBy(()->ledger.storeFrozen(e,()->null)).hasMessageContaining("retired");
    }
    @Test void retiredConflictCannotPretendThatAnUnavailableBusinessReadingWasCorrected(){
        conflict();var view=conflicts.detail(7,List.of("BUILDING_OWNER"),"P1",event(0).eventTime());
        ledger.retireBefore("P1",view.eventTime()+1);
        assertThatThrownBy(()->transaction(()->conflicts.resolve(7,List.of("BUILDING_OWNER"),"P1",view.eventTime(),new TelemetryConflictService.ResolveRequest(view.variants().getFirst().evidenceId(),view.revision(),"核实","工单"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已退役");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_resolution",Integer.class)).isZero();
    }

    @Test void expiryDoesNotAdoptUnverifiedValueOrReopenOnSameRetransmit(){
        conflict();long future=System.currentTimeMillis()+Duration.ofDays(8).toMillis();
        var recovery=new TelemetryConflictRecovery(jdbc,ledger,mock(TdengineHvacRawEventRepository.class),mock(TelemetryMessageJournal.class),mock(com.platform.iot.ingest.v2.TelemetryV2IngestionService.class),new StaticListableBeanFactory().getBeanProvider(com.platform.iot.dataquality.LateRealMinuteCorrectionService.class),mock(EnergyPeriodResultRepository.class),new SimpleMeterRegistry(),codec,7);
        recovery.expire(future);
        assertThat(ledger.find("P1",event(0).eventTime()).conflictState()).isEqualTo("EXPIRED");
        ledger.reserve(event(120),()->null);assertThat(ledger.find("P1",event(0).eventTime()).conflictState()).isEqualTo("EXPIRED");
        assertThat(ledger.effective(List.of(event(100)))).containsExactly(event(100));
        recovery.expire(future+Duration.ofDays(180).toMillis());
        assertThat(ledger.evidence(ledger.find("P1",event(0).eventTime()))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT used_rows FROM biz_telemetry_conflict_budget WHERE budget_key='EVIDENCE'",Long.class)).isZero();
        ledger.reserve(event(120),()->null);assertThat(ledger.find("P1",event(0).eventTime()).conflictState()).isEqualTo("EXPIRED");
    }
}
