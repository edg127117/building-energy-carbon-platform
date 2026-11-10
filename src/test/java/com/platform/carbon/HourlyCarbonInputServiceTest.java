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
    }
    private void configure(List<ClassificationCore.Meter> meters) {
        var config=new ClassificationContracts.Configuration("relation-v1",List.of(new ClassificationContracts.Scheme("use","用途","USAGE","ADDITIVE")),
                List.of(new ClassificationContracts.Node("use","all","全部",null)),List.of(),
                List.of(new ClassificationContracts.BoundaryRule("boundary","MAIN_WITH_SUBMETER_BREAKDOWN","CONFIRMED","test","ELECTRICITY","KWH")));
        when(classifications.intervalContext(anyLong(),anyCollection(),eq("TEST"),eq("class-v1"),any(),any(),eq("ELECTRICITY")))
                .thenReturn(new ClassificationService.IntervalContext("class-v1","relation-v1",1,config,meters,Map.of(),null));
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
