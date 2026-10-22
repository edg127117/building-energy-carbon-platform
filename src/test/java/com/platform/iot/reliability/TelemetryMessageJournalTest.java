package com.platform.iot.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.identity.*;
import com.platform.iot.ingest.standard.StandardMetric;
import com.platform.iot.ingest.v2.*;
import com.platform.iot.temporal.impl.TdengineHvacRawEventRepository;
import com.platform.energy.period.EnergyPeriodResultRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelemetryMessageJournalTest {
    TelemetryConflictTest f;TelemetryMessageJournal journal;
    @BeforeEach void setup(){f=new TelemetryConflictTest();f.setup();journal=new TelemetryMessageJournal(f.jdbc,new ObjectMapper().findAndRegisterModules());}
    TelemetryV2Message message(String id,double value){return new TelemetryV2Message("2.0","P1",1,new DeviceIdentityKey("MAC","A"),id,id,null,null,f.event(0).eventTime(),f.event(0).receivedTime(),null,null,null,null,"DEVICE_REPORTED","DEVICE_REPORTED","EXACT","EVIDENCE_ONLY","NONE",List.of(new StandardMetric("T",BigDecimal.valueOf(value),"C","/t")));}
    TelemetryMessageJournal.Snapshot snapshot(String id){return new TelemetryMessageJournal.Snapshot(message(id,100),new DeviceIdentityBinding("I1",new DeviceIdentityKey("MAC","A"),"E1","DEV1","B1","P1",AckMode.EVIDENCE_ONLY,CorrelationPolicy.NONE,null,null),new AckModeResolution(AckMode.EVIDENCE_ONLY,AckMode.EVIDENCE_ONLY,null,null),f.event(0).receivedTime(),List.of(f.event(100)));}
    void complete(String id){journal.locked(id,e->{journal.complete(id,new V2ProcessingResult(id,ReceiptStatus.PLATFORM_PERSISTED,"PLATFORM_PERSISTED",1,false,AckMode.EVIDENCE_ONLY,null,new PlatformApplicationAck("1.0",id,ReceiptStatus.PLATFORM_PERSISTED,"PLATFORM_PERSISTED",1L,2L,AckMode.EVIDENCE_ONLY,"EVIDENCE_ONLY"),null));return null;});}
    TelemetryConflictRecovery recovery(){return new TelemetryConflictRecovery(f.jdbc,f.ledger,mock(TdengineHvacRawEventRepository.class),journal,mock(TelemetryV2IngestionService.class),new StaticListableBeanFactory().getBeanProvider(com.platform.iot.dataquality.LateRealMinuteCorrectionService.class),mock(EnergyPeriodResultRepository.class),new SimpleMeterRegistry(),f.codec,7);}

    @Test void duplicateFreezeKeepsFirstBindingAndTerminalSurvivesReceiptRetention(){
        var original=journal.freeze("first",snapshot("M1"));
        journal.freeze("different",snapshot("M1"));
        complete("M1");var restarted=new TelemetryMessageJournal(f.jdbc,new ObjectMapper().findAndRegisterModules());
        assertThat(restarted.find("M1").snapshot()).isEqualTo(original.snapshot());
        assertThat(restarted.find("M1").hash()).isEqualTo("first");
        assertThat(restarted.find("M1").terminal().status()).isEqualTo(ReceiptStatus.PLATFORM_PERSISTED);
    }
    @Test void sameCanonicalConflictBecomesPointCandidateAndReleasesBothBudgetsAtExpiry(){
        var e=f.event(100);f.ledger.reserve(e,()->null);f.ledger.storeFrozen(e,()->null);
        journal.freeze("first",snapshot("M1"));complete("M1");
        journal.conflict("M1","second",message("M1",120));
        journal.materializeConflicts("M1",f.ledger,f.codec);
        assertThat(f.ledger.evidence(f.ledger.find("P1",e.eventTime()))).hasSize(1);
        assertThat(f.ledger.evidence(f.ledger.find("P1",e.eventTime())).getFirst().snapshotJson()).contains("120.0");
        assertThat(journal.conflictMessages()).isEmpty();
        assertThat(f.jdbc.queryForObject("SELECT used_rows FROM biz_telemetry_conflict_budget WHERE budget_key='EVIDENCE'",Long.class)).isEqualTo(2);
        recovery().expire(System.currentTimeMillis()+Duration.ofDays(181).toMillis());
        assertThat(f.jdbc.queryForObject("SELECT used_rows FROM biz_telemetry_conflict_budget WHERE budget_key='EVIDENCE'",Long.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_message_conflict",Long.class)).isZero();
    }
    @Test void failedOriginalAndMessageRequireAuthorizedExplicitRetryAfterTenFailures(){
        var event=f.event(100);f.ledger.reserve(event,()->null);journal.freeze("first",snapshot("M1"));
        for(int i=0;i<10;i++){f.ledger.retryLater(event,0);journal.retry("M1",0);}
        assertThat(f.ledger.pending(50,Long.MAX_VALUE)).isEmpty();assertThat(journal.pending(Long.MAX_VALUE,50)).isEmpty();
        assertThat(f.conflicts.failed(7,List.of("BUILDING_OWNER"),"B1")).hasSize(2);
        assertThatThrownBy(()->f.transaction(()->{f.conflicts.retry(8,List.of("BUILDING_OWNER"),new TelemetryConflictService.RetryRequest("RAW",RawTelemetryLedger.identity("P1",event.eventTime())));return null;})).isInstanceOf(com.platform.framework.exception.BusinessException.class);
        f.transaction(()->{f.conflicts.retry(7,List.of("BUILDING_OWNER"),new TelemetryConflictService.RetryRequest("RAW",RawTelemetryLedger.identity("P1",event.eventTime())));return null;});
        assertThat(f.ledger.pending(50,Long.MAX_VALUE)).containsExactly(event);
        assertThat(f.ledger.retireBefore("P1",event.eventTime()+1)).isEqualTo(event.eventTime());
    }
    @Test void configuredSharedCapacityAppliesToMessageEvidenceAndKnownRetransmissions(){
        journal=new TelemetryMessageJournal(f.jdbc,new ObjectMapper().findAndRegisterModules(),1,1);journal.freeze("first",snapshot("M1"));
        journal.conflict("M1","second",message("M1",120));
        assertThatThrownBy(()->journal.conflict("M1","third",message("M1",130))).hasMessageContaining("capacity");
        journal.conflict("M1","second",message("M1",120));
        assertThat(f.jdbc.queryForObject("SELECT attempt_count FROM biz_telemetry_message_conflict",Long.class)).isEqualTo(2);
    }
}
