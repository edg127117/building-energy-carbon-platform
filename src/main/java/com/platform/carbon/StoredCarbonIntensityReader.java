package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import static com.platform.carbon.CarbonModels.*;
import static com.platform.carbon.CarbonIntensityContracts.*;

/** 只读正式发布强度及冻结证据；气体身份来自当时的因子，不读取今天的因子目录补历史。 */
@Component
@RequiredArgsConstructor
class StoredCarbonIntensityReader {
    private final CarbonCalculationRepository carbon;
    Actual read(String building,String mode,LocalDate start,LocalDate end,ZoneId zone) {
        var batch=carbon.findCurrentFormal(building,"MONTH".equals(mode)?PeriodType.MONTH:PeriodType.YEAR,
                start.atStartOfDay(zone).toInstant(),end.atStartOfDay(zone).toInstant(),zone.getId());
        if(batch==null) return unavailable("NO_COMPLETE_CARBON_RESULT");
        String code="MONTH".equals(mode)?"MONTH_AREA_INTENSITY":"AREA_INTENSITY";
        var matches=carbon.listSummaries(batch.batchId()).stream().filter(s->code.equals(s.metricCode())&&"TOTAL".equals(s.dimensionCode())).toList();
        if(matches.size()!=1) return unavailable(matches.isEmpty()?"MISSING_METRIC":"CONFLICTING_METRICS");
        var metric=matches.getFirst();
        if(!"kgCO2e/m²".equals(metric.unitCode()) || metric.finalValue()==null || metric.finalValue().signum()<0 || metric.unavailableReason()!=null)
            return unavailable("METRIC_UNAVAILABLE_OR_UNIT_MISMATCH");
        String gas="UNKNOWN",boundary="UNKNOWN",area="UNKNOWN";
        var stored=carbon.listEvidence(batch.batchId());
        if(!stored.isEmpty() && stored.size()<=5000 && stored.size()==batch.detailCount()) {
            try {
                JsonNode shared=CarbonEvidence.parse(Optional.ofNullable(carbon.sharedEvidence(batch.batchId())).orElse("{}"));
                List<JsonNode> evidence=new ArrayList<>();
                for(var item:stored) evidence.add(CarbonEvidence.storedWithDictionary(item.json(),item.hash(),shared));
                gas=gasBasis(evidence);
                boolean whole=evidence.stream().allMatch(e->"ENERGY_BILL_CARBON_V1".equals(e.path("upstream").path("contractVersion").asText())
                        && "WHOLE_BUILDING".equals(e.path("upstream").path("billSource").path("source").path("configuration").path("coverage").asText()));
                if(whole) boundary="WHOLE_BUILDING_ENERGY";
                boolean frozenArea=evidence.stream().allMatch(e->{
                    JsonNode denominator=e.path("areaDenominator").path("value");
                    return Objects.equals(metric.denominatorVersionId(),denominator.path("denominatorVersionId").asText())
                            && "BUILDING_AREA".equals(denominator.path("type").asText()) && "M2".equals(denominator.path("unitCode").asText());
                });
                if(frozenArea && metric.denominatorVersionId()!=null) area="BUILDING_AREA";
            } catch(IllegalStateException | IllegalArgumentException e) {
                gas="EVIDENCE_INVALID"; boundary="UNKNOWN"; area="UNKNOWN";
            }
        }
        return new Actual(metric.finalValue(),metric.unitCode(),"AVAILABLE",gas,boundary,area,
                batch.batchId(),metric.evidenceHash(),metric.denominatorVersionId());
    }
    static String gasBasis(List<JsonNode> evidence) {
        boolean co2=true;
        for(var e:evidence) {
            var factor=e.path("factor");
            if("GAS_MASS".equals(factor.path("resultBasis").asText())) {
                if(!"CO2".equals(factor.path("gasCode").asText())
                        || !Set.of("CO2_ONLY_ELECTRICITY","CO2_ONLY_HEAT","CO2_ONLY_FOR_STATIONARY_COMBUSTION").contains(factor.path("gasCoverage").asText())
                        || !"CO2".equals(e.path("gwp").path("gasCode").asText()) || !one(e.path("gwpValue").asText())) return "UNKNOWN";
            } else if("CO2E_DIRECT".equals(factor.path("resultBasis").asText())) co2=false;
            else return "UNKNOWN";
            if("LEGACY_PARTIAL".equals(e.path("traceStatus").asText())) return "UNKNOWN";
        }
        return evidence.isEmpty()?"UNKNOWN":co2?"CO2_ONLY":"GHG_CO2E";
    }
    /** 累计结果的气体身份来自各来源批次的冻结证据，不依赖月强度或今天的因子参数。 */
    String gasBasisForBatches(String building,Collection<String> ids) {
        if(ids==null || ids.isEmpty() || ids.size()>12) return "UNKNOWN";
        boolean allCo2=true;
        try {
            for(String id:new LinkedHashSet<>(ids)) {
                var batch=carbon.findBatch(id);
                if(batch==null || !building.equals(batch.buildingId()) || batch.resultNature()!=ResultNature.FORMAL
                        || !"COMPLETED_COMPLETE".equals(batch.status()) || !Set.of("DIRECT","PUBLISHED").contains(batch.publicationStatus())) return "UNKNOWN";
                var stored=carbon.listEvidence(id);
                if(stored.isEmpty() || stored.size()>5000 || stored.size()!=batch.detailCount()) return "UNKNOWN";
                JsonNode shared=CarbonEvidence.parse(Optional.ofNullable(carbon.sharedEvidence(id)).orElse("{}"));
                List<JsonNode> evidence=new ArrayList<>();
                for(var item:stored) evidence.add(CarbonEvidence.storedWithDictionary(item.json(),item.hash(),shared));
                String basis=gasBasis(evidence);
                if("UNKNOWN".equals(basis)) return basis;
                if(!"CO2_ONLY".equals(basis)) allCo2=false;
            }
            return allCo2?"CO2_ONLY":"GHG_CO2E";
        } catch(IllegalArgumentException | IllegalStateException e) { return "EVIDENCE_INVALID"; }
    }
    private static boolean one(String value) { try { return new BigDecimal(value).compareTo(BigDecimal.ONE)==0; } catch(NumberFormatException e) { return false; } }
    static Actual unavailable(String status) { return new Actual(null,"kgCO2e/m²",status,"UNKNOWN","UNKNOWN","UNKNOWN",null,null,null); }
}
