package com.platform.carbon;

import com.platform.carbon.CarbonModels.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CarbonClassificationFlowTest {
    @Test void additiveFlowConservesTotalInsteadOfAddingAncestorsAndLeaves() {
        var graph=CarbonClassificationFlow.build(List.of(entry("P1","100","50","ADDITIVE",false),entry("P1","200","100","ADDITIVE",false))).getFirst();
        assertThat(graph.available()).isTrue();
        assertThat(graph.links().stream().filter(l->l.source().equals("TOTAL:TOTAL")).map(CarbonClassificationFlow.Link::value).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("150");
        assertThat(graph.links()).allSatisfy(link->assertThat(link.value()).isEqualByComparingTo("150"));
    }

    @Test void overlappingClassificationDoesNotBecomeDoubleSizedSankeyFlow() {
        var graph=CarbonClassificationFlow.build(List.of(entry("P1","100","50","OVERLAPPING",false))).getFirst();
        assertThat(graph.available()).isFalse();assertThat(graph.links()).isEmpty();assertThat(graph.unavailableReason()).contains("交叉");
    }

    @Test void invertedHistoricalPathsRemainSeparateVersionsRatherThanFormingCycle() {
        var graph=CarbonClassificationFlow.build(List.of(entry("P1","100","50","ADDITIVE",false),entry("P2","100","50","ADDITIVE",true))).getFirst();
        assertThat(graph.available()).isTrue();
        assertThat(graph.nodes().stream().filter(n->n.type().equals("CLASSIFICATION"))).hasSize(4);
        Set<String> seen=new HashSet<>();
        visit("TOTAL:TOTAL",graph.links(),new HashSet<>(),seen);
        assertThat(seen).hasSize(graph.nodes().size());
    }

    @Test void mixedLegacyEvidenceDoesNotPresentClassificationSubtotalAsBuildingTotal() {
        var current = entry("P1", "100", "50", "ADDITIVE", false);
        var legacy = new CarbonClassificationReportService.Entry(current.item(), CarbonEvidence.parse("{\"rawKgCO2e\":\"70\"}"));
        var graph = CarbonClassificationFlow.build(List.of(current, legacy)).getFirst();
        assertThat(graph.available()).isFalse();
        assertThat(graph.links()).isEmpty();
        assertThat(graph.unavailableReason()).contains("小计");
    }

    private void visit(String id,List<CarbonClassificationFlow.Link> edges,Set<String> path,Set<String> seen) {
        assertThat(path.add(id)).isTrue();seen.add(id);
        for(var edge:edges)if(edge.source().equals(id))visit(edge.target(),edges,path,seen);
        path.remove(id);
    }

    private CarbonClassificationReportService.Entry entry(String version,String quantity,String emission,String mode,boolean invert) {
        String root=invert?"B":"A",leaf=invert?"A":"B";
        var evidence=CarbonEvidence.parse("""
                {"rawKgCO2e":"%s","upstream":{"projectVersion":{"configuration":{"classificationVersionId":"%s"}},
                  "sourceSlice":{"classifications":{"U":["%s"]}},"classificationConfiguration":{
                  "schemes":[{"code":"U","mode":"%s"}],"nodes":[{"schemeCode":"U","code":"%s","name":"父项"},
                  {"schemeCode":"U","code":"%s","name":"子项","parentCode":"%s"}]}}}
                """.formatted(emission,version,leaf,mode,root,leaf,root));
        var item=new StoredCalculationItem("ITEM","BATCH","SOURCE","CUSTOM_ELEC",ScopeType.SCOPE_2,new BigDecimal(quantity),"KWH","F","FORMULA",null,new BigDecimal(emission),"","hash");
        return new CarbonClassificationReportService.Entry(item,evidence);
    }
}
