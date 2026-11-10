package com.platform.carbon;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** A real day interval with frozen static rules; never a synthetic set of hourly observations. */
public final class DailyStaticCarbonContracts {
    private DailyStaticCarbonContracts() { }
    public record Request(@NotBlank @Size(max=32) String buildingId, @NotNull LocalDate date,
            @NotNull Instant calculationAsOf, @NotBlank @Size(max=100) String idempotencyKey,
            @NotBlank @Pattern(regexp="SIMULATED|SOURCE_LABELLED") String declaredSourceNature,
            @NotBlank @Size(max=500) String sourceReference,
            @NotBlank @Pattern(regexp="FORMAL|DEVELOPMENT_REFERENCE") String factorUsageNature) { }
    public record View(String buildingId,LocalDate date,Instant startInclusive,Instant endExclusive,
            String timezoneId,String periodType,String unit,String scope,String resultNature,String state,
            String sourceKind,String activityWatermarkMeaning,String declaredSourceNature,String electricitySourceNature,String factorUsageNature,
            String calculationBatchId,Integer revision,Instant calculationAsOf,String sourceVersion,
            String classificationVersionId,String relationVersionId,String projectVersionId,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal electricityKwh,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal emissionKgCo2e,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal staticFactorKgCo2ePerKwh,
            String factorStatus,String gasBasis,List<String> factorVersionIds,String inputEvidenceHash,String evidenceHash) { }
    static View identity(View v,String id,int revision) {
        return new View(v.buildingId(),v.date(),v.startInclusive(),v.endExclusive(),v.timezoneId(),v.periodType(),v.unit(),v.scope(),
                v.resultNature(),v.state(),v.sourceKind(),v.activityWatermarkMeaning(),v.declaredSourceNature(),v.electricitySourceNature(),v.factorUsageNature(),
                id,revision,v.calculationAsOf(),v.sourceVersion(),v.classificationVersionId(),v.relationVersionId(),v.projectVersionId(),
                v.electricityKwh(),v.emissionKgCo2e(),v.staticFactorKgCo2ePerKwh(),v.factorStatus(),v.gasBasis(),v.factorVersionIds(),v.inputEvidenceHash(),v.evidenceHash());
    }
}
