package com.platform.energy.catalog.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 能源品种、单位量纲、兼容矩阵和测点绑定的稳定 HTTP DTO。 */
public final class EnergyCatalogContracts {
    private EnergyCatalogContracts() {
    }

    public record CreateItemVersionRequest(
            @NotBlank @Size(max = 64) String itemCode,
            @NotBlank @Size(max = 100) String itemName,
            @NotBlank String compatibleCategory,
            @NotEmpty List<@NotBlank String> usageScopes,
            @NotBlank String sourceType,
            @NotBlank @Size(max = 500) String sourceReference,
            @NotNull LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo) {
    }

    public record CreateUnitVersionRequest(
            @NotBlank @Size(max = 64) String unitCode,
            @NotBlank @Size(max = 32) String symbol,
            @NotBlank @Size(max = 100) String unitName,
            @NotBlank String dimensionCode,
            @NotBlank @Size(max = 64) String canonicalUnitCode,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal scaleFactor,
            @NotBlank String conversionType,
            @Size(max = 100) String standardConditionCode,
            @NotNull @Min(0) Integer precision,
            @NotBlank String sourceType,
            @NotBlank @Size(max = 500) String sourceReference,
            @NotNull LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo) {
    }

    public record CreateCompatibilityVersionRequest(
            @NotBlank @Size(max = 64) String energyItemCode,
            @NotBlank @Size(max = 64) String unitCode,
            @NotBlank String valueSemantics,
            @NotNull Boolean allowed,
            @NotBlank String conversionRequirement,
            @NotBlank String sourceType,
            @NotBlank @Size(max = 500) String sourceReference,
            @NotNull LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo) {
    }

    public record CreateBindingVersionRequest(
            @NotBlank String buildingId,
            @NotBlank String pointId,
            @NotBlank @Size(max = 64) String energyItemCode,
            @NotNull LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo,
            @NotBlank @Size(max = 500) String evidenceReference) {
    }

    public record ApproveRequest(
            @NotNull @Min(0) Integer expectedRevision,
            @NotBlank @Size(max = 500) String reviewComment) {
    }

    public record ItemVersionView(
            String itemId, String itemCode, String versionId, int versionNo, String itemName,
            String compatibleCategory, List<String> usageScopes, String status, String sourceType,
            String sourceReference, LocalDateTime effectiveFrom, LocalDateTime effectiveTo,
            int configRevision, Long createdBy, LocalDateTime createdAt,
            Long approvedBy, LocalDateTime approvedAt) {
    }

    public record UnitVersionView(
            String unitId, String unitCode, String versionId, int versionNo, String symbol,
            String unitName, String dimensionCode, String canonicalUnitCode, BigDecimal scaleFactor,
            String conversionType, String standardConditionCode, int precision, String status,
            String sourceType, String sourceReference, LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo, int configRevision, Long createdBy, LocalDateTime createdAt,
            Long approvedBy, LocalDateTime approvedAt) {
    }

    public record CompatibilityVersionView(
            String compatibilityId, String versionId, int versionNo, String energyItemCode,
            String unitCode, String valueSemantics, boolean allowed, String conversionRequirement,
            String status, String sourceType, String sourceReference, LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo, int configRevision, Long createdBy, LocalDateTime createdAt,
            Long approvedBy, LocalDateTime approvedAt) {
    }

    public record BindingVersionView(
            String bindingId, String bindingVersionId, int bindingVersion, String buildingId,
            String pointId, String pointCode, String rawUnit, String energyItemCode,
            String energyItemVersionId, LocalDateTime effectiveFrom, LocalDateTime effectiveTo,
            String confirmationStatus, String evidenceReference, int configRevision,
            Long createdBy, LocalDateTime createdAt, Long approvedBy, LocalDateTime approvedAt) {
    }

    public record OptionsView(
            List<String> catalogStatuses, List<String> sourceTypes, List<String> usageScopes,
            List<String> dimensionCodes, List<String> conversionTypes,
            List<String> conversionRequirements, List<String> bindingStatuses) {
    }
}
