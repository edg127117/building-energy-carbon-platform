package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.platform.carbon.DailyTrendSummaryContracts.*;

class DailyTrendSummaryServiceTest {
    CarbonAuthorization auth=mock(CarbonAuthorization.class);
    DailyTrendSummaryRepository repo=mock(DailyTrendSummaryRepository.class);
    DailyTrendSummaryCache cache=mock(DailyTrendSummaryCache.class);
    DailyElectricityCarbonService detailed=mock(DailyElectricityCarbonService.class);
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    DailyTrendSummaryService service=new DailyTrendSummaryService(auth,repo,cache,detailed,json);
    final String version="a".repeat(64);
    DailyTrendSummaryRepository.Head head;
    String payload;
    @BeforeEach void setup() throws Exception {
        payload=json.writeValueAsString(from(full(),version));
        head=new DailyTrendSummaryRepository.Head("B","2026-01",1,version,version,"READY",CarbonCalculationCore.sha256(payload),null,0,0);
        when(repo.sourceVersion("B",YearMonth.of(2026,1))).thenReturn(version);
        when(repo.head("B","2026-01")).thenReturn(head);
    }
    @Test void authorizationRunsEvenWhenCacheIsWarm() {
        doThrow(new BusinessException(403,"denied")).when(auth).requireReader(1,List.of("BUILDING_OWNER"),"B");
        assertThatThrownBy(()->service.query(1,List.of("BUILDING_OWNER"),"B","2026-01")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repo,cache,detailed);
    }
    @Test void warmReadUsesVersionAndHashWithoutReadingFactsOrPersistedBody() {
        when(cache.get("B","2026-01",version)).thenReturn(payload);
        assertThat(service.query(1,List.of("ENERGY_MANAGER"),"B","2026-01").days().getFirst().electricityKwh()).isEqualByComparingTo("12.3400");
        verify(repo,never()).payload(any());verifyNoInteractions(detailed);
    }
    @Test void redisMissOrCorruptionFallsBackToPersistedSummary() {
        when(cache.get("B","2026-01",version)).thenReturn("corrupt");when(repo.payload(head)).thenReturn(payload);
        assertThat(service.query(1,List.of("ENERGY_MANAGER"),"B","2026-01").sourceVersion()).isEqualTo(version);
        verify(cache).put("B","2026-01",version,payload);verifyNoInteractions(detailed);
    }
    @Test void sourceRevisionEnqueuesRebuildAndNeverServesPreviousVersion() {
        when(repo.sourceVersion("B",YearMonth.of(2026,1))).thenReturn("b".repeat(64));
        var result=service.query(1,List.of("ENERGY_MANAGER"),"B","2026-01");
        assertThat(result.state()).isEqualTo("PREPARING");assertThat(result.days()).isEmpty();
        verify(repo).request(eq("B"),eq("2026-01"),eq(1L),eq("b".repeat(64)),anyLong());verifyNoInteractions(cache,detailed);
    }
    @Test void firstReadQueuesWorkInsteadOfBlockingOnFullMonth() {
        when(repo.head("B","2026-01")).thenReturn(null);
        assertThat(service.query(1,List.of("ENERGY_MANAGER"),"B","2026-01").retryAfterSeconds()).isEqualTo(2);
        verifyNoInteractions(detailed);
    }
    @Test void concurrentSourceChangeCannotPublishOldReadModel() {
        when(detailed.queryPrepared(1,List.of("ENERGY_MANAGER"),"B","2026-01")).thenReturn(full());
        when(repo.sourceVersion("B",YearMonth.of(2026,1))).thenReturn(version,"b".repeat(64));
        assertThat(service.rebuild(head,"lease",List.of("ENERGY_MANAGER"))).isFalse();
        verify(repo,never()).publish(any(),any(),any(),any(),anyLong());verifyNoInteractions(cache);
        verify(detailed).queryPrepared(1,List.of("ENERGY_MANAGER"),"B","2026-01");
    }
    @Test void sourceChangedBeforePreparationAvoidsScanningAndQueuesFreshVersion() {
        when(repo.sourceVersion("B",YearMonth.of(2026,1))).thenReturn("b".repeat(64));
        assertThat(service.rebuild(head,"lease",List.of("ENERGY_MANAGER"))).isFalse();
        verifyNoInteractions(detailed,cache);
        verify(repo).request(eq("B"),eq("2026-01"),eq(1L),eq("b".repeat(64)),anyLong());
        verify(repo,never()).publish(any(),any(),any(),any(),anyLong());
    }
    @Test void lostLeaseCannotPopulateCache() {
        when(detailed.queryPrepared(1,List.of("ENERGY_MANAGER"),"B","2026-01")).thenReturn(full());
        assertThat(service.rebuild(head,"old-lease",List.of("ENERGY_MANAGER"))).isFalse();verifyNoInteractions(cache);
    }
    @Test void summaryPreservesPrecisionAndPointersButOmitsLargeEvidenceList() throws Exception {
        var tree=json.readTree(payload);
        assertThat(tree.path("days").get(0).path("electricityKwh").asText()).isEqualTo("12.3400");
        assertThat(tree.path("days").get(0).has("electricityEvidenceIds")).isFalse();
        assertThat(tree.path("days").get(0).path("hourlyBatchId").asText()).isEqualTo("H");
        assertThat(payload.length()).isLessThan(5000);
    }
    @Test void malformedOrCrossScopePersistedPayloadIsRejected() {
        when(repo.payload(head)).thenReturn(payload.replace("\"B\"","\"OTHER\""));
        assertThatThrownBy(()->service.query(1,List.of("ENERGY_MANAGER"),"B","2026-01"))
                .isInstanceOf(BusinessException.class).extracting(e->((BusinessException)e).getErrorCode()).isEqualTo("DAILY_SUMMARY_INTEGRITY");
    }
    @Test void detailedWrapperReturnsOriginalFullContractOnlyAfterStableSourceVersion() {
        var expected=full();when(detailed.queryPrepared(1,List.of("ENERGY_MANAGER"),"B","2026-01")).thenReturn(expected);
        assertThat(service.queryDetail(1,List.of("ENERGY_MANAGER"),"B","2026-01")).isSameAs(expected);
        var ordered=inOrder(auth,repo,detailed);
        ordered.verify(auth).requireReader(1,List.of("ENERGY_MANAGER"),"B");
        ordered.verify(repo).sourceVersion("B",YearMonth.of(2026,1));
        ordered.verify(detailed).queryPrepared(1,List.of("ENERGY_MANAGER"),"B","2026-01");
        ordered.verify(repo).sourceVersion("B",YearMonth.of(2026,1));
        verify(repo,never()).request(anyString(),anyString(),anyLong(),anyString(),anyLong());
        verify(repo,never()).publish(any(),any(),any(),any(),anyLong());verifyNoInteractions(cache);
    }
    @Test void detailedWrapperRejectsChangedVersionRatherThanReturningMixedDetails() {
        when(repo.sourceVersion("B",YearMonth.of(2026,1))).thenReturn(version,"b".repeat(64));
        when(detailed.queryPrepared(1,List.of("ENERGY_MANAGER"),"B","2026-01")).thenReturn(full());
        assertThatThrownBy(()->service.queryDetail(1,List.of("ENERGY_MANAGER"),"B","2026-01"))
                .isInstanceOfSatisfying(BusinessException.class,e->{assertThat(e.getCode()).isEqualTo(409);assertThat(e.getErrorCode()).isEqualTo("DAILY_TREND_SOURCE_CHANGED");});
        verifyNoInteractions(cache);verify(repo,never()).publish(any(),any(),any(),any(),anyLong());
    }
    @Test void detailedWrapperValidatesAndAuthorizesBeforeAnySourceRead() {
        assertThatThrownBy(()->service.queryDetail(1,List.of("ENERGY_MANAGER"),"B","2026-13"))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo("DAILY_TREND_QUERY_INVALID"));
        doThrow(new BusinessException(403,"denied")).when(auth).requireReader(1,List.of("ENERGY_MANAGER"),"B");
        assertThatThrownBy(()->service.queryDetail(1,List.of("ENERGY_MANAGER"),"B","2026-01")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repo,detailed,cache);
    }
    @Test void detailedWrapperCannotAcceptTwoAbsentVersionsAsConsistent() {
        when(repo.sourceVersion("B",YearMonth.of(2026,1))).thenReturn(null);
        assertThatThrownBy(()->service.queryDetail(1,List.of("ENERGY_MANAGER"),"B","2026-01"))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo("DAILY_SUMMARY_INTEGRITY"));
        verifyNoInteractions(detailed,cache);
    }
    static DailyElectricityCarbonContracts.View full() {
        var start=Instant.parse("2025-12-31T16:00:00Z");
        var day=new DailyElectricityCarbonContracts.Day(LocalDate.of(2026,1,1),start,start.plusSeconds(86400),new BigDecimal("12.3400"),"AVAILABLE",null,"CV","RV",
                Collections.nCopies(10000,"source-reading-id"),"SOURCE_LABELLED",new BigDecimal("0.007191"),"AVAILABLE",new BigDecimal("0.5827"),"AVAILABLE","H",1,"SIMULATED",List.of("F"),"h".repeat(64));
        return new DailyElectricityCarbonContracts.View("B","2026-01","Asia/Shanghai","COMPLETE","kWh","tCO2e","kgCO2e/kWh","MONITORING","DAILY_ELECTRICITY_STATIC_CARBON_V1",List.of(day),Instant.now(),"e".repeat(64));
    }
}
