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
        assertThat(f.jdbc.queryForObject("SELECT used_rows FROM biz_telemetry_conflict_budget WHERE budget_key='EVIDENCE'",Long.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT used_rows FROM biz_telemetry_conflict_budget WHERE budget_key='MESSAGE_EVIDENCE'",Long.class)).isEqualTo(1);
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
    @Test void configuredMessageCapacityAppliesToEvidenceAndKnownRetransmissions(){
        journal=new TelemetryMessageJournal(f.jdbc,new ObjectMapper().findAndRegisterModules(),1,1);journal.freeze("first",snapshot("M1"));
        journal.conflict("M1","second",message("M1",120));
        assertThatThrownBy(()->journal.conflict("M1","third",message("M1",130))).hasMessageContaining("capacity");
        journal.conflict("M1","second",message("M1",120));
        assertThat(f.jdbc.queryForObject("SELECT attempt_count FROM biz_telemetry_message_conflict",Long.class)).isEqualTo(2);
    }

    @Test void smallestEvidenceBudgetsStillAllowMessageToBecomeASelectableCandidate(){
        journal=new TelemetryMessageJournal(f.jdbc,new ObjectMapper().findAndRegisterModules(),16,1);
        f.ledger=new RawTelemetryLedger(f.jdbc,f.codec,16,1);
        var e=f.event(100);f.ledger.reserve(e,()->null);f.ledger.storeFrozen(e,()->null);
        journal.freeze("first",snapshot("M1"));complete("M1");journal.conflict("M1","second",message("M1",120));
        journal.materializeConflicts("M1",f.ledger,f.codec);
        assertThat(f.ledger.evidence(f.ledger.find("P1",e.eventTime()))).hasSize(1);
        assertThat(journal.conflictMessages()).isEmpty();
        assertThat(f.jdbc.queryForList("SELECT used_rows FROM biz_telemetry_conflict_budget WHERE budget_key IN ('EVIDENCE','MESSAGE_EVIDENCE')",Long.class)).containsExactlyInAnyOrder(1L,1L);
        recovery().expire(System.currentTimeMillis()+Duration.ofDays(181).toMillis());
        assertThat(f.jdbc.queryForList("SELECT used_rows FROM biz_telemetry_conflict_budget WHERE budget_key IN ('EVIDENCE','MESSAGE_EVIDENCE')",Long.class)).containsOnly(0L);
    }

    @Test void blockedMaterializationFailsAfterTenAttemptsAndOnlyAuthorizedRetryResumesIt(){
        journal=new TelemetryMessageJournal(f.jdbc,new ObjectMapper().findAndRegisterModules(),16,1);
        f.ledger=new RawTelemetryLedger(f.jdbc,f.codec,16,1);
        var e=f.event(100);f.ledger.reserve(e,()->null);f.ledger.storeFrozen(e,()->null);
        f.ledger.reserve(f.event(130),()->null);
        journal.freeze("first",snapshot("M1"));complete("M1");journal.conflict("M1","second",message("M1",120));
        for(int i=0;i<10;i++)journal.materializeConflicts("M1",f.ledger,f.codec);
        journal.conflict("M1","second",message("M1",120));
        assertThat(journal.conflictMessages()).isEmpty();
        f.jdbc.update("UPDATE biz_telemetry_message_conflict SET last_seen_ms=?",System.currentTimeMillis()-Duration.ofDays(181).toMillis());
        recovery().expire(System.currentTimeMillis());
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_message_conflict",Integer.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT used_rows FROM biz_telemetry_conflict_budget WHERE budget_key='MESSAGE_EVIDENCE'",Long.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT materialized FROM biz_telemetry_message_conflict",Integer.class)).isEqualTo(-1);
        assertThat(f.conflicts.failed(7,List.of("BUILDING_OWNER"),"B1")).extracting(TelemetryConflictService.RecoveryView::kind).containsExactly("MESSAGE_CONFLICT");
        var retry=new TelemetryConflictService.RetryRequest("MESSAGE_CONFLICT","M1");
        assertThatThrownBy(()->f.transaction(()->{f.conflicts.retry(8,List.of("BUILDING_OWNER"),retry);return null;})).isInstanceOf(com.platform.framework.exception.BusinessException.class);
        doThrow(new IllegalStateException("audit unavailable")).when(f.audit).append(any());
        assertThatThrownBy(()->f.transaction(()->{f.conflicts.retry(7,List.of("BUILDING_OWNER"),retry);return null;})).hasMessageContaining("audit unavailable");
        assertThat(f.jdbc.queryForObject("SELECT materialized FROM biz_telemetry_message_conflict",Integer.class)).isEqualTo(-1);
        reset(f.audit);
        f.jdbc.update("UPDATE biz_telemetry_conflict_evidence SET last_seen_ms=?",System.currentTimeMillis()-Duration.ofDays(181).toMillis());
        recovery().expire(System.currentTimeMillis());
        f.transaction(()->{f.conflicts.retry(7,List.of("BUILDING_OWNER"),retry);return null;});
        journal.materializeConflicts("M1",f.ledger,f.codec);
        assertThat(f.ledger.evidence(f.ledger.find("P1",e.eventTime()))).hasSize(1);
        recovery().expire(System.currentTimeMillis());
        assertThat(f.jdbc.queryForObject("SELECT used_rows FROM biz_telemetry_conflict_budget WHERE budget_key='MESSAGE_EVIDENCE'",Long.class)).isZero();
    }

    @Test void pendingMaterializationIsNotDiscardedByPayloadRetention(){
        journal.freeze("first",snapshot("M1"));complete("M1");journal.conflict("M1","second",message("M1",120));
        f.jdbc.update("UPDATE biz_telemetry_message_conflict SET last_seen_ms=?",System.currentTimeMillis()-Duration.ofDays(181).toMillis());
        recovery().expire(System.currentTimeMillis());
        assertThat(f.jdbc.queryForObject("SELECT materialized FROM biz_telemetry_message_conflict",Integer.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT used_rows FROM biz_telemetry_conflict_budget WHERE budget_key='MESSAGE_EVIDENCE'",Long.class)).isEqualTo(1);
    }

    @Test void retiredOriginalArchivesMessageEvidenceWithoutPretendingThatAValueWasAdopted(){
        var e=f.event(100);f.ledger.reserve(e,()->null);f.ledger.storeFrozen(e,()->null);
        journal.freeze("first",snapshot("M1"));complete("M1");journal.conflict("M1","second",message("M1",120));
        f.ledger.retireBefore("P1",e.eventTime()+1);
        assertThat(f.ledger.find("P1",e.eventTime())).isNull();
        journal.materializeConflicts("M1",f.ledger,f.codec);
        assertArchived();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_conflict_evidence",Integer.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_resolution",Integer.class)).isZero();
        assertThat(f.conflicts.failed(7,List.of("BUILDING_OWNER"),"B1")).isEmpty();
        assertThatThrownBy(()->f.transaction(()->{f.conflicts.retry(7,List.of("BUILDING_OWNER"),
                new TelemetryConflictService.RetryRequest("MESSAGE_CONFLICT","M1"));return null;}))
                .isInstanceOf(com.platform.framework.exception.BusinessException.class);
        journal.conflict("M1","second",message("M1",120));
        assertThat(f.jdbc.queryForObject("SELECT materialized FROM biz_telemetry_message_conflict",Integer.class)).isEqualTo(3);
        f.jdbc.update("UPDATE biz_telemetry_message_conflict SET last_seen_ms=?",System.currentTimeMillis()-Duration.ofDays(181).toMillis());
        recovery().expire(System.currentTimeMillis());recovery().expire(System.currentTimeMillis());
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM biz_telemetry_message_conflict",Integer.class)).isZero();
        assertThat(f.jdbc.queryForObject("SELECT used_rows FROM biz_telemetry_conflict_budget WHERE budget_key='MESSAGE_EVIDENCE'",Long.class)).isZero();
    }

    @Test void authorizedRetryOfAnOldFailureCanArchiveAfterOriginalRetires(){
        journal.freeze("first",snapshot("M1"));complete("M1");journal.conflict("M1","second",message("M1",120));
        for(int i=0;i<10;i++)journal.materializeConflicts("M1",f.ledger,f.codec);
        assertThat(f.jdbc.queryForObject("SELECT materialized FROM biz_telemetry_message_conflict",Integer.class)).isEqualTo(-1);
        var e=f.event(100);f.ledger.reserve(e,()->null);f.ledger.storeFrozen(e,()->null);
        f.ledger.retireBefore("P1",e.eventTime()+1);
        f.transaction(()->{f.conflicts.retry(7,List.of("BUILDING_OWNER"),
                new TelemetryConflictService.RetryRequest("MESSAGE_CONFLICT","M1"));return null;});
        journal.materializeConflicts("M1",f.ledger,f.codec);assertArchived();
    }

    @Test void retainedConflictSnapshotBelowRetirementFloorDoesNotReceiveMorePointCandidates(){
        f.conflict();var e=f.event(100);
        journal.freeze("first",snapshot("M1"));complete("M1");journal.conflict("M1","third",message("M1",130));
        f.ledger.retireBefore("P1",e.eventTime()+1);
        assertThat(f.ledger.find("P1",e.eventTime())).isNotNull();
        journal.materializeConflicts("M1",f.ledger,f.codec);assertArchived();
        assertThat(f.ledger.evidence(f.ledger.find("P1",e.eventTime()))).hasSize(1);
    }

    @Test void mixedBatchStillMaterializesLivePointsWhenAnotherPointHasRetired()throws Exception{
        var first=f.event(100);
        var second=new com.platform.iot.temporal.model.RawTelemetryEvent("P2","TEMP2",first.sourceSystem(),
                "MAC:A:TEMP2",first.sourceDeviceId(),first.buildingId(),null,first.equipId(),first.equipCode(),
                first.familyCode(),null,"TEMP2",200,first.eventTime(),first.receivedTime(),0,0,false);
        for(var e:List.of(first,second)){f.ledger.reserve(e,()->null);f.ledger.storeFrozen(e,()->null);}
        var mapper=new ObjectMapper().findAndRegisterModules();
        var baseNode=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(message("M1",100));
        baseNode.set("metrics",mapper.valueToTree(List.of(new StandardMetric("T",BigDecimal.valueOf(100),"C","/t"),
                new StandardMetric("T2",BigDecimal.valueOf(200),"C","/t2"))));
        var base=mapper.treeToValue(baseNode,TelemetryV2Message.class);var frozen=snapshot("M1");
        journal.freeze("first",new TelemetryMessageJournal.Snapshot(base,frozen.binding(),frozen.mode(),frozen.receivedAt(),List.of(first,second)));
        complete("M1");
        baseNode.set("metrics",mapper.valueToTree(List.of(new StandardMetric("T",BigDecimal.valueOf(120),"C","/t"),
                new StandardMetric("T2",BigDecimal.valueOf(220),"C","/t2"))));
        journal.conflict("M1","second",mapper.treeToValue(baseNode,TelemetryV2Message.class));
        f.ledger.retireBefore("P1",first.eventTime()+1);
        journal.materializeConflicts("M1",f.ledger,f.codec);assertArchived();
        assertThat(f.ledger.find("P1",first.eventTime())).isNull();
        assertThat(f.ledger.evidence(f.ledger.find("P2",first.eventTime()))).hasSize(1)
                .first().extracting(e->f.codec.decode(e.snapshotJson()).value()).isEqualTo(220d);
    }

    private void assertArchived(){
        assertThat(journal.conflictMessages()).isEmpty();
        assertThat(f.jdbc.queryForObject("SELECT materialized FROM biz_telemetry_message_conflict",Integer.class)).isEqualTo(3);
        assertThat(f.jdbc.queryForObject("SELECT used_rows FROM biz_telemetry_conflict_budget WHERE budget_key='MESSAGE_EVIDENCE'",Long.class)).isEqualTo(1);
        var evidence=f.conflicts.messageEvidence(9,List.of("PLATFORM_ADMIN"),"M1").getFirst();
        assertThat(evidence.get("snapshot_json")).isNotNull();
        assertThat(evidence.get("last_error")).isEqualTo("ORIGINAL_RETIRED_USE_HISTORICAL_REVIEW");
    }
}
