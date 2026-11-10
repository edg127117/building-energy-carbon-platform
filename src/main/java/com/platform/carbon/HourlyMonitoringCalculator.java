package com.platform.carbon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.framework.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.*;
import java.util.*;
import static com.platform.carbon.CarbonModels.*;

/** 同一份受治理电量供静态/动态监测使用；因子缺失不能抹掉合格电量。 */
@Service
class HourlyMonitoringCalculator {
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final HourlyCarbonInputService inputs;
    private final HourlyCarbonRuleService staticRules;
    private final DynamicElectricityRepository dynamic;
    private final CarbonAuthorization authorization;
    private final UsageNature factorUsage;

    HourlyMonitoringCalculator(HourlyCarbonInputService inputs,HourlyCarbonRuleService staticRules,
            DynamicElectricityRepository dynamic,CarbonAuthorization authorization,
            @Value("${hourly-monitoring.factor-usage-nature:FORMAL}") String factorUsage) {
        this.inputs=inputs;this.staticRules=staticRules;this.dynamic=dynamic;this.authorization=authorization;
        this.factorUsage=UsageNature.valueOf(factorUsage);
    }
    record Calculated(HourlyMonitoringContracts.Point point,ObjectNode evidence) { }
    String policy(){return "HOURLY_MONITOR_PRECEDING_SIX_MINUTES_V2:"+factorUsage.name();}
    void authorize(long actor,Collection<String> roles,String building){authorization.requireCalculationRunner(actor,roles,building);}

    Calculated calculate(long actor,Collection<String> roles,String building,Instant from,String sourceKind,Instant asOf) {
        authorization.requireCalculationRunner(actor,roles,building);
        var to=from.plusSeconds(3600);var cutoff=to.isBefore(asOf)?to:asOf;
        int hour=from.atZone(ZONE).getHour();boolean closed=!to.isAfter(asOf);
        var trace=CarbonEvidence.object();trace.put("algorithm","HOURLY_MONITOR_PRECEDING_SIX_MINUTES_V2");
        trace.put("buildingId",building);trace.put("hourStart",from.toString());trace.put("capturedAt",asOf.toString());
        trace.put("sourceKind",sourceKind);trace.put("factorUsageNature",factorUsage.name());
        var deadline=CarbonCalculationDeadline.start(Duration.ofSeconds(30));
        try(var ignored=deadline.bind()) {
            var request=new HourlyCarbonContracts.Request(building,from.atZone(ZONE).toLocalDate(),asOf,
                    "hourly-monitor",sourceKind,"SOURCE_LABELLED","HOURLY_MONITOR",factorUsage.name());
            var capture=new HourlyCarbonInputService.Capture(false,true);
            HourlyCarbonInputService.Input input;
            try {
                input=inputs.read(actor,roles,request,from,cutoff,from.minusSeconds(360),cutoff,capture,deadline);
            } catch(BusinessException e) {
                if(e.getCode()==401||e.getCode()==403||e.getCode()>=500)throw e;
                trace.put("reasonCode",Objects.toString(e.getErrorCode(),"SOURCE_MISSING"));
                return new Calculated(HourlyMonitoringContracts.empty(hour,from,"MISSING",e.getErrorCode()),trace);
            }
            trace.set("input",input.evidence());trace.set("contexts",CarbonEvidence.tree(capture.contexts()));
            trace.set("sourceSnapshots",CarbonEvidence.tree(input.sourceSnapshotIds()));
            var contributions=DynamicElectricityInputReader.hourlyContributions(input);
            var quantity=contributions.stream().map(DynamicElectricityInputReader.Contribution::electricityKwh).reduce(BigDecimal.ZERO,BigDecimal::add);
            Instant actualFrom=from,actualTo=cutoff;
            List<Instant[]> windows=new ArrayList<>();
            for(var contribution:contributions)windows.add(window(input.evidence(),contribution.pointId(),from,cutoff));
            if(!windows.isEmpty()) {
                actualFrom=windows.stream().map(w->w[0]).min(Comparator.naturalOrder()).orElse(from);
                actualTo=windows.stream().map(w->w[1]).max(Comparator.naturalOrder()).orElse(cutoff);
            }
            BigDecimal staticEmission=null,staticFactor=null,dynamicEmission=null,dynamicFactor=null;
            String staticState=closed?"COMPLETE":"NOT_ENDED",dynamicState=closed?"COMPLETE":"ACCUMULATING";
            String reason=null,gas=null;
            var staticEvidence=trace.putArray("staticCalculations");
            if(closed)try {
                BigDecimal total=BigDecimal.ZERO;Set<String> gases=new TreeSet<>();
                for(var activity:input.activities()) {
                    deadline.requireRemaining();
                    // 同一能源内边界可能略有错位；因子必须覆盖所有采用读数，禁止跨版本偷用新因子。
                    var bounds=energyWindow(activity.evidence(),from,cutoff);
                    var sourceHash=CarbonCalculationCore.sha256(CarbonEvidence.canonical(activity.evidence()));
                    var segment=new ActivitySegment(sourceHash,building,PeriodType.HOUR,bounds[0],bounds[1],ZONE.getId(),
                            activity.energyItemCode(),activity.quantityKwh(),"KWH","OBSERVED","COMPLETE",ResultNature.MONITORING,sourceHash,activity.evidence());
                    var value=staticRules.calculate(segment,factorUsage);
                    total=total.add(value.exactKgCo2e());gases.add(value.gasCoverage());staticEvidence.add(value.evidence());
                }
                staticEmission=total.movePointLeft(3);staticFactor=quantity.signum()==0?null:total.divide(quantity,MathContext.DECIMAL128);
                gas=gases.size()==1?gases.iterator().next():"MIXED";
            } catch(RuntimeException e) {
                if(e instanceof BusinessException business&&(business.getCode()==401||business.getCode()==403))throw e;
                staticState=e instanceof BusinessException business&&business.getCode()<500?"MISSING":"FAILED";
                reason=reason(e);trace.put("staticReason",reason);
            }
            Set<String> sourceNatures=new TreeSet<>();sourceNatures.add("SOURCE_LABELLED");
            try {
                var factors=dynamic.factors(building,actualFrom,actualTo).stream().map(DynamicElectricityRepository.FactorRow::factor).toList();
                var dynamicEvidence=trace.putArray("dynamicCalculations");BigDecimal total=BigDecimal.ZERO;
                for(int i=0;i<contributions.size();i++) {
                    deadline.requireRemaining();var bounds=windows.get(i);
                    var applied=DynamicElectricityCore.calculate(List.of(contributions.get(i)),factors,bounds[0],bounds[1]);
                    total=total.add(applied.emissionKgCo2());sourceNatures.addAll(applied.sourceNatures());
                    dynamicEvidence.add(CarbonEvidence.tree(applied));
                }
                trace.set("dynamicFactors",CarbonEvidence.tree(factors));
                dynamicEmission=total.movePointLeft(3);dynamicFactor=quantity.signum()==0?null:total.divide(quantity,MathContext.DECIMAL128);
            } catch(RuntimeException e) {
                if(e instanceof BusinessException business&&(business.getCode()==401||business.getCode()==403))throw e;
                dynamicState=e instanceof BusinessException business&&business.getCode()<500?"MISSING":"FAILED";
                if(reason==null)reason=reason(e);trace.put("dynamicReason",reason(e));
            }
            var point=new HourlyMonitoringContracts.Point(hour,from,to,actualFrom,actualTo,quantity,
                    staticEmission,staticFactor,dynamicEmission,dynamicFactor,closed?"COMPLETE":"ACCUMULATING",
                    staticState,dynamicState,"RAW_CUMULATIVE".equals(sourceKind),gas,List.copyOf(sourceNatures),null,reason);
            trace.set("result",CarbonEvidence.tree(point));
            return new Calculated(point,trace);
        }
    }

    private static String reason(RuntimeException e) {
        return e instanceof BusinessException business?Objects.toString(business.getErrorCode(),"HOURLY_MONITOR_DEPENDENCY"):"HOURLY_MONITOR_DEPENDENCY";
    }

    private static Instant[] window(JsonNode evidence,String point,Instant from,Instant to) {
        for(var energy:evidence.path("energies")) {
            var input=energy.path("pointInputs").path(point);
            if(input.hasNonNull("actualStart")&&input.hasNonNull("actualEnd"))
                return new Instant[]{Instant.parse(input.path("actualStart").asText()),Instant.parse(input.path("actualEnd").asText())};
        }
        return new Instant[]{from,to};
    }
    private static Instant[] energyWindow(JsonNode energy,Instant from,Instant to) {
        Instant first=null,last=null;
        for(var input:energy.path("pointInputs"))if(input.hasNonNull("actualStart")) {
            var a=Instant.parse(input.path("actualStart").asText());var b=Instant.parse(input.path("actualEnd").asText());
            if(first==null||a.isBefore(first))first=a;if(last==null||b.isAfter(last))last=b;
        }
        return new Instant[]{first==null?from:first,last==null?to:last};
    }
}
