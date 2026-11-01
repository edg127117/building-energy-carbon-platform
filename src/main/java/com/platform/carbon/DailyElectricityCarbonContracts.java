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
            String hourlySourceNature, List<String> factorVersionIds, String hourlyEvidenceHash) { }
    @Schema(name="DailyElectricityStaticCarbonView")
    public record View(String buildingId, String month, String timezoneId, String state,
            String electricityUnit, String emissionUnit, String factorUnit, String resultNature,
            String contractVersion, List<Day> days, Instant capturedAt, String evidenceHash) { }
}
