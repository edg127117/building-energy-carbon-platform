package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.energy.classification.ClassificationContracts.*;

import com.platform.energy.classification.ClassificationCore;
import com.platform.energy.conversion.EnergyConversionService;
import com.platform.energy.conversion.api.EnergyConversionContracts.ParameterVersionView;
import com.platform.energy.period.EnergyPeriodAuthorization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.platform.carbon.CarbonModels.*;
import static com.platform.carbon.JointFlowContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 隔离固定证据替身验证通用查询；不连接真实数据库或伪称现场设备验收。 */
class JointFlowServiceTest {
    private final EnergyPeriodAuthorization authorization=mock(EnergyPeriodAuthorization.class);
    private final CarbonCalculationRepository repository=mock(CarbonCalculationRepository.class);
    private final JointFlowConfigurationService configurations=mock(JointFlowConfigurationService.class);
    private final EnergyConversionService conversion=mock(EnergyConversionService.class);
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private final JointFlowService service=new JointFlowService(authorization,repository,configurations,conversion,mapper,new ClassificationCore());
    private final List<String> roles=List.of("ENERGY_MANAGER");
    private final List<JsonNode> frozen=new ArrayList<>();
    private Request request(String mapping,List<Conversion> selections) { if(!selections.isEmpty())display("FROZEN",null,selections); return new Request("OTHER_BUILDING","MONTH","2024-01",null,mapping); }

    @BeforeEach void setup() {
        when(conversion.listParameterVersions(roles)).thenReturn(List.of());
        display("FROZEN",null,List.of());
        month(YearMonth.of(2024,1),List.of("0","100.0000000000000000001"),List.of("0","58.2700000000000000001"));
    }
    private void month(YearMonth month,List<String> quantities,List<String> emissions) {
        var from=month.atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        var to=month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        var batch=mock(CalculationBatch.class); String id="batch-"+month;
        when(batch.batchId()).thenReturn(id);when(batch.buildingId()).thenReturn("OTHER_BUILDING");
        when(batch.periodType()).thenReturn(PeriodType.MONTH);when(batch.periodStart()).thenReturn(from);when(batch.periodEnd()).thenReturn(to);
        when(batch.timezoneId()).thenReturn("Asia/Shanghai");when(batch.resultNature()).thenReturn(ResultNature.FORMAL);
        when(batch.publicationStatus()).thenReturn("DIRECT");when(batch.status()).thenReturn("COMPLETED_COMPLETE");
        when(batch.detailCount()).thenReturn(quantities.size());when(batch.roundingPolicyVersionId()).thenReturn("ROUND");
        List<StoredCalculationItem> items=new ArrayList<>(); List<CarbonCalculationRepository.StoredEvidence> evidence=new ArrayList<>();
        for (int i=0;i<quantities.size();i++) {
            var tree=CarbonEvidence.parse("""
                {"schemaVersion":1,"rawKgCO2e":"%s","factorVersionId":"FACTOR","formulaVersionId":"FORMULA",
                "activity":{"snapshotId":"snapshot-%s","buildingId":"OTHER_BUILDING","energyItemCode":"CUSTOM_POWER","quantity":"%s","unitCode":"KWH","startInclusive":"%s","endExclusive":"%s"},
                "upstream":{"projectVersion":{"configuration":{"classificationVersionId":"OLD"}},
                "classificationConfiguration":{"relationVersionId":"REL","schemes":[
                {"code":"PURPOSE","dimension":"USAGE","mode":"ADDITIVE"},{"code":"SPACE","dimension":"SPACE","mode":"ADDITIVE"}],
                "nodes":[{"schemeCode":"PURPOSE","code":"MIXED","name":"原用途"},{"schemeCode":"SPACE","code":"F8","name":"八层"}]},
                "sourceSlice":{"pointId":"P%s","quantity":"%s","residual":false,"classifications":{"PURPOSE":["MIXED"],"SPACE":["F8"]}}}}
                """.formatted(emissions.get(i),i,quantities.get(i),from,to,i,quantities.get(i)));
            frozen.add(tree); String hash=CarbonCalculationCore.sha256(CarbonEvidence.canonical(tree)); String item="item-"+i;
            items.add(new StoredCalculationItem(item,id,"snapshot-"+i,"CUSTOM_POWER",ScopeType.SCOPE_2,new BigDecimal(quantities.get(i)),"KWH","FACTOR","FORMULA",null,new BigDecimal(emissions.get(i)),"",hash));
            evidence.add(new CarbonCalculationRepository.StoredEvidence(item,tree.toString(),hash));
        }
        var summary=new SummaryMetric("TOTAL_EMISSION","TOTAL",null,new BigDecimal("0.05827"),"tCO2e",null,null,"SUM_HASH");
        when(repository.findJointFlowMonth("OTHER_BUILDING",from,to)).thenReturn(List.of(batch));
        when(repository.detail(id)).thenReturn(new CalculationDetail(batch,items,List.of(),List.of(summary)));
        when(repository.listEvidence(id)).thenReturn(evidence);
    }
    private VersionView mapping(String status,String floor) {
        var schemes=List.of(new Scheme("PURPOSE","用途","USAGE","ADDITIVE"),new Scheme("SPACE","楼层","SPACE","ADDITIVE"));
        var nodes=List.of(new com.platform.energy.classification.ClassificationContracts.Node("PURPOSE","CORRIDOR","过道",null),
                new com.platform.energy.classification.ClassificationContracts.Node("SPACE",floor,"八层",null));
        var bindings=new ArrayList<Binding>();
        for(int i=0;i<2;i++) {
            bindings.add(new Binding("PURPOSE","CORRIDOR","POINT","P"+i,"P"+i,"METERING","CONFIRMED","用户已确认过道"));
            bindings.add(new Binding("SPACE",floor,"POINT","P"+i,"P"+i,"METERING","CONFIRMED","空间证据"));
        }
        display("NEW",new Configuration("REL",schemes,nodes,bindings,List.of()),List.of());
        var view=configurations.resolve(7,roles,"OTHER_BUILDING","NEW",false);
        if(!"APPROVED".equals(status))when(configurations.resolve(7,roles,"OTHER_BUILDING","NEW",false)).thenThrow(CarbonErrors.error(409,CarbonErrors.STATUS_CONFLICT,"须已批准"));
        return new VersionView("NEW","OTHER_BUILDING",2,4,status,Instant.now(),null,1,2L,new Configuration("REL",schemes,nodes,bindings,List.of()),List.of());
    }
    private void display(String id,Configuration mapping,List<Conversion> selections) {
        var dimensions=List.of(new JointFlowConfigurationContracts.Dimension("USAGE","用途",mapping==null?"FROZEN_SCHEME":"MAPPED_SCHEME","PURPOSE",null,List.of()),
            new JointFlowConfigurationContracts.Dimension("FLOOR","楼层",mapping==null?"FROZEN_SCHEME":"MAPPED_SCHEME","SPACE","FLOOR",List.of()));
        var c=new JointFlowConfigurationContracts.Configuration(1,Instant.parse("2020-01-01T00:00:00Z"),Instant.parse("2030-01-01T00:00:00Z"),List.of("REL"),dimensions,mapping==null?List.of():List.of(mapping),selections,"夹具证据",List.of(new JointFlowConfigurationContracts.FloorReference("REL","SPACE","F8","SPACE_F8"),new JointFlowConfigurationContracts.FloorReference("REL","SPACE","F9","SPACE_F9")));
        var version=new JointFlowConfigurationContracts.Version(id,"OTHER_BUILDING",1,1,"APPROVED",c,"a".repeat(64),1,2L);
        when(configurations.resolve(eq(7L),eq(roles),eq("OTHER_BUILDING"),any(),eq(false))).thenReturn(version);
    }
    @Test void preservesRawPrecisionZeroAndBothStagesWithoutDoublingBuildingTotal() {
        var result=service.query(7,roles,request(null,List.of()));
        assertThat(result.carbon().total()).isEqualByComparingTo("0.0582700000000000000001");
        assertThat(result.sources().getFirst().publishedCarbonTons()).isEqualByComparingTo("0.05827");
        assertThat(result.carbon().nodes()).hasSize(3);
        assertThat(result.carbon().links()).hasSize(2).allSatisfy(link->assertThat(link.value()).isEqualByComparingTo(result.carbon().total()));
        assertThat(result.classificationBasis()).isEqualTo("CONFIGURED_VIEW");
        assertThat(result.sources().getFirst().sourceNature()).isEqualTo("UNDETERMINED");
        assertThat(result.energy().state()).isEqualTo("SELECTION_REQUIRED");
        verify(authorization).checkBuilding(7,roles,"OTHER_BUILDING");
    }
    @Test void explicitApprovedNewMappingOnlyChangesDisplayAndKeepsOriginalEvidence() {
        mapping("APPROVED","F8"); String before=frozen.toString();
        var result=service.query(7,roles,request("NEW",List.of()));
        assertThat(result.classificationBasis()).isEqualTo("CONFIGURED_VIEW");
        assertThat(result.carbon().nodes()).anyMatch(node->node.name().equals("过道"));
        assertThat(result.sources().getFirst().classificationVersionId()).isEqualTo("OLD");
        assertThat(result.configurationVersionId()).isEqualTo("NEW");
        assertThat(result.configurationHash()).hasSize(64);assertThat(result.resultVersion()).hasSize(64);
        assertThat(frozen.toString()).isEqualTo(before);
        verify(configurations,atLeastOnce()).resolve(7,roles,"OTHER_BUILDING","NEW",false);
    }
    @Test void rejectsDraftMappingAndMovingFrozenFloor() {
        mapping("DRAFT","F8");assertThatThrownBy(()->service.query(7,roles,request("NEW",List.of()))).hasMessageContaining("已批准");
        mapping("APPROVED","F9");assertThatThrownBy(()->service.query(7,roles,request("NEW",List.of()))).hasMessageContaining("迁移冻结楼层");
    }
    @Test void residualCannotInheritPurposeButKeepsItsKnownFrozenFloor() {
        mapping("APPROVED","F8");
        changeProof(0,tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("upstream").path("sourceSlice")).put("residual",true));
        var result=service.query(7,roles,request("NEW",List.of()));
        assertThat(result.state()).isEqualTo("PARTIAL");
        assertThat(result.carbon().nodes().stream().filter(n->n.depth()==1).map(JointFlowContracts.Node::code)).containsExactly("F8");
        assertThat(result.carbon().total()).isEqualByComparingTo("0.0582700000000000000001");
    }
    @Test void missingAndConflictingMonthsNeverBecomeZeroOrSilentlyTruncatedYear() {
        var january=YearMonth.of(2024,1);
        var result=service.query(7,roles,new Request("OTHER_BUILDING","YEAR","2024","2024-02",null));
        assertThat(result.missingMonths()).containsExactly("2024-02");assertThat(result.carbon().total()).isNull();assertThat(result.state()).isEqualTo("INCOMPLETE");
        var from=january.atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(); var to=january.plusMonths(1).atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        when(repository.findJointFlowMonth("OTHER_BUILDING",from,to)).thenReturn(List.of(mock(CalculationBatch.class),mock(CalculationBatch.class)));
        assertThatThrownBy(()->service.query(7,roles,request(null,List.of()))).hasMessageContaining("多个有效");
    }
    @Test void rejectsBuildingScopeBeforeAnySourceReadAndChecksYearBoundary() {
        doThrow(CarbonErrors.error(403,CarbonErrors.FORBIDDEN,"拒绝建筑")).when(authorization).checkBuilding(8,roles,"OTHER_BUILDING");
        assertThatThrownBy(()->service.query(8,roles,request(null,List.of()))).hasMessageContaining("拒绝建筑");
        verify(repository,never()).findJointFlowMonth(anyString(),isNull(),isNull());
        assertThatThrownBy(()->service.query(7,roles,new Request("OTHER_BUILDING","YEAR","2024","2025-01",null))).hasMessageContaining("截止月");
    }
    @Test void convertsEachFrozenActivityWithExplicitApprovedRuleWithoutAssumingFrozenTce() {
        var parameter=mock(ParameterVersionView.class);
        when(parameter.status()).thenReturn("APPROVED");when(parameter.usageScope()).thenReturn("PRODUCTION");
        when(parameter.energyItemCode()).thenReturn("CUSTOM_POWER");when(parameter.versionId()).thenReturn("P");
        when(parameter.method()).thenReturn("DIRECT_TCE_FACTOR");when(parameter.perspective()).thenReturn("CALORIFIC_EQUIVALENT");
        when(parameter.consumptionScope()).thenReturn("PURCHASED_ELECTRICITY");when(parameter.regionCode()).thenReturn("GLOBAL");
        when(parameter.effectiveFrom()).thenReturn(LocalDateTime.of(2023,1,1,0,0));
        when(conversion.listParameterVersions(roles)).thenReturn(List.of(parameter));
        when(conversion.evaluateFormalRulesBatch(eq(7L),eq(roles),any(),any(),any())).thenReturn(List.of(
                new EnergyConversionService.FormalConversion(BigDecimal.ZERO,"TCE","P","F",null,Map.of()),
                new EnergyConversionService.FormalConversion(new BigDecimal("0.01229"),"TCE","P","F",null,Map.of())));
        var result=service.query(7,roles,request(null,List.of(new Conversion("CUSTOM_POWER","KWH","P"))));
        assertThat(result.energy().total()).isEqualByComparingTo("0.01229");assertThat(result.parameterVersionIds()).containsExactly("P");
        verify(conversion).evaluateFormalRulesBatch(eq(7L),eq(roles),argThat(r->r.energyItemCode().equals("CUSTOM_POWER")),any(),
                eq(List.of(new BigDecimal("0"),new BigDecimal("100.0000000000000000001"))));
    }
    @Test void tamperedOrMissingFrozenProofIsRejected() {
        when(repository.listEvidence("batch-2024-01")).thenReturn(List.of());
        assertThatThrownBy(()->service.query(7,roles,request(null,List.of()))).hasMessageContaining("证据不完整");
    }
    @Test void rejectsOverlappingPointAndWholeParentWithChildButAllowsExplicitResidual() {
        changeProof(1,tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("upstream").path("sourceSlice")).put("pointId","P0"));
        assertThatThrownBy(()->service.query(7,roles,request(null,List.of()))).hasMessageContaining("期间重叠");
        setup();
        changeProof(1,tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("upstream").path("sourceSlice")).putArray("ancestors").add("P0").add("P1"));
        assertThatThrownBy(()->service.query(7,roles,request(null,List.of()))).hasMessageContaining("总表与分表");
        changeProof(0,tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("upstream").path("sourceSlice")).put("residual",true));
        assertThat(service.query(7,roles,request(null,List.of())).carbon().total()).isEqualByComparingTo("0.0582700000000000000001");
    }
    @Test void rejectsAValidHashBoundToTheWrongEnergyIdentity() {
        changeProof(1,tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("activity")).put("energyItemCode","OTHER_ENERGY"));
        assertThatThrownBy(()->service.query(7,roles,request(null,List.of()))).hasMessageContaining("规则身份不一致");
    }
    private void changeProof(int index,java.util.function.Consumer<JsonNode> change) {
        var detail=repository.detail("batch-2024-01");
        var rows=new ArrayList<>(repository.listEvidence("batch-2024-01"));
        var items=new ArrayList<>(detail.items());var item=items.get(index);
        JsonNode tree=CarbonEvidence.parse(rows.get(index).json());change.accept(tree);
        String hash=CarbonCalculationCore.sha256(CarbonEvidence.canonical(tree));
        rows.set(index,new CarbonCalculationRepository.StoredEvidence(item.calculationItemId(),tree.toString(),hash));
        items.set(index,new StoredCalculationItem(item.calculationItemId(),item.batchId(),item.snapshotId(),item.energyItemCode(),item.scopeType(),item.activityQuantity(),item.activityUnitCode(),item.factorVersionId(),item.formulaVersionId(),item.gwpVersionId(),item.emissionKgCo2e(),item.matchReason(),hash));
        when(repository.detail("batch-2024-01")).thenReturn(new CalculationDetail(detail.batch(),items,detail.failures(),detail.summaries()));
        when(repository.listEvidence("batch-2024-01")).thenReturn(rows);
    }
}
