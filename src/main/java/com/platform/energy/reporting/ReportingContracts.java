package com.platform.energy.reporting;

import jakarta.validation.Valid;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 账单为独立业务来源；日期按来源时区左闭右开，原生量与折标完整性分列。 */
public final class ReportingContracts {
    private ReportingContracts() { }
    public record SourceConfig(@NotBlank @Size(max=100) String name, @NotBlank String energyItemCode,
        @NotBlank String unitCode, @NotBlank String channel, @NotBlank String pathway,
        @NotBlank String purpose, @NotBlank String coverage, @NotBlank String meterOverlap,
        @NotBlank String timezoneId, @NotNull LocalDate effectiveFrom, LocalDate effectiveTo,
        @NotBlank @Size(max=1000) String evidence, @NotBlank @Size(max=500) String reason,
        String conversionMethod, String conversionPerspective, String regionCode,String supersedesSourceId) { }
    public record Source(String sourceId,long revision,String status,long createdBy,Long reviewedBy,SourceConfig configuration) { }
    public record CreateSource(@NotNull @Valid SourceConfig configuration,@NotBlank @Size(max=100) String idempotencyKey) { }
    public record SaveSource(@NotNull @Min(0) Long expectedRevision,@NotNull @Valid SourceConfig configuration) { }
    public record Row(@NotBlank String sourceId,@Min(0) long sourceRevision,@DecimalMin("0") @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal quantity,
        @NotBlank String valueStatus,@NotNull LocalDate startDate,@NotNull LocalDate endDate,
        @Size(max=1000) String evidence,@Size(max=1000) String note,@DecimalMin("0") @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal exclusionQuantity,
        @Size(max=500) String exclusionReason,@Size(max=1000) String exclusionEvidence,String dataNature) { }
    public record CreateBatch(@NotNull LocalDate month,@NotBlank @Size(max=500) String reason,
        @NotBlank @Size(max=100) String idempotencyKey,String supersedesBatchId) { }
    public record SaveBatch(@NotNull @Min(0) Long expectedRevision,@NotNull @Size(max=100) List<@NotNull @Valid Row> rows,
        @NotBlank @Size(max=500) String reason) { }
    public record Command(@NotNull @Min(0) Long expectedRevision,@NotBlank @Size(max=500) String reason,
        @Size(max=100) String idempotencyKey) { }
    public record Batch(String batchId,String buildingId,LocalDate month,String status,long revision,
        long createdBy,Long reviewedBy,String supersedesBatchId,List<Row> rows,Snapshot snapshot) { }
    public record Issue(String code,String reference,String message) { }
    public record PreparedRow(Row input,Source source,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal nativeQuantity,String nativeUnit,
        String energyItemVersionId,String unitVersionId,com.platform.energy.catalog.EnergyCatalogLookup.ApprovedUnit unitEvidence,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal tce,String tceStatus,Object conversionEvidence) { }
    public record Snapshot(String contractVersion,String nativeStatus,String tceStatus,List<PreparedRow> rows,String evidenceHash) { }
    public record Preview(boolean ready,List<Issue> issues,String nativeStatus,String tceStatus) { }
    public record EnergyOption(String code,String name,List<String> usageScopes) { }
    public record UnitOption(String code,String symbol,String dimension) { }
    public record ConversionOption(String energyItemCode,String method,String perspective,String regionCode,String label) { }
    public record Capabilities(boolean maintainSources,boolean reviewSources,boolean reviewBatches,boolean sealBatches) { }
    public record Context(String buildingId,LocalDate month,List<Source> sources,List<Batch> batches,
        List<EnergyOption> energyOptions,List<UnitOption> unitOptions,List<ConversionOption> conversionOptions,Capabilities capabilities) { }
}
