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
            String hourlyBatchId,Integer hourlyRevision,String hourlySourceNature,List<String> factorVersionIds,String hourlyEvidenceHash, String gasBasis, DailyElectricityCarbonContracts.StaticSource staticSource) {
        public Day(LocalDate date,Instant startInclusive,Instant endExclusive,BigDecimal electricityKwh,
            String electricityStatus,String electricityReasonCode,String classificationVersionId,String relationVersionId,String electricitySourceNature,
            BigDecimal staticEmissionTco2e,String staticCarbonStatus,BigDecimal staticFactorKgCo2ePerKwh,String factorStatus,
            String hourlyBatchId,Integer hourlyRevision,String hourlySourceNature,List<String> factorVersionIds,String hourlyEvidenceHash,String gasBasis) {
            this(date,startInclusive,endExclusive,electricityKwh,electricityStatus,electricityReasonCode,classificationVersionId,relationVersionId,
                    electricitySourceNature,staticEmissionTco2e,staticCarbonStatus,staticFactorKgCo2ePerKwh,factorStatus,hourlyBatchId,hourlyRevision,
                    hourlySourceNature,factorVersionIds,hourlyEvidenceHash,gasBasis,
                    hourlyBatchId==null?null:new DailyElectricityCarbonContracts.StaticSource("HOUR",hourlyBatchId,hourlyRevision,hourlyEvidenceHash,hourlySourceNature,null));
        }
        /** 旧调用不携带气体覆盖证据，保守标记为未知。 */
        public Day(LocalDate date,Instant startInclusive,Instant endExclusive,
            BigDecimal electricityKwh,String electricityStatus,String electricityReasonCode,
            String classificationVersionId,String relationVersionId,String electricitySourceNature,
            BigDecimal staticEmissionTco2e,String staticCarbonStatus,
            BigDecimal staticFactorKgCo2ePerKwh,String factorStatus,
            String hourlyBatchId,Integer hourlyRevision,String hourlySourceNature,List<String> factorVersionIds,String hourlyEvidenceHash) {
            this(date, startInclusive, endExclusive, electricityKwh, electricityStatus, electricityReasonCode, classificationVersionId, relationVersionId, electricitySourceNature, staticEmissionTco2e, staticCarbonStatus, staticFactorKgCo2ePerKwh, factorStatus, hourlyBatchId, hourlyRevision, hourlySourceNature, factorVersionIds, hourlyEvidenceHash, "UNKNOWN");
        }
    }
    public record View(String buildingId,String month,String timezoneId,String state,
            String electricityUnit,String emissionUnit,String factorUnit,String resultNature,String contractVersion,
            List<Day> days,Instant capturedAt,String evidenceHash,String sourceVersion,String detailPath,int retryAfterSeconds) { }
    static View from(DailyElectricityCarbonContracts.View v,String version) {
        return new View(v.buildingId(),v.month(),v.timezoneId(),v.state(),v.electricityUnit(),v.emissionUnit(),v.factorUnit(),v.resultNature(),CONTRACT,
                v.days().stream().map(d->new Day(d.date(),d.startInclusive(),d.endExclusive(),d.electricityKwh(),d.electricityStatus(),d.electricityReasonCode(),
                        d.classificationVersionId(),d.relationVersionId(),d.electricitySourceNature(),d.staticEmissionTco2e(),d.staticCarbonStatus(),
                        d.staticFactorKgCo2ePerKwh(),d.factorStatus(),d.hourlyBatchId(),d.hourlyRevision(),d.hourlySourceNature(),d.factorVersionIds(),d.hourlyEvidenceHash(),d.gasBasis(),d.staticSource())).toList(),
                v.capturedAt(),v.evidenceHash(),version,detail(v.buildingId()),0);
    }
    static View preparing(String building,String month,String version) {
        return new View(building,month,"Asia/Shanghai","PREPARING","kWh","tCO2e","kgCO2e/kWh","MONITORING",CONTRACT,
                List.of(),Instant.now(),null,version,detail(building),2);
    }
    private static String detail(String building){return "/v1/energy-reporting/buildings/"+building+"/daily-trends";}
}
