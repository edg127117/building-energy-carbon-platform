package com.platform.iot.energymetadata.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** 能源测点专业属性的稳定 HTTP DTO。 */
public final class EnergyPointProfileContracts {
    private EnergyPointProfileContracts() {
    }

    public record CreateRequest(
            @NotBlank String buildingId,
            @NotBlank String pointId,
            @NotBlank String energyType,
            String energySubtype,
            @NotBlank String valueSemantics,
            @NotBlank @Pattern(regexp = "MONTH") String reportingPeriod,
            @NotNull Boolean annualSummary,
            @NotBlank String confirmationStatus,
            @NotBlank @Size(max = 500) String evidenceReference) {
    }

    public record UpdateRequest(
            @NotBlank String energyType,
            String energySubtype,
            @NotBlank String valueSemantics,
            @NotBlank @Pattern(regexp = "MONTH") String reportingPeriod,
            @NotNull Boolean annualSummary,
            @NotBlank String confirmationStatus,
            @NotBlank @Size(max = 500) String evidenceReference,
            @NotNull @Min(0) Integer expectedRevision) {
    }

    public record ProfileView(
            String profileId,
            String pointId,
            String pointCode,
            String buildingId,
            String energyType,
            String energySubtype,
            String valueSemantics,
            String unit,
            String reportingPeriod,
            boolean annualSummary,
            String confirmationStatus,
            String evidenceReference,
            int configRevision,
            Long createBy,
            Long updateBy,
            LocalDateTime createTime,
            LocalDateTime updateTime) {
    }

    public record OptionsView(
            List<String> energyTypes,
            List<String> electricitySubtypes,
            List<String> valueSemantics,
            List<String> reportingPeriods,
            List<String> confirmationStatuses,
            List<String> supportedUnits) {
    }

    /** 业务统计周期与设备采集周期分字段返回，调用方不能把月度统计解释为月度采集。 */
    public record CollectionContextView(
            String sourceId,
            String sourceCode,
            String aliasId,
            String sourcePointCode,
            String pointId,
            String pointCode,
            String unit,
            boolean profilePresent,
            String energyType,
            String energySubtype,
            String valueSemantics,
            String reportingPeriod,
            Boolean annualSummary,
            String confirmationStatus,
            Integer profileRevision,
            String policyVersionId,
            String policyVersionStatus,
            Integer expectedIntervalSeconds,
            Integer allowedDelaySeconds,
            String timeSemantics) {
    }
}
