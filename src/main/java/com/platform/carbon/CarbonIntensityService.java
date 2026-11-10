package com.platform.carbon;

import com.platform.hvac.mapper.BuildingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import static com.platform.carbon.CarbonIntensityCatalogService.error;
import static com.platform.carbon.CarbonIntensityContracts.*;

/** 权限先于档案及核算读取；查询不创建结果、不运行面积算法、不把累计量年化。 */
@Service
@RequiredArgsConstructor
public class CarbonIntensityService {
    private final CarbonAuthorization authorization;
    private final BuildingMapper buildings;
    private final CarbonIntensityCatalogService catalog;
    private final StoredCarbonIntensityReader actuals;
    private final ObjectProvider<CarbonIntensityYtdReader> ytd;
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View query(long user,Collection<String> roles,String building,String mode,int year,Integer month,String timezone) {
        if(building==null || building.isBlank() || building.length()>32 || year<2000 || year>2200
                || !Set.of("FORMAL_YEAR","MONTHLY_YTD","MONTH").contains(mode==null?"":mode)
                || ("FORMAL_YEAR".equals(mode)?month!=null:month==null || month<1 || month>12))
            throw error(400,"QUERY_INVALID","建筑、模式、年份或月份无效；正式年度不接受月份");
        ZoneId zone; try { zone=ZoneId.of(timezone); } catch(DateTimeException | NullPointerException e) { throw error(400,"QUERY_INVALID","统计时区无效"); }
        authorization.requireReader(user,roles,building);
        var profile=buildings.selectById(building);
        if(profile==null) throw error(404,"BUILDING_NOT_FOUND","建筑不存在");
        LocalDate start=LocalDate.of(year,"MONTH".equals(mode)?month:1,1);
        LocalDate end="FORMAL_YEAR".equals(mode)?start.plusYears(1):"MONTH".equals(mode)?start.plusMonths(1):start.plusMonths(month);
        Actual actual;
        if("MONTHLY_YTD".equals(mode)) {
            var reader=ytd.getIfAvailable();
            actual=reader==null?StoredCarbonIntensityReader.unavailable("ANNUAL_OVERVIEW_INTEGRATION_PENDING"):reader.read(user,roles,building,year,month,timezone);
        } else actual=actuals.read(building,mode,start,end,zone);
        var match=CarbonIntensityMatcher.match(catalog.list(),profile,start,end);
        return evaluate(building,mode,start,end,zone.getId(),actual,match);
    }
    static View evaluate(String building,String mode,LocalDate start,LocalDate end,String timezone,Actual actual,CarbonIntensityMatcher.Match match) {
        List<String> reasons=new ArrayList<>();
        if(!"MATCHED".equals(match.status())) reasons.add(match.status());
        if(!"FORMAL_YEAR".equals(mode)) reasons.add("MONTH".equals(mode)?"MONTH_NOT_ANNUAL_COMPARISON":"YTD_NOT_ANNUAL_COMPARISON");
        if(!"AVAILABLE".equals(actual.status()) || actual.value()==null || actual.value().signum()<0) reasons.add("ACTUAL_UNAVAILABLE");
        var reference=match.reference();
        if(reference!=null) {
            if(!"kgCO2e/m²".equals(actual.unit()) && !"kgCO2/m²".equals(actual.unit())) reasons.add("UNIT_MISMATCH");
            if(!reference.rule().gasBasis().equals(actual.gasBasis())) reasons.add("GAS_BASIS_MISMATCH_OR_UNKNOWN");
            if("UNKNOWN".equals(reference.rule().boundaryCode()) || !reference.rule().boundaryCode().equals(actual.boundaryStatus())) reasons.add("BOUNDARY_MISMATCH_OR_UNKNOWN");
            if("UNKNOWN".equals(reference.rule().areaBasis()) || !reference.rule().areaBasis().equals(actual.areaStatus())) reasons.add("AREA_BASIS_MISMATCH_OR_UNKNOWN");
        }
        List<String> percentageReasons=new ArrayList<>();
        if(reference!=null && Set.of("MATCHED","APPLICABILITY_TIME_UNCLEAR").contains(match.status())) {
            // 唯一目录条目即可计算参考比例；原文日期和专门边界只影响标准比较，不伪造适用期或年化累计值。
            if("MONTH".equals(mode)) percentageReasons.add("MONTH_NOT_ANNUAL_COMPARISON");
            for(String reason:reasons) if(Set.of("ACTUAL_UNAVAILABLE","UNIT_MISMATCH","GAS_BASIS_MISMATCH_OR_UNKNOWN","AREA_BASIS_MISMATCH_OR_UNKNOWN").contains(reason)) percentageReasons.add(reason);
        } else percentageReasons.addAll(reasons);
        String basis="MONTHLY_YTD".equals(mode)?"YTD_SHARE_OF_ANNUAL_REFERENCE":"ANNUAL_REFERENCE";
        BigDecimal percentage=percentageReasons.isEmpty()?actual.value().multiply(BigDecimal.valueOf(100)).divide(reference.rule().constraintValue(),6,RoundingMode.HALF_UP):null;
        String hash=CarbonCalculationCore.sha256(CarbonEvidence.canonical(CarbonEvidence.tree(Arrays.asList(building,mode,start,end,timezone,actual,match,percentage,basis,percentageReasons))));
        return new View(building,mode,start,end,timezone,actual,match.status(),reasons.isEmpty()?"COMPARABLE":"NOT_COMPARABLE",List.copyOf(reasons),reference,percentage,
                percentage==null?"UNAVAILABLE":"AVAILABLE",basis,List.copyOf(percentageReasons),hash);
    }
}
