package com.platform.carbon;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 北京时间整月日序列；日计量与小时监测来源分别保留，不冒充正式月度封账。 */
public final class DailyElectricityCarbonContracts {
    private DailyElectricityCarbonContracts() { }
    @Schema(name="DailyElectricityStaticCarbonDay")
    public record Day(LocalDate date, Instant startInclusive, Instant endExclusive,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal electricityKwh,
            String electricityStatus, String electricityReasonCode, String classificationVersionId,
            String relationVersionId, List<String> electricityEvidenceIds, String electricitySourceNature,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal staticEmissionTco2e,
            String staticCarbonStatus,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal staticFactorKgCo2ePerKwh,
            String factorStatus, String hourlyBatchId, Integer hourlyRevision,
            String hourlySourceNature, List<String> factorVersionIds, String hourlyEvidenceHash, String gasBasis, StaticSource staticSource) {
        public Day(LocalDate date, Instant startInclusive, Instant endExclusive,
            BigDecimal electricityKwh,String electricityStatus,String electricityReasonCode,String classificationVersionId,
            String relationVersionId,List<String> electricityEvidenceIds,String electricitySourceNature,
            BigDecimal staticEmissionTco2e,String staticCarbonStatus,BigDecimal staticFactorKgCo2ePerKwh,
            String factorStatus,String hourlyBatchId,Integer hourlyRevision,String hourlySourceNature,
            List<String> factorVersionIds,String hourlyEvidenceHash,String gasBasis) {
            this(date,startInclusive,endExclusive,electricityKwh,electricityStatus,electricityReasonCode,classificationVersionId,
                    relationVersionId,electricityEvidenceIds,electricitySourceNature,staticEmissionTco2e,staticCarbonStatus,
                    staticFactorKgCo2ePerKwh,factorStatus,hourlyBatchId,hourlyRevision,hourlySourceNature,factorVersionIds,hourlyEvidenceHash,gasBasis,
                    hourlyBatchId==null?null:new StaticSource("HOUR",hourlyBatchId,hourlyRevision,hourlyEvidenceHash,hourlySourceNature,null));
        }
        /** 旧调用不携带气体覆盖证据，保守标记为未知。 */
        public Day(LocalDate date, Instant startInclusive, Instant endExclusive,
            BigDecimal electricityKwh,
            String electricityStatus, String electricityReasonCode, String classificationVersionId,
            String relationVersionId, List<String> electricityEvidenceIds, String electricitySourceNature,
            BigDecimal staticEmissionTco2e,
            String staticCarbonStatus,
            BigDecimal staticFactorKgCo2ePerKwh,
            String factorStatus, String hourlyBatchId, Integer hourlyRevision,
            String hourlySourceNature, List<String> factorVersionIds, String hourlyEvidenceHash) {
            this(date, startInclusive, endExclusive, electricityKwh, electricityStatus, electricityReasonCode, classificationVersionId, relationVersionId, electricityEvidenceIds, electricitySourceNature, staticEmissionTco2e, staticCarbonStatus, staticFactorKgCo2ePerKwh, factorStatus, hourlyBatchId, hourlyRevision, hourlySourceNature, factorVersionIds, hourlyEvidenceHash, "UNKNOWN");
        }
    }
    public record StaticSource(String grain,String calculationBatchId,Integer revision,String evidenceHash,
                               String declaredSourceNature,String sourceVersion) { }
    @Schema(name="DailyElectricityStaticCarbonView")
    public record View(String buildingId, String month, String timezoneId, String state,
            String electricityUnit, String emissionUnit, String factorUnit, String resultNature,
            String contractVersion, List<Day> days, Instant capturedAt, String evidenceHash) { }
}
