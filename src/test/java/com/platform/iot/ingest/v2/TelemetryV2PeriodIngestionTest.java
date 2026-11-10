package com.platform.iot.ingest.v2;

import com.platform.iot.identity.*;
import com.platform.iot.reliability.*;
import com.platform.iot.reliability.mapper.*;
import com.platform.iot.ingest.standard.*;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import com.platform.carbon.DynamicElectricityContracts.DeviceIntervalRequest;
import com.platform.adapter.model.ElectricityInterval;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelemetryV2PeriodIngestionTest {
    private final DeviceIdentityKey key=new DeviceIdentityKey("SERIAL_NO","SIMULATED");
    private final DeviceIdentityBinding binding=new DeviceIdentityBinding("I",key,"E","E","B","METER",AckMode.ADAPTER_PROXY,CorrelationPolicy.BOOT_ID_AND_SEQ,null,"own/ack");
    private final Map<String,TelemetryMessageJournal.Entry> entries=new HashMap<>();
    private StandardTelemetryIngestionService numeric;
    private DynamicElectricityV2Input periods;
    private TelemetryMessageJournal journal;
    private TelemetryV2IngestionService service;
    @BeforeEach void setup() {
        var identities=mock(DeviceIdentityProvider.class);when(identities.find(key)).thenReturn(Optional.of(binding));
        numeric=mock(StandardTelemetryIngestionService.class);periods=mock(DynamicElectricityV2Input.class);journal=mock(TelemetryMessageJournal.class);
        when(journal.find(anyString())).thenAnswer(a->entries.get(a.getArgument(0)));
        when(journal.freeze(anyString(),any())).thenAnswer(a->{TelemetryMessageJournal.Snapshot s=a.getArgument(1);var e=new TelemetryMessageJournal.Entry(s.message().canonicalMessageId(),a.getArgument(0),s,"PENDING",null);entries.put(e.id(),e);return e;});
        when(journal.locked(anyString(),any())).thenAnswer(a->((java.util.function.Function<TelemetryMessageJournal.Entry,Object>)a.getArgument(1)).apply(entries.get(a.getArgument(0))));
        doAnswer(a->{var e=entries.get(a.getArgument(0));entries.put(e.id(),new TelemetryMessageJournal.Entry(e.id(),e.hash(),e.snapshot(),"COMPLETE",a.getArgument(1)));return null;}).when(journal).complete(anyString(),any());
        lenient().when(periods.prepare(any(),eq(binding))).thenAnswer(a->{TelemetryV2Message m=a.getArgument(0);var p=m.electricityInterval();return new DeviceIntervalRequest(key,"METER","P",Instant.ofEpochMilli(p.startMs()),Instant.ofEpochMilli(p.endMs()),p.revision(),p.electricityKwh(),p.factorKgCo2PerKwh(),p.sourceNature(),p.sourceReference());});
        service=new TelemetryV2IngestionService(identities,mock(UnknownDeviceHandler.class),numeric,mock(TelemetryReceiptMapper.class),mock(TelemetryReceiptFailureMapper.class),new AckModeResolver(),new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),"own/ack",journal,periods);
    }
    private TelemetryV2Message message(String id,String quantity,List<StandardMetric> metrics) {
        var p=new ElectricityInterval("Q",1790784000000L,1790784900000L,1,new BigDecimal(quantity),new BigDecimal("0.5"),"kWh","kgCO2/kWh","SIMULATED","V2_TEST");
        return new TelemetryV2Message("2.0","METER",1,key,id,null,"BOOT",1L,1790784900000L,1790785000000L,null,null,null,null,"ADAPTER_DERIVED","DEVICE_REPORTED","DERIVED","ADAPTER_PROXY","BOOT_ID_AND_SEQ",metrics,p);
    }
    @Test void periodOnlyHasExplicitReceiptAndKeepsDuplicateIdempotent() {
        var m=message("M1","1",List.of());
        assertThat(service.ingest(m,System.currentTimeMillis()).status()).isEqualTo(ReceiptStatus.PERIOD_INPUT_PERSISTED);
        assertThat(service.ingest(m,System.currentTimeMillis()).status()).isEqualTo(ReceiptStatus.DUPLICATE_PERIOD_INPUT);
        verify(periods,times(1)).persist(any(),eq(binding));verifyNoInteractions(numeric);
    }
    @Test void includesPeriodInConflictDigestBeforeAnotherPersist() {
        service.ingest(message("M1","1",List.of()),System.currentTimeMillis());
        assertThat(service.ingest(message("M1","2",List.of()),System.currentTimeMillis()).status()).isEqualTo(ReceiptStatus.MESSAGE_CONFLICT);
        verify(periods,times(1)).persist(any(),eq(binding));verify(journal).conflict(eq("M1"),anyString(),any());
    }
    @Test void mixedPayloadWaitsForRawStorageBeforePeriodReceipt() {
        var event=new RawTelemetryEvent("P1","T","S","T","D","B",null,"E","D","T",null,"T",1,1790784900000L,1790785000000L,0,0,false);
        when(numeric.prepareImmutable(any(),anyLong())).thenReturn(new StandardTelemetryIngestionService.PreparedBatch(StandardTelemetryResult.accepted(1),List.of(event)));
        when(numeric.ingestPrepared(any())).thenReturn(StandardTelemetryResult.retryable(0,"storage"));
        var m=message("M1","1",List.of(new StandardMetric("T",BigDecimal.ONE,"C","/t")));
        assertThat(service.ingest(m,System.currentTimeMillis()).retryable()).isTrue();verify(periods,never()).persist(any(),any());
        when(numeric.ingestPrepared(any())).thenReturn(StandardTelemetryResult.accepted(1));
        assertThat(service.recover("M1").status()).isEqualTo(ReceiptStatus.PERIOD_INPUT_PERSISTED);verify(periods).persist(any(),eq(binding));
    }
    @Test void dependencyFailureAndInvalidPeriodDoNotBecomeSuccess() {
        when(periods.prepare(any(),any())).thenThrow(new BusinessException(503,"DEPENDENCY","unavailable"));
        assertThat(service.ingest(message("M1","1",List.of()),System.currentTimeMillis()).retryable()).isTrue();verify(journal,never()).freeze(any(),any());
        assertThat(service.ingest(message("M2","-1",List.of()),System.currentTimeMillis()).status()).isEqualTo(ReceiptStatus.PLATFORM_REJECTED);
    }
}
