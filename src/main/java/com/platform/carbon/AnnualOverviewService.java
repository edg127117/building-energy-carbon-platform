package com.platform.carbon;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.platform.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import static com.platform.carbon.CarbonModels.*;

/** 只读概览：正式年报保持冻结；累计量来自月度发布结果，累计强度采用当前获批的历史面积期间。 */
@Service
@RequiredArgsConstructor
public class AnnualOverviewService {
    private final CarbonAuthorization authorization;
    private final CarbonCalculationRepository carbon;
    private final CarbonRuleRepository rules;
    private final MonthlyComparisonService monthly;
    public record Source(LocalDate month, String batchId, String evidenceHash, String versionId) { }
    public record Metric(String code, String unit, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal value,
                         String status, List<LocalDate> missingMonths, List<Source> sources) { }
    public record AreaPart(String versionId, LocalDate from, LocalDate to,
                           @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal area,
                           String sourceReference, String evidenceReference) { }
    public record Period(LocalDate start, LocalDate endExclusive, Instant startInclusive, Instant endInstantExclusive,
                         List<Metric> metrics, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal averageArea,
                         String areaStatus, List<AreaPart> areaParts) { }
    public record Comparison(String code, MonthlyComparisonService.Change yearOnYear) { }
    public record Response(String buildingId, int year, String mode, int throughMonth, String timezoneId,
                           String algorithmVersion, String evidenceHash, Period current, Period samePeriodLastYear,
                           List<Comparison> comparisons) { }

    // 同一次查询固定读取视图，避免发布替代或面积换版发生在两个指标读取之间。
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Response overview(long user, Collection<String> roles, String building, int year,
                             String mode, Integer throughMonth, String timezoneId) {
        if (building==null || building.isBlank() || building.length()>32 || year<2000 || year>2200
                || !Set.of("FORMAL_YEAR","MONTHLY_YTD").contains(mode==null?"":mode)
                || ("MONTHLY_YTD".equals(mode) && (throughMonth==null || throughMonth<1 || throughMonth>12))
                || ("FORMAL_YEAR".equals(mode) && throughMonth!=null)) throw invalid();
        ZoneId zone;
        try { zone=ZoneId.of(timezoneId); } catch (DateTimeException | NullPointerException e) { throw invalid(); }
        authorization.requireReader(user,roles,building);
        int cutoff="FORMAL_YEAR".equals(mode)?12:throughMonth;
        Period current=period(user,roles,building,year,cutoff,mode,zone);
        Period previous=period(user,roles,building,year-1,cutoff,mode,zone);
        var comparisons=new ArrayList<Comparison>();
        for (int i=0;i<current.metrics().size();i++) {
            Metric a=current.metrics().get(i),b=previous.metrics().get(i);
            comparisons.add(new Comparison(a.code(),MonthlyComparisonService.change(value(a),value(b))));
        }
        String algorithm="ANNUAL_OVERVIEW_AREA_DAY_V1";
        return new Response(building,year,mode,cutoff,zone.getId(),algorithm,
                CarbonCalculationCore.sha256(CarbonEvidence.canonical(CarbonEvidence.tree(List.of(algorithm,building,mode,zone.getId(),current,previous)))),
                current,previous,List.copyOf(comparisons));
    }
    private Period period(long user,Collection<String> roles,String building,int year,int cutoff,String mode,ZoneId zone) {
        LocalDate start=LocalDate.of(year,1,1), end=start.plusMonths(cutoff);
        Instant from=start.atStartOfDay(zone).toInstant(),to=end.atStartOfDay(zone).toInstant();
        if ("FORMAL_YEAR".equals(mode)) {
            var batch=carbon.findCurrentFormal(building,PeriodType.YEAR,from,to,zone.getId());
            var summaries=batch==null?List.<SummaryMetric>of():carbon.listSummaries(batch.batchId());
            List<Metric> metrics=new ArrayList<>();
            String[] codes={"TOTAL_EMISSION","AREA_INTENSITY","POPULATION_INTENSITY"};
            String[] units={"tCO2e","kgCO2e/m²","kgCO2e/人"};
            for(int i=0;i<codes.length;i++) metrics.add(frozen(codes[i],units[i],summaries,batch,start));
            return new Period(start,end,from,to,List.copyOf(metrics),null,"FROZEN_YEAR_RESULT",List.of());
        }
        String[] codes={"ELECTRICITY_KWH","ELECTRICITY_TCE","TOTAL_EMISSION"};
        String[] units={"kWh","tce","tCO2e"};
        List<Metric> totals=new ArrayList<>();
        var periods=new ArrayList<MonthlyComparisonService.Period>();
        for(LocalDate m=start;m.isBefore(end);m=m.plusMonths(1)) periods.add(monthly.period(user,roles,building,m,zone));
        BigDecimal rawEmission=null;
        for(int i=0;i<codes.length;i++) {
            BigDecimal sum=BigDecimal.ZERO;var missing=new ArrayList<LocalDate>();var sources=new ArrayList<Source>();
            for(var p:periods) {
                var v=p.metrics().get(i);
                if (!"AVAILABLE".equals(v.status())) { missing.add(p.month()); continue; }
                BigDecimal amount=v.value();
                if(i==2) {
                    var matches=carbon.listSummaries(v.sourceBatchId()).stream().filter(s->"TOTAL_EMISSION".equals(s.metricCode())&&"TOTAL".equals(s.dimensionCode())).toList();
                    if(matches.size()!=1 || matches.getFirst().rawValue()==null) { missing.add(p.month()); continue; }
                    amount=matches.getFirst().rawValue();
                }
                sum=sum.add(amount);sources.add(new Source(p.month(),v.sourceBatchId(),v.evidenceHash(),v.referenceVersionId()));
            }
            if(i==2 && missing.isEmpty()) rawEmission=sum;
            totals.add(new Metric(codes[i],units[i],missing.isEmpty()?sum.setScale(6,RoundingMode.HALF_UP):null,
                    missing.isEmpty()?"AVAILABLE":"MISSING_MONTHS",List.copyOf(missing),List.copyOf(sources)));
        }
        var area=AreaTimeline.weigh(rules.areaSlices(building,UsageNature.FORMAL,start,end),start,end);
        Metric emission=totals.get(2);
        String status=!"AVAILABLE".equals(emission.status())?"EMISSION_UNAVAILABLE":area.status();
        // 直接除以面积天数，避免先舍入平均面积；这里不求和已舍入的月强度。
        BigDecimal intensity="AVAILABLE".equals(status)?rawEmission.multiply(BigDecimal.valueOf(1000L*area.days()))
                .divide(area.areaDays(),6,RoundingMode.HALF_UP):null;
        totals.add(new Metric("YTD_AREA_INTENSITY","kgCO2e/m²",intensity,status,emission.missingMonths(),emission.sources()));
        var parts=area.slices().stream().map(s->new AreaPart(s.version().denominatorVersionId(),s.from().isBefore(start)?start:s.from(),
                s.to()==null||s.to().isAfter(end)?end:s.to(),s.version().value(),s.version().sourceReference(),s.version().evidenceReference())).toList();
        return new Period(start,end,from,to,List.copyOf(totals),area.average(),area.status(),parts);
    }
    private Metric frozen(String code,String unit,List<SummaryMetric> summaries,CalculationBatch batch,LocalDate start) {
        var matches=summaries.stream().filter(s->code.equals(s.metricCode())&&"TOTAL".equals(s.dimensionCode())).toList();
        var s=matches.size()==1?matches.getFirst():null;
        boolean valid=s!=null&&unit.equals(s.unitCode())&&s.finalValue()!=null&&s.unavailableReason()==null;
        return new Metric(code,unit,valid?s.finalValue():null,valid?"AVAILABLE":"FROZEN_METRIC_UNAVAILABLE",List.of(),
                batch==null?List.of():List.of(new Source(start,batch.batchId(),s==null?null:s.evidenceHash(),s==null?null:("TOTAL_EMISSION".equals(code)?batch.roundingPolicyVersionId():s.denominatorVersionId()))));
    }
    private static MonthlyComparisonService.Value value(Metric m) { return new MonthlyComparisonService.Value(m.code(),m.unit(),m.value(),m.status(),null,null,null); }
    private static BusinessException invalid() { return new BusinessException(400,"ANNUAL_OVERVIEW_INVALID","建筑、年份、模式、截止月或时区无效；正式年度不接受截止月"); }
}
