package com.platform.carbon;

import com.platform.energy.classification.BuildingDailyElectricityReader;
import com.platform.energy.classification.MonthlyReadingWindow;
import com.platform.energy.classification.ClassificationContracts.QueryView;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 隔离来源端口，执行真实整月编排与冻结证据检查；不连接真实设备或运行库。 */
class DailyElectricityCarbonServiceTest {
    private final CarbonAuthorization auth=mock(CarbonAuthorization.class);
    private final BuildingDailyElectricityReader electricity=mock(BuildingDailyElectricityReader.class);
    private final HourlyCarbonRepository hourly=mock(HourlyCarbonRepository.class);
    private final Set<String> roles=Set.of("ENERGY_MANAGER");
    private DailyElectricityCarbonService service;
    @BeforeEach void setup() {
        service=new DailyElectricityCarbonService(auth,electricity,hourly,new CarbonProperties(),
                Clock.fixed(Instant.parse("2026-10-30T17:00:00Z"),ZoneOffset.UTC));
        when(electricity.read(anyLong(),anyCollection(),any(),any())).thenAnswer(c->quantity(c.getArgument(3)));
        when(hourly.byDate(any(),any(),isNull())).thenAnswer(c->stored(c.getArgument(1),"0.56",false));
    }
    @AfterEach void closeReaders() { service.close(); }
    @Test void backgroundDayUsesBoundedSourcesAndClosesThemWithoutLegacyFallback() {
        var date=LocalDate.of(2026,1,2);var sources=mock(MonthlyReadingWindow.class);
        when(electricity.prepareDay(eq(1L),eq(roles),eq("B"),eq(date),any())).thenReturn(sources);
        when(electricity.readPrepared(1,roles,"B",date,sources)).thenReturn(quantity(date));
        assertThat(service.prepareDay(1,roles,"B",date).date()).isEqualTo(date);
        verify(electricity,never()).read(anyLong(),anyCollection(),any(),any());
        verify(sources).close();
    }
    @ParameterizedTest @CsvSource({"2024-02,29","2026-02,28","2026-04,30","2026-01,31"})
    void returnsEveryCalendarDayAndPreservesZero(String month,int count) {
        var view=service.query(1,roles,"B",month);
        assertThat(view.days()).hasSize(count).allSatisfy(day->{
            assertThat(day.electricityKwh()).isZero();
            assertThat(day.staticEmissionTco2e()).isEqualByComparingTo("0.001234567890123456789");
            assertThat(day.staticFactorKgCo2ePerKwh()).isEqualByComparingTo("0.56");
            assertThat(day.factorVersionIds()).containsExactly("factor");
            assertThat(day.hourlySourceNature()).isEqualTo("SIMULATED");
            assertThat(day.gasBasis()).isEqualTo("CO2_ONLY");
        });
        assertThat(view.state()).isEqualTo("COMPLETE");
        assertThat(view.resultNature()).isEqualTo("MONITORING");
        verify(hourly,times(count)).byDate(eq("B"),any(),isNull());
        verify(hourly,never()).publish(anyLong(),any(),any(),any(),any());
    }
    @Test void acceptsTheApprovedKilowattHourSpellingWithoutChangingTheQuantity() {
        var date=LocalDate.of(2026,1,1);
        var value=new QueryView("B","classification","relation","kWh",start(date),start(date.plusDays(1)),new BigDecimal("12.345"),
                BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"CALCULABLE","SOURCE_LABELLED","BOUNDARY_TOTAL",true,List.of(),List.of(),List.of(),List.of("source"));
        when(electricity.read(1,roles,"B",date)).thenReturn(value);
        var day=service.query(1,roles,"B","2026-01").days().getFirst();
        assertThat(day.electricityStatus()).isEqualTo("AVAILABLE");
        assertThat(day.electricityKwh()).isEqualByComparingTo("12.345");
    }
    @Test void wholeMonthUsesItsOwnBoundedBudgetWithoutChangingOrdinaryQueryTimeout() {
        var properties=new CarbonProperties();
        properties.setQueryTimeout(Duration.ofNanos(1));
        var reader=new DailyElectricityCarbonService(auth,electricity,hourly,properties,
                Clock.fixed(Instant.parse("2026-10-30T17:00:00Z"),ZoneOffset.UTC));
        try(reader) { assertThat(reader.query(1,roles,"B","2026-01").days()).hasSize(31); }
        assertThat(properties.getQueryTimeout()).isEqualTo(Duration.ofNanos(1));
    }
    @Test void expiredDailyTrendBudgetFailsBeforeReadingAnyPartialMonth() {
        var properties=new CarbonProperties();
        properties.setDailyTrendQueryTimeout(Duration.ofNanos(1));
        var reader=new DailyElectricityCarbonService(auth,electricity,hourly,properties,
                Clock.fixed(Instant.parse("2026-10-30T17:00:00Z"),ZoneOffset.UTC));
        try(reader) {
            assertThatThrownBy(()->reader.query(1,roles,"B","2026-01"))
                    .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(504));
        }
        verifyNoInteractions(electricity,hourly);
        properties.setDailyTrendQueryTimeout(Duration.ofMinutes(3));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
    @Test void concurrentMonthRequestsShareTwoReadersAndReturnCalendarOrder() throws Exception {
        var active=new AtomicInteger();var maximum=new AtomicInteger();
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        doAnswer(c->{
            int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);entered.countDown();
            try {
                if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Test reader was not released");
                return quantity(c.getArgument(3));
            } finally { active.decrementAndGet(); }
        }).when(electricity).read(anyLong(),anyCollection(),any(),any());
        try(var callers=Executors.newVirtualThreadPerTaskExecutor()) {
            var january=callers.submit(()->service.query(1,roles,"B","2026-01"));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var february=callers.submit(()->service.query(1,roles,"B","2026-02"));
            release.countDown();
            assertThat(january.get(10,TimeUnit.SECONDS).days()).extracting(d->d.date().getDayOfMonth())
                    .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1,31).boxed().toList());
            assertThat(february.get(10,TimeUnit.SECONDS).days()).hasSize(28);
            assertThat(maximum.get()).isEqualTo(2);
            assertThat(active.get()).isZero();
        } finally { release.countDown(); }
    }
    @Test void missingCarbonDoesNotHideDailyElectricityAndRevisionIsPassedThrough() {
        var date=LocalDate.of(2026,1,2);
        when(hourly.byDate("B",date,null)).thenReturn(null);
        var view=service.query(1,roles,"B","2026-01");
        assertThat(view.state()).isEqualTo("PARTIAL");
        assertThat(view.days().get(1).staticCarbonStatus()).isEqualTo("HOURLY_RESULT_MISSING");
        assertThat(view.days().get(1).staticEmissionTco2e()).isNull();
        assertThat(view.days().get(1).electricityKwh()).isZero();
        assertThat(view.days().getFirst().hourlyRevision()).isEqualTo(2);
    }
    @Test void incompleteHoursOrDifferentFrozenFactorsDoNotBecomeAStaticAverage() {
        when(hourly.byDate("B",LocalDate.of(2026,1,1),null)).thenReturn(stored(LocalDate.of(2026,1,1),"0.56",true));
        when(hourly.byDate("B",LocalDate.of(2026,1,2),null)).thenReturn(stored(LocalDate.of(2026,1,2),"0.57",false));
        var view=service.query(1,roles,"B","2026-01");
        assertThat(view.days().getFirst().staticCarbonStatus()).isEqualTo("INCOMPLETE_HOURS");
        assertThat(view.days().get(1).staticCarbonStatus()).isEqualTo("MULTIPLE_FROZEN_FACTORS");
        assertThat(view.days().subList(0,2)).allSatisfy(d->{assertThat(d.staticEmissionTco2e()).isNull();assertThat(d.staticFactorKgCo2ePerKwh()).isNull();});
    }
    @Test void excludesTodayAndFutureUsingBeijingMidnight() {
        var view=service.query(1,roles,"B","2026-10");
        assertThat(view.days().get(29).electricityStatus()).isEqualTo("AVAILABLE");
        assertThat(view.days().get(30).electricityStatus()).isEqualTo("NOT_ENDED");
        verify(electricity,never()).read(anyLong(),anyCollection(),any(),eq(LocalDate.of(2026,10,31)));
        clearInvocations(electricity,hourly);
        assertThat(service.query(1,roles,"B","2026-11").state()).isEqualTo("NOT_STARTED");
        verifyNoInteractions(electricity,hourly);
    }
    @Test void preparedSummaryPreservesEveryDailyResultAndClosesRequestSources() {
        var source=mock(MonthlyReadingWindow.class);
        when(electricity.prepareMonth(eq(1L),anyCollection(),eq("B"),eq(YearMonth.of(2026,1)),any(),any())).thenReturn(source);
        when(electricity.readPrepared(eq(1L),anyCollection(),eq("B"),any(),eq(source)))
                .thenAnswer(c->quantity(c.getArgument(3)));
        var original=service.query(1,roles,"B","2026-01");
        clearInvocations(electricity,hourly);
        var prepared=service.queryPrepared(1,roles,"B","2026-01");
        assertThat(prepared).isEqualTo(original);
        verify(electricity,times(1)).prepareMonth(eq(1L),anyCollection(),eq("B"),eq(YearMonth.of(2026,1)),eq(LocalDate.of(2026,10,31)),any());
        verify(electricity,times(31)).readPrepared(eq(1L),anyCollection(),eq("B"),any(),eq(source));
        verify(electricity,never()).read(anyLong(),anyCollection(),any(),any());verify(source).close();
    }
    @Test void preparedSummaryDoesNotScanUnendedMonthAndBoundsCurrentMonthPreparation() {
        assertThat(service.queryPrepared(1,roles,"B","2026-11").state()).isEqualTo("NOT_STARTED");
        verifyNoInteractions(electricity,hourly);
        var source=mock(MonthlyReadingWindow.class);
        when(electricity.prepareMonth(anyLong(),anyCollection(),any(),any(),any(),any())).thenReturn(source);
        when(electricity.readPrepared(anyLong(),anyCollection(),any(),any(),eq(source))).thenAnswer(c->quantity(c.getArgument(3)));
        var value=service.queryPrepared(1,roles,"B","2026-10");
        assertThat(value.days().get(30).electricityStatus()).isEqualTo("NOT_ENDED");
        verify(electricity).prepareMonth(eq(1L),anyCollection(),eq("B"),eq(YearMonth.of(2026,10)),eq(LocalDate.of(2026,10,31)),any());
        verify(electricity,never()).readPrepared(anyLong(),anyCollection(),any(),eq(LocalDate.of(2026,10,31)),any());
        verify(source).close();
    }
    @Test void preparedSummaryKeepsDayConflictsIndependentButCleansUpOnInfrastructureFailure() {
        var source=mock(MonthlyReadingWindow.class);
        when(electricity.prepareMonth(anyLong(),anyCollection(),any(),any(),any(),any())).thenReturn(source);
        when(electricity.readPrepared(anyLong(),anyCollection(),any(),any(),eq(source))).thenAnswer(c->quantity(c.getArgument(3)));
        when(electricity.readPrepared(eq(1L),anyCollection(),eq("B"),eq(LocalDate.of(2026,1,2)),eq(source)))
                .thenThrow(new BusinessException(409,"OVERLAP","overlap"));
        var result=service.queryPrepared(1,roles,"B","2026-01");
        assertThat(result.days().get(1).electricityStatus()).isEqualTo("BLOCKED");
        assertThat(result.days().get(2).electricityStatus()).isEqualTo("AVAILABLE");
        assertThat(result.days().get(1).staticCarbonStatus()).isEqualTo("AVAILABLE");verify(source).close();
        clearInvocations(source);
        doThrow(new BusinessException(503,"SOURCE_FAILURE","unavailable")).when(electricity)
                .readPrepared(eq(1L),anyCollection(),eq("B"),eq(LocalDate.of(2026,1,2)),eq(source));
        assertThatThrownBy(()->service.queryPrepared(1,roles,"B","2026-01"))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(503));
        verify(source).close();
    }
    @Test void preparedSourceDatabaseTimeoutIsReportedAsBudgetTimeout() {
        when(electricity.prepareMonth(anyLong(),anyCollection(),any(),any(),any(),any()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("bounded JDBC scan"));
        assertThatThrownBy(()->service.queryPrepared(1,roles,"B","2026-01"))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo("CARBON_CALCULATION_TIMEOUT"));
        verifyNoInteractions(hourly);
    }
    @Test void cancellingPreparedReadClosesSourcesOnTheOwningWorker() throws Exception {
        var source=mock(MonthlyReadingWindow.class);var entered=new CountDownLatch(1);var closed=new CountDownLatch(1);
        var owner=new java.util.concurrent.atomic.AtomicReference<Thread>();
        when(electricity.prepareMonth(anyLong(),anyCollection(),any(),any(),any(),any())).thenAnswer(c->{owner.set(Thread.currentThread());return source;});
        when(electricity.readPrepared(anyLong(),anyCollection(),any(),any(),eq(source))).thenAnswer(c->{
            entered.countDown();try{Thread.sleep(10000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new org.springframework.dao.QueryTimeoutException("cancelled");}
            return quantity(c.getArgument(3));
        });
        doAnswer(c->{assertThat(Thread.currentThread()).isSameAs(owner.get());closed.countDown();return null;}).when(source).close();
        var properties=new CarbonProperties();properties.setDailyTrendQueryTimeout(Duration.ofSeconds(1));
        try(var reader=new DailyElectricityCarbonService(auth,electricity,hourly,properties,Clock.fixed(Instant.parse("2026-10-30T17:00:00Z"),ZoneOffset.UTC));
                var callers=Executors.newVirtualThreadPerTaskExecutor()) {
            var future=callers.submit(()->reader.queryPrepared(1,roles,"B","2026-01"));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->future.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
            assertThat(closed.await(5,TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void deniesBuildingBeforeReadingAndDoesNotSwallowFailures() {
        doThrow(new BusinessException(403,"DENIED","无权限")).when(auth).requireReader(1,roles,"C");
        assertThatThrownBy(()->service.query(1,roles,"C","2026-01")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(electricity,hourly);
        doThrow(new BusinessException(503,"DOWN","失败")).when(electricity).read(anyLong(),anyCollection(),any(),any());
        assertThatThrownBy(()->service.query(1,roles,"B","2026-01")).extracting(e->((BusinessException)e).getCode()).isEqualTo(503);
        doThrow(new BusinessException(409,"RELATION_GAP","缺口")).when(electricity).read(anyLong(),anyCollection(),any(),any());
        var view=service.query(1,roles,"B","2026-01");
        assertThat(view.days().getFirst().electricityReasonCode()).isEqualTo("RELATION_GAP");
        assertThat(view.days().getFirst().staticCarbonStatus()).isEqualTo("AVAILABLE");
    }
    @Test void rejectsInvalidMonthsAndCrossBuildingSources() {
        assertThatThrownBy(()->service.query(1,roles,"B","2026-13")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.query(1,roles,"B","2026-1")).isInstanceOf(BusinessException.class);
        doReturn(quantity(LocalDate.of(2025,1,1))).when(electricity).read(anyLong(),anyCollection(),any(),any());
        assertThatThrownBy(()->service.query(1,roles,"B","2026-01")).extracting(e->((BusinessException)e).getErrorCode()).isEqualTo("DAILY_QUANTITY_SCOPE_MISMATCH");
    }
    private static Instant start(LocalDate date) {return date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();}
    private static QueryView quantity(LocalDate date) {
        return new QueryView("B","classification","relation","KWH",start(date),start(date.plusDays(1)),BigDecimal.ZERO,
                BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"CALCULABLE","SOURCE_LABELLED","BOUNDARY_TOTAL",true,List.of(),List.of(),List.of(),List.of("source"));
    }
    static HourlyCarbonRepository.Stored stored(LocalDate date,String alternate,boolean incomplete) {
        var hours=CarbonEvidence.object().putArray("hours");var points=new ArrayList<HourlyCarbonContracts.Point>();
        for(int hour=0;hour<24;hour++) {
            var calculation=CarbonEvidence.object();
            var factor=calculation.putObject("factor");factor.put("scopeType","SCOPE_2");factor.put("category","PURCHASED_ELECTRICITY_LOCATION");
            factor.put("energyItemCode","ELECTRICITY");factor.put("inputUnitCode","KWH");factor.put("resultBasis","GAS_MASS");factor.put("gasCode","CO2");factor.put("gasCoverage","CO2_ONLY_ELECTRICITY");
            var component=factor.putArray("components").addObject();component.put("type","DIRECT_EMISSION_FACTOR");component.put("unit","KG_CO2/KWH");component.put("value",hour==23?alternate:"0.5600");
            calculation.put("factorVersionId","factor");calculation.putObject("gwp").put("gasCode","CO2");calculation.put("gwpValue","1.000");
            var trace=CarbonEvidence.object();trace.put("hour",hour);trace.put("status","COMPLETE");trace.putArray("calculations").add(calculation);hours.add(trace);
            var from=start(date).plusSeconds(hour*3600L);
            points.add(new HourlyCarbonContracts.Point(hour,from,from.plusSeconds(3600),"COMPLETE",null,null,BigDecimal.ONE,BigDecimal.ONE,
                    List.of("source"),List.of("factor"),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),CarbonCalculationCore.sha256(CarbonEvidence.canonical(trace))));
        }
        var root=CarbonEvidence.object();root.set("hours",hours);
        var view=new HourlyCarbonContracts.View("B",date,"Asia/Shanghai","KG_CO2E","PURCHASED_ELECTRICITY_SCOPE_2","REVIEWED_INTERVAL","SIMULATED","FORMAL","MONITORING",
                incomplete?"PARTIAL":"COMPLETE","batch",2,start(date.plusDays(1)),start(date.plusDays(1)),"source watermark",24,incomplete?23:24,new BigDecimal("1.234567890123456789"),points,start(date.plusDays(1)),"hash");
        return new HourlyCarbonRepository.Stored(view,CarbonEvidence.canonical(root));
    }
    @Test void existingHourlyResultKeepsPriorityAndNeverAddsDayCarbon() {
        service.close();var daily=mock(DailyStaticCarbonService.class);
        service=new DailyElectricityCarbonService(auth,electricity,hourly,new CarbonProperties(),
                Clock.fixed(Instant.parse("2026-10-30T17:00:00Z"),ZoneOffset.UTC),daily);
        var day=service.query(1,roles,"B","2026-01").days().getFirst();
        assertThat(day.staticEmissionTco2e()).isEqualByComparingTo("0.001234567890123456789");
        assertThat(day.staticSource().grain()).isEqualTo("HOUR");
        verifyNoInteractions(daily);
    }

}
