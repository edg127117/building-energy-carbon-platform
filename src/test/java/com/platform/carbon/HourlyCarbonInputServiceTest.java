package com.platform.carbon;

import com.platform.energy.activity.EnergyActivityDataContracts.*;
import com.platform.energy.activity.EnergyActivityDataService;
import com.platform.energy.aggregation.*;
import com.platform.energy.aggregation.NativeQuantityAggregationService.*;
import com.platform.energy.catalog.*;
import com.platform.energy.catalog.api.EnergyCatalogContracts.BindingVersionView;
import com.platform.energy.classification.*;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 原生累计、互斥计量树和项目边界使用真实核心；来源为隔离端口替身。 */
class HourlyCarbonInputServiceTest {
    private final CarbonProjectService projects=mock(CarbonProjectService.class);
    private final ClassificationService classifications=mock(ClassificationService.class);
    private final EnergyActivityDataService activities=mock(EnergyActivityDataService.class);
    private final EnergyCatalogService catalog=mock(EnergyCatalogService.class);
    private final EnergyCatalogLookup units=mock(EnergyCatalogLookup.class);
    private final NativeQuantityAggregationService nativeQuantities=spy(new NativeQuantityAggregationService(
            new EnergyAggregationCore(),mock(EnergyAggregationAuthorization.class),mock(EnergyAggregationGovernanceService.class)));
    private HourlyCarbonInputService service;
    private static final Instant START=Instant.parse("2026-01-01T16:00:00Z"),END=START.plusSeconds(86400),ASOF=END.plusSeconds(3600);
    private static final Set<String> ROLES=Set.of("PLATFORM_ADMIN");
    private final HourlyCarbonContracts.Request request=new HourlyCarbonContracts.Request("TEST",LocalDate.of(2026,1,2),ASOF,"key","RAW_CUMULATIVE","SIMULATED","manifest","FORMAL");

    @BeforeEach void setup() {
        service=new HourlyCarbonInputService(projects,new CarbonProjectCore(),classifications,new ClassificationCore(),activities,nativeQuantities,catalog,units);
        var config=new CarbonProjectContracts.Configuration("class-v1",List.of(new CarbonProjectContracts.Energy("ELECTRICITY","PURCHASED_ELECTRICITY","ENABLED")),List.of());
        when(projects.effective(eq("TEST"),any(),any())).thenReturn(new CarbonProjectContracts.Version("project-v1","TEST",1,0,"EFFECTIVE",START,null,1,2L,config));
        var effective=LocalDateTime.of(2025,1,1,0,0);
        when(catalog.effectiveBinding(anyLong(),anyCollection(),eq("TEST"),anyString(),any())).thenAnswer(call->new BindingVersionView("binding-"+call.getArgument(3),"binding-v1",1,"TEST",call.getArgument(3),"point","KWH","ELECTRICITY","item-v1",effective,null,"CONFIRMED","test",0,1L,null,2L,null));
        when(units.findApprovedItem(eq("ELECTRICITY"),any())).thenReturn(Optional.of(new EnergyCatalogLookup.ApprovedEnergyItem("item","ELECTRICITY","item-v1",1,"ELECTRICITY",List.of("PURCHASED_ELECTRICITY"),"TEST","manifest",effective,null)));
        var unit=new EnergyCatalogLookup.ApprovedUnit("kwh","KWH","unit-v1",1,"kWh","ENERGY","KWH",BigDecimal.ONE,"FIXED",null,"TEST","manifest",effective,null);
        when(units.convert(eq(BigDecimal.ONE),anyString(),eq("KWH"),any())).thenReturn(Optional.of(new EnergyCatalogLookup.UnitConversion(BigDecimal.ONE,unit,BigDecimal.ONE,unit)));
        doReturn(new GovernedEvidence(List.of(),List.of())).when(nativeQuantities).governedEvidence(anyLong(),anyCollection(),anyString(),anyString(),any(),any(),any());
        // Existing boundary tests retain the single-point path when a group lacks profile metadata.
        doThrow(new BusinessException(409,"ENERGY_ACTIVITY_POINT_PROFILE_REQUIRED","test profile unavailable"))
                .when(activities).monitoringSnapshots(anyLong(),anyCollection(),anyString(),anyList(),anyLong(),anyLong(),anyLong());
        doAnswer(call->classifications.monitoringIntervalContext(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4),call.getArgument(5),call.getArgument(6)))
                .when(classifications).monitoringIntervalContext(anyLong(),anyCollection(),anyString(),anyString(),any(),any(),anyString(),any());
    }
    private void configure(List<ClassificationCore.Meter> meters) {configure(meters,"MAIN_WITH_SUBMETER_BREAKDOWN");}
    private void configure(List<ClassificationCore.Meter> meters,String mode) {
        var config=new ClassificationContracts.Configuration("relation-v1",List.of(new ClassificationContracts.Scheme("use","用途","USAGE","ADDITIVE")),
                List.of(new ClassificationContracts.Node("use","all","全部",null)),List.of(),
                List.of(new ClassificationContracts.BoundaryRule("boundary",mode,"CONFIRMED","test","ELECTRICITY","KWH")));
        when(classifications.intervalContext(anyLong(),anyCollection(),eq("TEST"),eq("class-v1"),any(),any(),eq("ELECTRICITY")))
                .thenAnswer(call->{
                    assertThat(Duration.between(call.getArgument(4),call.getArgument(5))).isEqualTo(Duration.ofHours(1));
                    return new ClassificationService.IntervalContext("class-v1","relation-v1",1,config,meters,Map.of(),null);
                });
        when(classifications.monitoringIntervalContext(anyLong(),anyCollection(),eq("TEST"),eq("class-v1"),any(),any(),eq("ELECTRICITY")))
                .thenAnswer(call->{
                    assertThat(Duration.between(call.getArgument(4),call.getArgument(5))).isPositive().isLessThanOrEqualTo(Duration.ofHours(1));
                    return new ClassificationService.IntervalContext("class-v1","relation-v1",1,config,meters,Map.of(),null);
                });
    }
    private AggregationActivitySnapshot monitoringSnapshot(String point,long from,long to) {
        return new AggregationActivitySnapshot("TEST",point,"KWH","CUMULATIVE","CONFIRMED",1,from,to,ASOF.toEpochMilli(),ASOF.toEpochMilli(),"POINT_ID_EVENT_TIME","EXTERNAL_APPEND_ONLY",List.of(fact(point,0,0),fact(point,1,point.equals("A")?100:40)));
    }
    private HourlyCarbonInputService.Input monitoringRead(HourlyCarbonInputService.Capture capture) {
        return service.read(1,ROLES,request,START,START.plusSeconds(3600),START.minusSeconds(360),START.plusSeconds(3600),capture,CarbonCalculationDeadline.start(Duration.ofSeconds(30)));
    }
    @Test void bulkMonitoringFactsMatchTheSinglePointGovernedResultExactly() {
        configure(List.of(meter("A",null),meter("B","A")));
        when(activities.aggregationSnapshot(anyLong(),anyCollection(),eq("TEST"),anyString(),anyLong(),anyLong(),anyLong()))
                .thenAnswer(call->monitoringSnapshot(call.getArgument(3),call.getArgument(4),call.getArgument(5)));
        var single=monitoringRead(new HourlyCarbonInputService.Capture(false,true));clearInvocations(activities);
        doAnswer(call->{var result=new HashMap<String,AggregationActivitySnapshot>();
            for(String point:(List<String>)call.getArgument(3))result.put(point,monitoringSnapshot(point,call.getArgument(4),call.getArgument(5)));return result;
        }).when(activities).monitoringSnapshots(anyLong(),anyCollection(),eq("TEST"),anyList(),anyLong(),anyLong(),anyLong());
        var capture=new HourlyCarbonInputService.Capture(false,true);var bulk=monitoringRead(capture);
        assertThat(bulk.activities()).isEqualTo(single.activities());assertThat(bulk.sourceSnapshotIds()).isEqualTo(single.sourceSnapshotIds());
        assertThat(bulk.activities().getFirst().quantityKwh()).isEqualByComparingTo("100");
        verify(activities,never()).aggregationSnapshot(anyLong(),anyCollection(),anyString(),anyString(),anyLong(),anyLong(),anyLong());
        assertThatThrownBy(()->service.read(2,ROLES,request,START,START.plusSeconds(3600),START.minusSeconds(360),START.plusSeconds(3600),capture,CarbonCalculationDeadline.start(Duration.ofSeconds(30)))).isInstanceOf(BusinessException.class);
    }
    @Test void aMissingChildProfileKeepsTheAvailableMainTotalButBulkDependencyFailureDoesNotFallBack() {
        configure(List.of(meter("A",null),meter("B","A")),"MAIN_METER_TOTAL");
        when(activities.aggregationSnapshot(anyLong(),anyCollection(),eq("TEST"),anyString(),anyLong(),anyLong(),anyLong())).thenAnswer(call->{
            if(call.getArgument(3).equals("B"))throw new BusinessException(409,"ENERGY_ACTIVITY_POINT_PROFILE_REQUIRED","child unavailable");
            return monitoringSnapshot(call.getArgument(3),call.getArgument(4),call.getArgument(5));
        });
        assertThat(monitoringRead(new HourlyCarbonInputService.Capture(false,true)).activities().getFirst().quantityKwh()).isEqualByComparingTo("100");
        clearInvocations(activities);
        doThrow(new BusinessException(503,"ENERGY_ACTIVITY_DEPENDENCY_UNAVAILABLE","down"))
                .when(activities).monitoringSnapshots(anyLong(),anyCollection(),anyString(),anyList(),anyLong(),anyLong(),anyLong());
        assertThatThrownBy(()->monitoringRead(new HourlyCarbonInputService.Capture(false,true))).isInstanceOf(BusinessException.class).extracting(e->((BusinessException)e).getCode()).isEqualTo(503);
        verify(activities,never()).aggregationSnapshot(anyLong(),anyCollection(),anyString(),anyString(),anyLong(),anyLong(),anyLong());
    }
    private static ClassificationCore.Meter meter(String id,String parent) {return new ClassificationCore.Meter(id,parent,"boundary",parent==null?"MAIN":"SUB","INBOUND","CONFIRMED");}
    private void raw(Map<String,Integer> increments) {
        when(activities.aggregationSnapshot(anyLong(),anyCollection(),eq("TEST"),anyString(),anyLong(),anyLong(),eq(ASOF.toEpochMilli())))
                .thenAnswer(call->{String point=call.getArgument(3);List<RawActivityDataView> values=new ArrayList<>();
                    for(int i=0;i<=24;i++)values.add(fact(point,i,increments.get(point)*i));
                    return new AggregationActivitySnapshot("TEST",point,"KWH","CUMULATIVE","CONFIRMED",1,START.toEpochMilli(),END.toEpochMilli(),ASOF.toEpochMilli(),ASOF.toEpochMilli(),"POINT_EVENT","GOVERNED",values);
                });
    }
    private static RawActivityDataView fact(String point,int hour,double value) {
        return new RawActivityDataView(point,point,"KWH","ELECTRICITY","PURCHASED_ELECTRICITY","CUMULATIVE","CONFIRMED",1,"SIMULATOR",point,"device",value,
                START.plusSeconds(hour*3600L).toEpochMilli(),END.toEpochMilli(),"Q0",false,"BUILDING",1,1);
    }
    private HourlyCarbonInputService.Input read(int hour,HourlyCarbonInputService.Capture capture) {
        return service.read(1,ROLES,request,START.plusSeconds(hour*3600L),START.plusSeconds((hour+1)*3600L),START,END,capture,CarbonCalculationDeadline.start(Duration.ofSeconds(60)));
    }

    private HourlyCarbonInputService.Input readReviewed(Instant from,Instant to) {
        var hourly=reviewedRequest(ASOF);
        var reading=new ClassificationContracts.Reading("main",from,to,"ELECTRICITY","KWH","PERIOD_TOTAL",null,null,
                new BigDecimal("12"),BigDecimal.ONE,true,"CONFIRMED","manifest","EXACT",null);
        when(classifications.reviewedIntervalReadings(anyLong(),anyCollection(),eq("TEST"),any(),any(),eq(ASOF)))
                .thenReturn(List.of(new ClassificationContracts.ReadingView("boundary-fact","batch",reading,new BigDecimal("12"),1,"APPROVED",null)));
        return service.read(1,ROLES,hourly,from,to,from,to,new HourlyCarbonInputService.Capture(),CarbonCalculationDeadline.start(Duration.ofSeconds(20)));
    }

    private static LocalDateTime local(Instant value) { return LocalDateTime.ofInstant(value,ZoneId.of("Asia/Shanghai")); }
    private static Instant instant(String value) { return LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Shanghai")).toInstant(); }

    @Test void exactHourlyCoverageAcceptsEveryMonthEndAcrossLeapAndCommonYears() {
        var monthEnds=new ArrayList<Instant[]>();
        for(int year=2024;year<=2025;year++)for(int month=1;month<=12;month++) {
            var lastDay=YearMonth.of(year,month).atEndOfMonth();
            var from=lastDay.atTime(23,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
            var to=lastDay.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
            monthEnds.add(new Instant[]{from,to});
        }
        assertThat(monthEnds).hasSize(24);
        for(var bounds:monthEnds) {
            setup();
            configure(List.of(meter("main",null)));
            var from=bounds[0];var to=bounds[1];
            var end=local(to);
            var binding=new BindingVersionView("binding-main","binding-v1",1,"TEST","main","point","KWH","ELECTRICITY","item-v1",local(from),end,"CONFIRMED","test",0,1L,null,2L,null);
            when(catalog.effectiveBinding(anyLong(),anyCollection(),eq("TEST"),eq("main"),any())).thenReturn(binding);
            when(units.findApprovedItem(eq("ELECTRICITY"),any())).thenReturn(Optional.of(
                    new EnergyCatalogLookup.ApprovedEnergyItem("item","ELECTRICITY","item-v1",1,"ELECTRICITY",List.of("PURCHASED_ELECTRICITY"),"TEST","manifest",local(from),end)));
            var inputUnit=new EnergyCatalogLookup.ApprovedUnit("input","KWH","input-v1",1,"kWh","ENERGY","KWH",BigDecimal.ONE,"FIXED",null,"TEST","manifest",local(from),end);
            var targetUnit=new EnergyCatalogLookup.ApprovedUnit("target","KWH","target-v1",1,"kWh","ENERGY","KWH",BigDecimal.ONE,"FIXED",null,"TEST","manifest",local(from),end);
            when(units.convert(eq(BigDecimal.ONE),eq("KWH"),eq("KWH"),any())).thenReturn(Optional.of(
                    new EnergyCatalogLookup.UnitConversion(BigDecimal.ONE,inputUnit,BigDecimal.ONE,targetUnit)));
            var result=readReviewed(from,to);
            assertThat(result.activities().getFirst().quantityKwh()).isEqualByComparingTo("12");
            verify(catalog,never()).effectiveBinding(anyLong(),anyCollection(),anyString(),anyString(),eq(end.minusNanos(1)));
        }
    }

    @Test void aBindingEndingOneNanosecondBeforeHourEndIsRejected() {
        setup();configure(List.of(meter("main",null)));
        var from=instant("2026-01-31T23:00:00");var to=instant("2026-02-01T00:00:00");
        var earlyEnd=local(to).minusNanos(1);
        var binding=new BindingVersionView("binding-main","binding-v1",1,"TEST","main","point","KWH","ELECTRICITY","item-v1",local(from),earlyEnd,"CONFIRMED","test",0,1L,null,2L,null);
        when(catalog.effectiveBinding(anyLong(),anyCollection(),eq("TEST"),eq("main"),any())).thenReturn(binding);
        assertThatThrownBy(()->readReviewed(from,to)).isInstanceOf(BusinessException.class);
    }

    @Test void eitherConversionUnitEndingBeforeHourEndIsRejected() {
        var from=instant("2026-02-28T23:00:00");var to=instant("2026-03-01T00:00:00");
        for(boolean inputExpires:List.of(true,false)) {
            setup();configure(List.of(meter("main",null)));
            var end=local(to);var earlyEnd=end.minusNanos(1);
            var inputUnit=new EnergyCatalogLookup.ApprovedUnit("input","KWH","input-v1",1,"kWh","ENERGY","KWH",BigDecimal.ONE,"FIXED",null,"TEST","manifest",local(from),inputExpires?earlyEnd:end);
            var targetUnit=new EnergyCatalogLookup.ApprovedUnit("target","KWH","target-v1",1,"kWh","ENERGY","KWH",BigDecimal.ONE,"FIXED",null,"TEST","manifest",local(from),inputExpires?end:earlyEnd);
            when(units.convert(eq(BigDecimal.ONE),eq("KWH"),eq("KWH"),any())).thenReturn(Optional.of(
                    new EnergyCatalogLookup.UnitConversion(BigDecimal.ONE,inputUnit,BigDecimal.ONE,targetUnit)));
            assertThatThrownBy(()->readReviewed(from,to)).isInstanceOf(BusinessException.class);
        }
    }

    @Test void nestedSubMetersNeverDoubleCountFloorAndRoomQuantities() {
        configure(List.of(meter("main",null),meter("floor","main"),meter("room1","floor"),meter("room2","floor")));
        raw(Map.of("main",1000,"floor",1000,"room1",600,"room2",400));
        var input=read(0,new HourlyCarbonInputService.Capture());
        assertThat(input.activities()).singleElement().satisfies(a->assertThat(a.quantityKwh()).isEqualByComparingTo("1000"));
        assertThat(input.sourceSnapshotIds()).hasSize(4);assertThat(input.qualityPolicyVersions()).containsExactly("BUILDING:1:1");
        assertThat(input.evidence().path("energies").get(0).path("composition")).isNotEmpty();
    }
    @Test void completeDayOf225MetersReadsEachRawDayOnceAndKeepsAll24Hours() {
        List<ClassificationCore.Meter> meters=new ArrayList<>();Map<String,Integer> values=new HashMap<>();
        meters.add(meter("main",null));values.put("main",225);
        for(int i=1;i<225;i++){String point="p"+i;meters.add(meter(point,"main"));values.put(point,1);}
        configure(meters);raw(values);var capture=new HourlyCarbonInputService.Capture();
        BigDecimal day=BigDecimal.ZERO;
        for(int hour=0;hour<24;hour++) {
            var input=read(hour,capture);assertThat(input.activities().getFirst().quantityKwh()).isEqualByComparingTo("225");
            day=day.add(input.activities().getFirst().quantityKwh());
        }
        assertThat(day).isEqualByComparingTo("5400");
        verify(activities,times(225)).aggregationSnapshot(anyLong(),anyCollection(),anyString(),anyString(),anyLong(),anyLong(),anyLong());
        assertThat(capture.contexts()).hasSize(227);
    }
    @Test void missingOrRejectedExactAnchorCannotBeInterpolated() {
        configure(List.of(meter("main",null)));
        when(activities.aggregationSnapshot(anyLong(),anyCollection(),anyString(),anyString(),anyLong(),anyLong(),anyLong()))
                .thenReturn(new AggregationActivitySnapshot("TEST","main","KWH","CUMULATIVE","CONFIRMED",1,START.toEpochMilli(),END.toEpochMilli(),ASOF.toEpochMilli(),ASOF.toEpochMilli(),"POINT_EVENT","GOVERNED",List.of(fact("main",0,0),fact("main",2,20))));
        assertThatThrownBy(()->read(0,new HourlyCarbonInputService.Capture())).isInstanceOf(BusinessException.class)
                .extracting(ex->((BusinessException)ex).getErrorCode()).isEqualTo(CarbonErrors.ACTIVITY_INCOMPLETE);
    }
    @Test void monitoringUsesPrecedingReadingsWhileFormalCalculationStillRequiresExactAnchors() {
        configure(List.of(meter("main",null)));
        var begin=START.minusSeconds(120);var finish=START.plusSeconds(3480);
        var values=List.of(monitorFact(begin,100),monitorFact(finish,110));
        monitoringRaw(values);
        var result=service.read(1,ROLES,request,START,START.plusSeconds(3600),START.minusSeconds(360),START.plusSeconds(3600),
                new HourlyCarbonInputService.Capture(false,true),CarbonCalculationDeadline.start(Duration.ofSeconds(30)));
        assertThat(result.activities().getFirst().quantityKwh()).isEqualByComparingTo("10");
        assertThat(result.evidence().at("/energies/0/pointInputs/main/actualStart").asText()).isEqualTo(begin.toString());
        assertThatThrownBy(()->service.read(1,ROLES,request,START,START.plusSeconds(3600),START.minusSeconds(360),START.plusSeconds(3600),
                new HourlyCarbonInputService.Capture(),CarbonCalculationDeadline.start(Duration.ofSeconds(30)))).isInstanceOf(BusinessException.class);
    }
    @Test void monitoringCannotSubtractAcrossAnUngovernedCounterReset() {
        configure(List.of(meter("main",null)));
        monitoringRaw(List.of(monitorFact(START.minusSeconds(120),100),monitorFact(START.plusSeconds(120),1),monitorFact(START.plusSeconds(3480),110)));
        assertThatThrownBy(()->service.read(1,ROLES,request,START,START.plusSeconds(3600),START.minusSeconds(360),START.plusSeconds(3600),
                new HourlyCarbonInputService.Capture(false,true),CarbonCalculationDeadline.start(Duration.ofSeconds(30)))).isInstanceOf(BusinessException.class);
    }
    @Test void currentPartialHourUsesMonitoringContextAndNotFormalFullHourPort() {
        configure(List.of(meter("main",null)));
        monitoringRaw(List.of(monitorFact(START,100),monitorFact(START.plusSeconds(1800),110)));
        var result=service.read(1,ROLES,request,START,START.plusSeconds(1900),START.minusSeconds(360),START.plusSeconds(1900),
                new HourlyCarbonInputService.Capture(false,true),CarbonCalculationDeadline.start(Duration.ofSeconds(30)));
        assertThat(result.activities().getFirst().quantityKwh()).isEqualByComparingTo("10");
        verify(classifications,never()).intervalContext(anyLong(),anyCollection(),anyString(),anyString(),any(),any(),anyString());
    }
    @Test void precedingAnchorsLongerThanOneHourValidateBothContextSegments() {
        configure(List.of(meter("main",null)));
        monitoringRaw(List.of(monitorFact(START.minusSeconds(360),100),monitorFact(START.plusSeconds(3600),110)));
        var result=service.read(1,ROLES,request,START,START.plusSeconds(3600),START.minusSeconds(360),START.plusSeconds(3600),
                new HourlyCarbonInputService.Capture(false,true),CarbonCalculationDeadline.start(Duration.ofSeconds(30)));
        assertThat(result.activities().getFirst().quantityKwh()).isEqualByComparingTo("10");
        verify(classifications).monitoringIntervalContext(1,ROLES,"TEST","class-v1",START.plusSeconds(3240),START.plusSeconds(3600),"ELECTRICITY");
    }
    private void monitoringRaw(List<RawActivityDataView> values) {
        when(activities.aggregationSnapshot(anyLong(),anyCollection(),anyString(),anyString(),anyLong(),anyLong(),anyLong())).thenAnswer(call->
                new AggregationActivitySnapshot("TEST","main","KWH","CUMULATIVE","CONFIRMED",1,call.getArgument(4),call.getArgument(5),ASOF.toEpochMilli(),ASOF.toEpochMilli(),"POINT_EVENT","GOVERNED",values));
    }
    private RawActivityDataView monitorFact(Instant at,double value) {
        return new RawActivityDataView("main","main","KWH","ELECTRICITY","PURCHASED_ELECTRICITY","CUMULATIVE","CONFIRMED",1,
                "SIMULATOR","main","device",value,at.toEpochMilli(),END.toEpochMilli(),"Q0",false,"BUILDING",1,1);
    }
    @Test void equalAnchorsRemainTrueZeroAndWrongSourceCutoffIsRejected() {
        configure(List.of(meter("main",null)));raw(Map.of("main",0));
        assertThat(read(0,new HourlyCarbonInputService.Capture()).activities().getFirst().quantityKwh()).isZero();
        when(activities.aggregationSnapshot(anyLong(),anyCollection(),anyString(),anyString(),anyLong(),anyLong(),anyLong()))
                .thenReturn(new AggregationActivitySnapshot("TEST","main","KWH","CUMULATIVE","CONFIRMED",1,START.toEpochMilli(),END.toEpochMilli(),ASOF.toEpochMilli(),ASOF.minusSeconds(1).toEpochMilli(),"POINT_EVENT","GOVERNED",List.of(fact("main",0,0),fact("main",1,10))));
        assertThatThrownBy(()->read(0,new HourlyCarbonInputService.Capture())).isInstanceOf(BusinessException.class);
    }
    @Test void reviewedExactHourlyTotalWorksButDailyIntervalIsNeverProrated() {
        configure(List.of(meter("main",null)));
        var hourly=new HourlyCarbonContracts.Request("TEST",request.date(),ASOF,"manual","REVIEWED_INTERVAL","SIMULATED","manifest","FORMAL");
        var reading=new ClassificationContracts.Reading("main",START,START.plusSeconds(3600),"ELECTRICITY","KWH","PERIOD_TOTAL",null,null,
                new BigDecimal("12"),BigDecimal.ONE,true,"CONFIRMED","manifest","EXACT",null);
        when(classifications.reviewedIntervalReadings(anyLong(),anyCollection(),eq("TEST"),any(),any(),eq(ASOF)))
                .thenReturn(List.of(new ClassificationContracts.ReadingView("fact","batch",reading,new BigDecimal("12"),1,"APPROVED",null)));
        var result=service.read(1,ROLES,hourly,START,START.plusSeconds(3600),START,END,new HourlyCarbonInputService.Capture(),CarbonCalculationDeadline.start(Duration.ofSeconds(20)));
        assertThat(result.activities().getFirst().quantityKwh()).isEqualByComparingTo("12");
        verifyNoInteractions(activities);
        var daily=new ClassificationContracts.Reading("main",START,END,"ELECTRICITY","KWH","PERIOD_TOTAL",null,null,
                new BigDecimal("288"),BigDecimal.ONE,true,"CONFIRMED","manifest","EXACT",null);
        when(classifications.reviewedIntervalReadings(anyLong(),anyCollection(),eq("TEST"),any(),any(),eq(ASOF)))
                .thenReturn(List.of(new ClassificationContracts.ReadingView("daily","batch",daily,new BigDecimal("288"),1,"APPROVED",null)));
        assertThatThrownBy(()->service.read(1,ROLES,hourly,START,START.plusSeconds(3600),START,END,new HourlyCarbonInputService.Capture(),CarbonCalculationDeadline.start(Duration.ofSeconds(20))))
                .isInstanceOf(BusinessException.class).extracting(ex->((BusinessException)ex).getErrorCode()).isEqualTo(CarbonErrors.ACTIVITY_INCOMPLETE);
    }

    @Test void reviewed219PointDayReadsOnceAndKeepsAllHourlyEvidenceIdentical() {
        var meters=new ArrayList<ClassificationCore.Meter>();meters.add(meter("main",null));
        for(int i=1;i<219;i++)meters.add(meter("p"+i,"main"));
        configure(meters);
        var rows=new ArrayList<ClassificationContracts.ReadingView>();
        for(int hour=0;hour<24;hour++)for(var meter:meters)
            rows.add(reviewed(meter.pointId(),START.plusSeconds(hour*3600L),START.plusSeconds((hour+1)*3600L),
                    "main".equals(meter.pointId())?"219":"1"));
        when(classifications.reviewedDayIntervalReadings(eq(1L),eq(ROLES),eq("TEST"),eq(START),eq(END),eq(ASOF),any()))
                .thenReturn(rows);
        when(classifications.reviewedIntervalReadings(eq(1L),eq(ROLES),eq("TEST"),any(),any(),eq(ASOF)))
                .thenAnswer(call->rows.stream().filter(row->row.reading().startInclusive().isBefore(call.getArgument(4))
                        &&row.reading().endExclusive().isAfter(call.getArgument(3))).toList());
        var hourly=reviewedRequest(ASOF);var cached=new HourlyCarbonInputService.Capture(true);
        var original=new HourlyCarbonInputService.Capture();
        for(int hour=0;hour<24;hour++) {
            Instant from=START.plusSeconds(hour*3600L),to=from.plusSeconds(3600);
            var expected=service.read(1,ROLES,hourly,from,to,START,END,original,CarbonCalculationDeadline.start(Duration.ofMinutes(1)));
            var actual=service.read(1,ROLES,hourly,from,to,START,END,cached,CarbonCalculationDeadline.start(Duration.ofMinutes(1)));
            assertThat(CarbonEvidence.canonical(CarbonEvidence.tree(actual))).isEqualTo(CarbonEvidence.canonical(CarbonEvidence.tree(expected)));
            assertThat(actual.activities().getFirst().quantityKwh()).isEqualByComparingTo("219");
        }
        verify(classifications).reviewedDayIntervalReadings(eq(1L),eq(ROLES),eq("TEST"),eq(START),eq(END),eq(ASOF),any());
        verify(classifications,times(24)).reviewedIntervalReadings(eq(1L),eq(ROLES),eq("TEST"),any(),any(),eq(ASOF));
        assertThat(CarbonEvidence.canonical(CarbonEvidence.tree(cached.contexts())))
                .isEqualTo(CarbonEvidence.canonical(CarbonEvidence.tree(original.contexts())));
    }

    @Test void reviewedDayCapturePinsIdentityCutoffAndNaturalDayBeforeAnySourceRead() {
        configure(List.of(meter("main",null)));
        when(classifications.reviewedDayIntervalReadings(anyLong(),anyCollection(),anyString(),any(),any(),any(),any()))
                .thenReturn(List.of(reviewed("main",START,START.plusSeconds(3600),"12")));
        var capture=new HourlyCarbonInputService.Capture(true);
        service.read(1,ROLES,reviewedRequest(ASOF),START,START.plusSeconds(3600),START,END,capture,CarbonCalculationDeadline.start(Duration.ofMinutes(1)));
        clearInvocations(classifications,projects);
        assertThatThrownBy(()->service.read(2,ROLES,reviewedRequest(ASOF),START,START.plusSeconds(3600),START,END,capture,CarbonCalculationDeadline.start(Duration.ofMinutes(1))))
                .hasMessageContaining("不能跨用户");
        assertThatThrownBy(()->service.read(1,Set.of("ENERGY_MANAGER"),reviewedRequest(ASOF),START,START.plusSeconds(3600),START,END,capture,CarbonCalculationDeadline.start(Duration.ofMinutes(1))))
                .hasMessageContaining("不能跨用户");
        var foreign=new HourlyCarbonContracts.Request("OTHER",request.date(),ASOF,"manual","REVIEWED_INTERVAL","SIMULATED","manifest","FORMAL");
        assertThatThrownBy(()->service.read(1,ROLES,foreign,START,START.plusSeconds(3600),START,END,capture,CarbonCalculationDeadline.start(Duration.ofMinutes(1))))
                .hasMessageContaining("不能跨用户");
        assertThatThrownBy(()->service.read(1,ROLES,reviewedRequest(ASOF.plusSeconds(1)),START,START.plusSeconds(3600),START,END,capture,CarbonCalculationDeadline.start(Duration.ofMinutes(1))))
                .hasMessageContaining("不能跨用户");
        assertThatThrownBy(()->service.read(1,ROLES,reviewedRequest(ASOF),END,END.plusSeconds(3600),END,END.plusSeconds(86400),capture,CarbonCalculationDeadline.start(Duration.ofMinutes(1))))
                .hasMessageContaining("不能跨用户");
        verifyNoInteractions(classifications,projects);
    }

    @Test void cachedCrossDayAndMonthFactsStillFailExactHourValidationAndHourlyCountRemainsBounded() {
        configure(List.of(meter("main",null)));
        for(Instant from:List.of(START.minusSeconds(3600),START.minusSeconds(31*86400L))) {
            when(classifications.reviewedDayIntervalReadings(anyLong(),anyCollection(),anyString(),any(),any(),any(),any()))
                    .thenReturn(List.of(reviewed("main",from,END,"288")));
            assertThatThrownBy(()->service.read(1,ROLES,reviewedRequest(ASOF),START,START.plusSeconds(3600),START,END,
                    new HourlyCarbonInputService.Capture(true),CarbonCalculationDeadline.start(Duration.ofMinutes(1))))
                    .isInstanceOf(BusinessException.class).extracting(ex->((BusinessException)ex).getErrorCode()).isEqualTo(CarbonErrors.ACTIVITY_INCOMPLETE);
        }
        var over=new ArrayList<ClassificationContracts.ReadingView>();
        for(int i=0;i<5001;i++)over.add(reviewed("p"+i,START,START.plusSeconds(3600),"1"));
        when(classifications.reviewedDayIntervalReadings(anyLong(),anyCollection(),anyString(),any(),any(),any(),any())).thenReturn(over);
        assertThatThrownBy(()->service.read(1,ROLES,reviewedRequest(ASOF),START,START.plusSeconds(3600),START,END,
                new HourlyCarbonInputService.Capture(true),CarbonCalculationDeadline.start(Duration.ofMinutes(1))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("5000");
    }

    private HourlyCarbonContracts.Request reviewedRequest(Instant asOf) {
        return new HourlyCarbonContracts.Request("TEST",request.date(),asOf,"manual","REVIEWED_INTERVAL","SIMULATED","manifest","FORMAL");
    }

    private static ClassificationContracts.ReadingView reviewed(String point,Instant from,Instant to,String quantity) {
        var reading=new ClassificationContracts.Reading(point,from,to,"ELECTRICITY","KWH","PERIOD_TOTAL",null,null,
                new BigDecimal(quantity),BigDecimal.ONE,true,"CONFIRMED","manifest","EXACT",null);
        return new ClassificationContracts.ReadingView(point+"@"+from,"batch",reading,new BigDecimal(quantity),1,"APPROVED",null);
    }
    @Test void complete225PointDayProducesBoundedFrozenEvidenceThroughTheHourlyCommand() {
        List<ClassificationCore.Meter> meters=new ArrayList<>();Map<String,Integer> values=new HashMap<>();
        meters.add(meter("main",null));values.put("main",225);
        for(int i=1;i<225;i++){String point="p"+i;meters.add(meter(point,"main"));values.put(point,1);}
        configure(meters);raw(values);
        var rules=mock(HourlyCarbonRuleService.class);
        when(rules.calculate(any(),any())).thenAnswer(call->{
            var activity=call.<CarbonModels.ActivitySegment>getArgument(0);
            return new HourlyCarbonRuleService.Calculated(activity.quantity().multiply(new BigDecimal("0.5827")),"factor","formula","gwp","round","CO2_ONLY_ELECTRICITY",CarbonEvidence.object());
        });
        var repository=mock(HourlyCarbonRepository.class);
        when(repository.publish(anyLong(),any(),anyString(),any(),anyString())).thenAnswer(call->{
            String evidence=call.getArgument(4);var view=call.<HourlyCarbonContracts.View>getArgument(3);
            assertThat(evidence.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(16*1024*1024);
            assertThat(CarbonCalculationCore.sha256(evidence)).isEqualTo(view.evidenceHash());
            assertThat(CarbonEvidence.parse(evidence).path("contexts")).hasSize(227);
            return new HourlyCarbonRepository.Stored(HourlyCarbonContracts.identity(view,"day-batch",1),evidence);
        });
        var properties=new CarbonProperties();properties.setCalculationTimeout(Duration.ofSeconds(60));
        var command=new HourlyCarbonService(mock(CarbonAuthorization.class),service,rules,repository,properties);
        var result=command.calculate(1,ROLES,request);
        assertThat(result.availableHours()).isEqualTo(24);assertThat(result.state()).isEqualTo("COMPLETE");
        assertThat(result.dailyTotal()).isEqualByComparingTo("3146.58");
    }
}
