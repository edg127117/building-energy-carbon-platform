package com.platform.energy.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.energy.classification.ClassificationContracts.*;
import com.platform.energy.classification.ClassificationCore.SourceSlice;
import com.platform.energy.classification.ClassificationService;
import com.platform.energy.conversion.EnergyConversionService;
import com.platform.energy.conversion.api.EnergyConversionContracts.ParameterVersionView;
import com.platform.energy.period.EnergyPeriodAuthorization;
import com.platform.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只验证只读整合契约；分类计量和折标规则本身另有真实核心/H2用例。 */
class EnergyFlowServiceTest {
    final ClassificationService classifications=mock(ClassificationService.class);
    final EnergyConversionService conversion=mock(EnergyConversionService.class);
    final EnergyPeriodAuthorization authorization=mock(EnergyPeriodAuthorization.class);
    final EnergyFlowService service=new EnergyFlowService(authorization,classifications,conversion,new ObjectMapper().findAndRegisterModules());
    static final Set<String> ROLES=Set.of("BUILDING_OWNER");
    static final Instant START=Instant.parse("2026-08-31T16:00:00Z"),END=Instant.parse("2026-09-30T16:00:00Z");
    Configuration config;
    @BeforeEach void setup() {
        config=new Configuration("REL-1",List.of(new Scheme("USE","用途","USAGE","ADDITIVE"),new Scheme("CROSS","交叉","REGION","OVERLAPPING")),
                List.of(new Node("USE","ROOT","用途合计",null),new Node("USE","HVAC","空调","ROOT")),List.of(),
                List.of(new BoundaryRule("BOUNDARY","MAIN_WITH_SUBMETER_BREAKDOWN","CONFIRMED","fixture","ELECTRICITY","kWh")));
        when(classifications.effectiveVersion(1,ROLES,"B",START,END,null)).thenReturn(new VersionView("CLASS-1","B",1,0,"APPROVED",START,null,2,3L,config,List.of()));
        when(conversion.listParameterVersions(ROLES)).thenReturn(List.of(parameter()));
        projection("CALCULABLE", "BOUNDARY_TOTAL",List.of(slice("600","HVAC",false),slice("300","ROOT",false),slice("100",null,true)));
        when(conversion.evaluateFormalRules(eq(1L),eq(ROLES),any(),any())).thenAnswer(call->{
            var request=(com.platform.energy.conversion.api.EnergyConversionContracts.SimulationRequest)call.getArgument(2);
            return new EnergyConversionService.FormalConversion(request.quantity().multiply(new BigDecimal("0.0001229")),"TCE","PARAM-1","FORMULA-1",null,Map.of("quantity",request.quantity()));
        });
    }
    private ParameterVersionView parameter() {
        return new ParameterVersionView("P","TEST_APPROVED","PARAM-1",1,"ELECTRICITY","ITEM-1","F","FORMULA-1","DIRECT_TCE_FACTOR","CALORIFIC_EQUIVALENT",new BigDecimal("0.0001229"),"TCE_PER_INPUT_UNIT",null,"TERMINAL","TEST","PRODUCTION","APPROVED","TEST","isolated",LocalDateTime.of(2025,1,1,0,0),null,0,2,LocalDateTime.of(2025,1,1,0,0),3L,LocalDateTime.of(2025,1,2,0,0));
    }
    private SourceSlice slice(String quantity,String target,boolean residual) {
        return new SourceSlice("P"+quantity,new BigDecimal(quantity),Set.of("M"),residual,List.of("FACT-"+quantity),Map.of("USE",target==null?Set.of():Set.of(target)));
    }
    private void projection(String status,String kind,List<SourceSlice> slices) {
        var view=new QueryView("B","CLASS-1","REL-1","kWh",START,END,new BigDecimal("1000"),new BigDecimal("900"),new BigDecimal("100"),new BigDecimal("100"),status,"SOURCE_LABELLED_STATISTICS",kind,true,List.of(),List.of(),List.of(),List.of("MONTHLY-SOURCES"));
        when(classifications.queryProjection(eq(1L),eq(ROLES),eq("B"),any())).thenReturn(new ClassificationService.QueryProjection(view,slices,config));
    }
    private EnergyFlowContracts.Request request(boolean selected) {
        return new EnergyFlowContracts.Request("B","2026-09",null,selected?List.of(new EnergyFlowContracts.Selection("ELECTRICITY","kWh","PARAM-1")):List.of());
    }
    @Test void requiresExplicitParameterChoiceWithoutGuessingOrRunningCalculations() {
        var result=service.query(1,ROLES,request(false));
        assertThat(result.state()).isEqualTo("SELECTION_REQUIRED");assertThat(result.flows()).isEmpty();
        assertThat(result.conversionOptions()).extracting(EnergyFlowContracts.Option::parameterVersionId).containsExactly("PARAM-1");
        verify(conversion,never()).evaluateFormalRules(anyLong(),anyCollection(),any(),any());
    }
    @Test void convertsMutuallyExclusiveSlicesAndConservesGraphWithoutAddingMainAndChildren() {
        var result=service.query(1,ROLES,request(true));var graph=result.flows().getFirst();
        assertThat(result.state()).isEqualTo("READY");assertThat(result.unit()).isEqualTo("TCE");
        assertThat(result.resultNature()).isEqualTo("SOURCE_LABELLED_STATISTICS");
        assertThat(graph.total()).isEqualByComparingTo("0.1229");assertThat(graph.unallocated()).isEqualByComparingTo("0.01229");
        assertThat(graph.residual()).isEqualByComparingTo("0.01229");
        assertThat(graph.links().stream().filter(l->l.source().startsWith("ENERGY:")).map(EnergyFlowContracts.Link::value).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(graph.total());
        assertThat(graph.links().stream().filter(l->l.target().equals("TOTAL:TOTAL")).map(EnergyFlowContracts.Link::value).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(graph.total());
        assertThat(graph.links()).anySatisfy(l->{assertThat(l.source()).isEqualTo("CLASSIFICATION:ROOT");assertThat(l.target()).isEqualTo("CLASSIFICATION:HVAC");assertThat(l.value()).isEqualByComparingTo("0.07374");});
        assertThat(result.flows().get(1).available()).isFalse();
        assertThat(result.parameterVersionIds()).containsExactly("PARAM-1");assertThat(result.formulaVersionIds()).containsExactly("FORMULA-1");
        assertThat(result.resultVersion()).hasSize(64);
    }
    @Test void missingOrPartialSourcesDoNotBecomeZeroFlow() {
        projection("INCOMPLETE","BOUNDARY_TOTAL",List.of());var result=service.query(1,ROLES,request(true));
        assertThat(result.state()).isEqualTo("INCOMPLETE");assertThat(result.flows()).isEmpty();
        verify(conversion,never()).evaluateFormalRules(anyLong(),anyCollection(),any(),any());
    }
    @Test void rejectsSubtotalNegativeSlicesAndMismatchedVersions() {
        projection("CALCULABLE","MEASURED_SUBTOTAL",List.of(slice("1000","HVAC",false)));
        assertThatThrownBy(()->service.query(1,ROLES,request(true))).isInstanceOf(BusinessException.class).hasMessageContaining("小计");
        projection("CALCULABLE","BOUNDARY_TOTAL",List.of(slice("-1","HVAC",false)));
        assertThatThrownBy(()->service.query(1,ROLES,request(true))).hasMessageContaining("负余量");
        projection("CALCULABLE","BOUNDARY_TOTAL",List.of(slice("1000","HVAC",false)));
        when(conversion.evaluateFormalRules(anyLong(),anyCollection(),any(),any())).thenReturn(new EnergyConversionService.FormalConversion(BigDecimal.ONE,"TCE","OTHER","F",null,Map.of()));
        assertThatThrownBy(()->service.query(1,ROLES,request(true))).hasMessageContaining("版本");
    }
    @Test void buildingScopeAndCalendarAreValidatedBeforeLoadingSources() {
        doThrow(new BusinessException(403,"forbidden")).when(authorization).checkBuilding(1,ROLES,"OTHER");
        assertThatThrownBy(()->service.query(1,ROLES,new EnergyFlowContracts.Request("OTHER","2026-09",null,List.of()))).hasMessage("forbidden");
        assertThatThrownBy(()->service.query(1,ROLES,new EnergyFlowContracts.Request("B","2026-13",null,List.of()))).hasMessageContaining("YYYY-MM");
        verify(classifications,never()).effectiveVersion(anyLong(),anyCollection(),eq("OTHER"),any(),any(),any());
    }
}
