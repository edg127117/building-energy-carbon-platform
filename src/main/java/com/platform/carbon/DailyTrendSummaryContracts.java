package com.platform.carbon;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** 图表只读摘要；详细来源列表仍通过原日趋势及小时批次接口追溯。 */
public final class DailyTrendSummaryContracts {
    private DailyTrendSummaryContracts() { }
    public static final String CONTRACT="DAILY_ELECTRICITY_STATIC_CARBON_SUMMARY_V1";
    public record Day(LocalDate date,Instant startInclusive,Instant endExclusive,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal electricityKwh,String electricityStatus,String electricityReasonCode,
            String classificationVersionId,String relationVersionId,String electricitySourceNature,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal staticEmissionTco2e,String staticCarbonStatus,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal staticFactorKgCo2ePerKwh,String factorStatus,
            String hourlyBatchId,Integer hourlyRevision,String hourlySourceNature,List<String> factorVersionIds,String hourlyEvidenceHash) { }
    public record View(String buildingId,String month,String timezoneId,String state,
            String electricityUnit,String emissionUnit,String factorUnit,String resultNature,String contractVersion,
            List<Day> days,Instant capturedAt,String evidenceHash,String sourceVersion,String detailPath,int retryAfterSeconds) { }
    static View from(DailyElectricityCarbonContracts.View v,String version) {
        return new View(v.buildingId(),v.month(),v.timezoneId(),v.state(),v.electricityUnit(),v.emissionUnit(),v.factorUnit(),v.resultNature(),CONTRACT,
                v.days().stream().map(d->new Day(d.date(),d.startInclusive(),d.endExclusive(),d.electricityKwh(),d.electricityStatus(),d.electricityReasonCode(),
                        d.classificationVersionId(),d.relationVersionId(),d.electricitySourceNature(),d.staticEmissionTco2e(),d.staticCarbonStatus(),
                        d.staticFactorKgCo2ePerKwh(),d.factorStatus(),d.hourlyBatchId(),d.hourlyRevision(),d.hourlySourceNature(),d.factorVersionIds(),d.hourlyEvidenceHash())).toList(),
                v.capturedAt(),v.evidenceHash(),version,detail(v.buildingId()),0);
    }
    static View preparing(String building,String month,String version) {
        return new View(building,month,"Asia/Shanghai","PREPARING","kWh","tCO2e","kgCO2e/kWh","MONITORING",CONTRACT,
                List.of(),Instant.now(),null,version,detail(building),2);
    }
    private static String detail(String building){return "/v1/energy-reporting/buildings/"+building+"/daily-trends";}
}
