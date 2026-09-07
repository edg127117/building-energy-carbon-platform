package com.platform.energy.efficiency;

import com.platform.energy.period.EnergyPeriodBoundary;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static com.platform.energy.efficiency.EerpContracts.*;

/** 年度先验证精确区间覆盖，再计算总冷量/总电量；比较使用交叉乘法避免显示舍入跨阈值。 */
public final class EerpAnnualCore {
    public record Segment(String taskId, PeriodResult result, Configuration configuration) {}
    public AnnualResult calculate(AnnualRequest request,List<Segment> input,Instant now) {
        var window = EnergyPeriodBoundary.resolve("YEAR",LocalDate.of(request.year(),1,1),request.timezoneId());
        List<Segment> segments = input.stream().sorted(Comparator.comparing(s -> s.result().fromInclusive())).toList();
        List<Issue> issues = new ArrayList<>(); Set<String> rules = new LinkedHashSet<>(), references = new LinkedHashSet<>();
        Instant cursor = window.startInclusive(); BigDecimal cold = BigDecimal.ZERO, electricity = BigDecimal.ZERO;
        boolean hasCold=false, hasElectricity=false;
        if (now.isBefore(window.endExclusive())) issues.add(new Issue("YEAR_NOT_FINISHED",null,window.startInclusive(),window.endExclusive()));
        for (Segment s : segments) {
            var r = s.result(); var c = s.configuration();
            if (!request.buildingId().equals(r.buildingId()) || !request.stationId().equals(r.stationId())
                    || !request.timezoneId().equals(r.timezoneId()) || !request.timezoneVersion().equals(r.timezoneVersion())
                    || r.fromInclusive().isBefore(window.startInclusive()) || r.toExclusive().isAfter(window.endExclusive())
                    || !r.fromInclusive().isBefore(r.toExclusive()))
                throw EerpSupport.error(400,"PERIOD_MISMATCH","年度输入身份、时区或区间不一致");
            if (r.fromInclusive().isBefore(cursor)) throw EerpSupport.error(400,"PERIOD_OVERLAP","年度输入周期重叠");
            if (r.fromInclusive().isAfter(cursor)) issues.add(new Issue("PERIOD_GAP",s.taskId(),cursor,r.fromInclusive()));
            cursor = r.toExclusive();
            if(r.coolingKwh()!=null){cold=cold.add(r.coolingKwh());hasCold=true;}
            if(r.electricityKwh()!=null){electricity=electricity.add(r.electricityKwh());hasElectricity=true;}
            if (!r.complete() || r.coolingKwh()==null || r.electricityKwh()==null) issues.add(new Issue("INPUT_INCOMPLETE",s.taskId(),r.fromInclusive(),r.toExclusive()));
            issues.addAll(r.issues()); rules.add(c.evaluationRuleVersion()); references.add(c.evaluationReference());
        }
        if (cursor.isBefore(window.endExclusive())) issues.add(new Issue("PERIOD_GAP",null,cursor,window.endExclusive()));
        boolean complete = issues.isEmpty();
        if (!hasElectricity) issues.add(new Issue("ELECTRICITY_MISSING",null,window.startInclusive(),window.endExclusive()));
        else if (electricity.signum()<=0) issues.add(new Issue("ZERO_DENOMINATOR",null,window.startInclusive(),window.endExclusive()));
        boolean calculable = complete && hasCold && hasElectricity && electricity.signum()>0;
        BigDecimal ratio = calculable ? cold.divide(electricity,MathContext.DECIMAL128) : null;
        return new AnnualResult(request.buildingId(),request.stationId(),request.year(),request.timezoneId(),request.timezoneVersion(),
                window.startInclusive(),window.endExclusive(),hasCold?cold:null,hasElectricity?electricity:null,ratio,calculable?"CALCULATED":"NOT_CALCULABLE",
                complete?"COMPLETE":"INCOMPLETE",calculable?"DEVELOPMENT_EVALUATED":"NOT_EVALUATED",
                calculable?band(cold,electricity):null,new BigDecimal("4.0"),new BigDecimal("5.0"),List.copyOf(rules),List.copyOf(references),
                "FULL_TEXT_NOT_VERIFIED",segments.stream().map(Segment::taskId).toList(),List.copyOf(issues),"EERP_ANNUAL_RATIO_V1","DEVELOPMENT_SIMULATION");
    }
    public static String band(BigDecimal cold,BigDecimal electricity) {
        EerpSupport.require(electricity.signum()>0,"ZERO_DENOMINATOR","评价分母必须大于零");
        int guidance = cold.compareTo(electricity.multiply(new BigDecimal("4")));
        if (guidance<0) return "BELOW_GUIDANCE";
        if (guidance==0) return "AT_GUIDANCE";
        return cold.compareTo(electricity.multiply(new BigDecimal("5")))>0?"ADVANCED":"GUIDANCE_ONLY";
    }
}
