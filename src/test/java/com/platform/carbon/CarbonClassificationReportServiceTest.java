package com.platform.carbon;

import com.platform.carbon.CarbonModels.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CarbonClassificationReportServiceTest {
    private CarbonClassificationReportService.Entry entry(String id,String quantity,String emission,boolean overlapping) {
        var item=new StoredCalculationItem(id,"BATCH",id,"ELECTRICITY",ScopeType.SCOPE_2,new BigDecimal(quantity),"KWH","F","FORMULA",null,new BigDecimal(emission),"",id);
        var trace=CarbonEvidence.parse("""
                {"activity":{"startInclusive":"2026-01-01T00:00:00Z"},"upstream":{
                  "classificationConfiguration":{"schemes":[{"code":"U","mode":"%s"}],"nodes":[
                    {"schemeCode":"U","code":"ROOT","name":"全部"},
                    {"schemeCode":"U","code":"A","name":"空调","parentCode":"ROOT"},
                    {"schemeCode":"U","code":"B","name":"一层","parentCode":"ROOT"}]},
                  "sourceSlice":{"classifications":{"U":["A","B"]}}}}
                """.formatted(overlapping?"OVERLAPPING":"ADDITIVE"));
        return new CarbonClassificationReportService.Entry(item,trace);
    }
    @Test void overlappingChildrenDoNotDoubleParentOrAuthority() {
        var result=CarbonClassificationReportService.aggregate("BLD","BATCH","COMPLETED_COMPLETE",List.of(entry("1","100","50",true),entry("2","200","100",true)));
        assertThat(result.totalKgCo2e()).isEqualByComparingTo("150");
        assertThat(result.nodes().stream().filter(n->n.nodeCode().equals("ROOT")).findFirst().orElseThrow().emissionKgCo2e()).isEqualByComparingTo("150");
        assertThat(result.periods()).singleElement().satisfies(p->assertThat(p.emissionKgCo2e()).isEqualByComparingTo("150"));
        assertThat(result.warnings()).isNotEmpty();
    }
    @Test void exclusiveAmbiguityStaysUnallocatedInsteadOfSplitting() {
        var result=CarbonClassificationReportService.aggregate("BLD","BATCH","COMPLETED_COMPLETE",List.of(entry("1","100","50",false)));
        assertThat(result.nodes()).singleElement().satisfies(n->{assertThat(n.nodeCode()).isEqualTo("UNALLOCATED");assertThat(n.emissionKgCo2e()).isEqualByComparingTo("50");});
    }
    @Test void legacyEvidenceDoesNotBorrowCurrentClassification() {
        var e=entry("1","100","50",true);
        var result=CarbonClassificationReportService.aggregate("BLD","BATCH","COMPLETED_COMPLETE",List.of(new CarbonClassificationReportService.Entry(e.item(),CarbonEvidence.object())));
        assertThat(result.classificationComplete()).isFalse();assertThat(result.nodes()).isEmpty();assertThat(result.totalKgCo2e()).isEqualByComparingTo("50");
    }
}
