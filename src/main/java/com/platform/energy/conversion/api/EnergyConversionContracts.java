package com.platform.energy.conversion.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 折标参数、公式版本和确定性模拟计算的稳定 HTTP DTO。 */
public final class EnergyConversionContracts {
    private EnergyConversionContracts() {
    }

    public record CreateStandardCoalVersionRequest(
            @NotBlank @Size(max = 64) String lhvCode,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal value,
            @NotBlank String parameterUnit,
            @NotBlank String sourceType,
            @NotBlank @Size(max = 500) String sourceReference,
            @NotNull LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo) {
    }

    public record CreateFormulaVersionRequest(
            @NotBlank @Size(max = 64) String formulaCode,
            @NotBlank String method,
            @NotBlank String perspective,
            @NotBlank String algorithmCode,
            @NotBlank @Size(max = 64) String applicableInputUnitCode,
            @NotBlank @Size(max = 64) String resultUnitCode,
            @NotBlank String parameterUnit,
            @NotBlank String sourceType,
            @NotBlank @Size(max = 500) String sourceReference,
            @NotNull LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo) {
    }

    public record CreateParameterVersionRequest(
            @NotBlank @Size(max = 64) String parameterCode,
            @NotBlank @Size(max = 64) String energyItemCode,
            @NotBlank @Size(max = 32) String formulaVersionId,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal parameterValue,
            @NotBlank String parameterUnit,
            @Size(max = 32) String standardCoalLhvVersionId,
            @NotBlank String consumptionScope,
            @NotBlank @Size(max = 32) String regionCode,
            @NotBlank String usageScope,
            @NotBlank String sourceType,
            @NotBlank @Size(max = 500) String sourceReference,
            @NotNull LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo) {
    }

    public record ApproveRequest(
            @NotNull @Min(0) Integer expectedRevision,
            @NotBlank @Size(max = 500) String reviewComment) {
    }

    public record SimulationRequest(
            @NotBlank String buildingId,
            @NotBlank @Size(max = 64) String energyItemCode,
            @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal quantity,
            @NotBlank @Size(max = 64) String inputUnitCode,
            @NotBlank String method,
            @NotBlank String perspective,
            @NotBlank String consumptionScope,
            @NotBlank @Size(max = 32) String regionCode,
            @NotNull LocalDateTime effectiveAt) {
    }

    public record StandardCoalVersionView(
            String lhvId, String lhvCode, String versionId, int versionNo, BigDecimal value,
            String parameterUnit, String energyUnitVersionId, String coalUnitVersionId,
            String status, String sourceType, String sourceReference,
            LocalDateTime effectiveFrom, LocalDateTime effectiveTo, int configRevision,
            long createdBy, LocalDateTime createdAt, Long approvedBy, LocalDateTime approvedAt) {
    }

    public record FormulaVersionView(
            String formulaId, String formulaCode, String versionId, int versionNo, String method,
            String perspective, String algorithmCode, String applicableInputUnitCode,
            String applicableInputUnitVersionId, String resultUnitCode, String resultUnitVersionId,
            String parameterUnit, String status, String sourceType, String sourceReference,
            LocalDateTime effectiveFrom, LocalDateTime effectiveTo, int configRevision,
            long createdBy, LocalDateTime createdAt, Long approvedBy, LocalDateTime approvedAt) {
    }

    public record ParameterVersionView(
            String parameterId, String parameterCode, String versionId, int versionNo,
            String energyItemCode, String energyItemVersionId, String formulaCode,
            String formulaVersionId, String method, String perspective, BigDecimal parameterValue,
            String parameterUnit, String standardCoalLhvVersionId, String consumptionScope,
            String regionCode, String usageScope, String status, String sourceType,
            String sourceReference, LocalDateTime effectiveFrom, LocalDateTime effectiveTo,
            int configRevision, long createdBy, LocalDateTime createdAt,
            Long approvedBy, LocalDateTime approvedAt) {
    }

    public record SimulationResultView(
            String resultNature, String buildingId, String energyItemCode,
            String energyItemVersionId, BigDecimal inputQuantity, String inputUnitCode,
            String inputUnitVersionId, BigDecimal convertedQuantity,
            String applicableInputUnitCode, String applicableInputUnitVersionId,
            String method, String perspective, BigDecimal parameterValue, String parameterUnit,
            String parameterVersionId, String formulaVersionId, String algorithmCode,
            String standardCoalLhvVersionId, BigDecimal tce, String resultUnitCode,
            LocalDateTime effectiveAt) {
    }

    public record OptionsView(
            List<String> statuses, List<String> methods, List<String> perspectives,
            List<String> algorithms, List<String> parameterUnits,
            List<String> ruleUsageScopes, List<String> resultNatures) {
    }
}
