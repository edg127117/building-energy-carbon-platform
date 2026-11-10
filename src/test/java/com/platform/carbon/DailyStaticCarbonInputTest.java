package com.platform.carbon;

import com.platform.energy.classification.*;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses the normal classification port and real project deduction core; no synthetic hour conversion. */
class DailyStaticCarbonInputTest {
    final CarbonProjectService projects=mock(CarbonProjectService.class);
    final ClassificationService classifications=mock(ClassificationService.class);
    final DailyStaticCarbonInput reader=new DailyStaticCarbonInput(classifications,projects,new CarbonProjectCore());
    final LocalDate date=LocalDate.of(2026,9,1);
    final Instant from=date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),to=from.plusSeconds(86400);
    final Set<String> roles=Set.of("ENERGY_MANAGER");
    ClassificationContracts.Configuration config;
    ClassificationService.QueryProjection preparedProjection;
    @BeforeEach void setup() {
        var project=new CarbonProjectContracts.Configuration("C",List.of(new CarbonProjectContracts.Energy("ELECTRICITY","PURCHASED_ELECTRICITY","ENABLED")),
                List.of(new CarbonProjectContracts.Exclusion("ELECTRICITY",List.of("P218"),"tenant excluded","signed")));
        when(projects.effective("B",from,to)).thenReturn(new CarbonProjectContracts.Version("P","B",1,1,"EFFECTIVE",from,null,1,2L,project));
        config=new ClassificationContracts.Configuration("R",List.of(new ClassificationContracts.Scheme("USE","Use","USAGE","ADDITIVE")),List.of(),List.of(),
                List.of(new ClassificationContracts.BoundaryRule("boundary","MAIN_WITH_SUBMETER_BREAKDOWN","CONFIRMED","evidence","ELECTRICITY","kWh")));
        when(classifications.effectiveVersion(1,roles,"B",from,to,"C")).thenReturn(new ClassificationContracts.VersionView("C","B",1,1,"APPROVED",from,null,1,2L,config,List.of()));
        var slices=new ArrayList<ClassificationCore.SourceSlice>();
        for(int i=0;i<219;i++)slices.add(new ClassificationCore.SourceSlice("P"+i,BigDecimal.ONE,Set.of("P"+i),false,List.of("day-fact-"+i),Map.of()));
        projection("CALCULABLE",new BigDecimal("219"),slices);
    }
    void projection(String status,BigDecimal quantity,List<ClassificationCore.SourceSlice> slices) {
        var view=new ClassificationContracts.QueryView("B","C","R","KWH",from,to,quantity,quantity,BigDecimal.ZERO,BigDecimal.ZERO,
                status,"SOURCE_LABELLED_STATISTICS","BOUNDARY_TOTAL",true,List.of(),List.of(),List.of(),List.of("approved-day-batch"));
        preparedProjection=new ClassificationService.QueryProjection(view,slices,config);
        when(classifications.queryProjection(eq(1L),eq(roles),eq("B"),any())).thenReturn(preparedProjection);
    }
    @Test void keeps219ApprovedSlicesAndAppliesExclusionWithoutInventingHours() {
        var input=reader.read(1,roles,"B",date);
        assertThat(input.quantity()).isEqualByComparingTo("218");assertThat(input.sourceNature()).isEqualTo("SOURCE_LABELLED_STATISTICS");
        assertThat(input.evidence().path("sourceSlices")).hasSize(219);
        assertThat(input.evidence().path("periodType").asText()).isEqualTo("DAY");
        assertThat(input.evidence().path("projectApplied").path("excluded").decimalValue()).isEqualByComparingTo("1");
        var request=org.mockito.ArgumentCaptor.forClass(ClassificationContracts.QueryRequest.class);
        verify(classifications).queryProjection(eq(1L),eq(roles),eq("B"),request.capture());
        assertThat(request.getValue().periodType()).isEqualTo("DAY");assertThat(request.getValue().unit()).isEqualTo("kWh");
        assertThat(request.getValue().startInclusive()).isEqualTo(from);assertThat(request.getValue().endExclusive()).isEqualTo(to);
    }
    @Test void missingOrIncompleteApprovedProjectionCannotBecomeCarbon() {
        projection("PENDING_EXPERT",BigDecimal.ZERO,List.of());
        assertThatThrownBy(()->reader.read(1,roles,"B",date)).isInstanceOf(BusinessException.class);
    }
    @Test void duplicateSlicesAndTotalMismatchFailClosed() {
        var p=new ClassificationCore.SourceSlice("P218",BigDecimal.ONE,Set.of("P218"),false,List.of("fact"),Map.of());
        projection("CALCULABLE",new BigDecimal("2"),List.of(p,p));
        assertThatThrownBy(()->reader.read(1,roles,"B",date)).isInstanceOf(BusinessException.class);
        projection("CALCULABLE",new BigDecimal("9"),List.of(p));
        assertThatThrownBy(()->reader.read(1,roles,"B",date)).isInstanceOf(BusinessException.class);
    }
    @Test void missingProjectCannotBeBypassedByAClassificationTotal() {
        when(projects.effective("B",from,to)).thenReturn(null);
        assertThatThrownBy(()->reader.read(1,roles,"B",date)).isInstanceOf(BusinessException.class);
        verify(classifications,never()).queryProjection(anyLong(),anyCollection(),anyString(),any());
    }
    @Test void preparedTrendReusesVerifiedMonthlyDaySourceInsteadOfReadingManualHistoryAgain() {
        var source=mock(MonthlyReadingWindow.class);
        when(classifications.queryProjectionUsingMonthSource(eq(1L),eq(roles),eq("B"),any(),eq(source))).thenReturn(preparedProjection);
        var input=reader.readPrepared(1,roles,"B",date,source);
        assertThat(input.quantity()).isEqualByComparingTo("218");
        verify(classifications).queryProjectionUsingMonthSource(eq(1L),eq(roles),eq("B"),argThat(q->"DAY".equals(q.periodType())),eq(source));
        verify(classifications,never()).queryProjection(anyLong(),anyCollection(),anyString(),any());
        verifyNoInteractions(source);
    }

}
