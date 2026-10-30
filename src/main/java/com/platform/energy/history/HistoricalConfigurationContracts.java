package com.platform.energy.history;

import com.platform.energy.classification.ClassificationContracts.ImportRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 独立返回原始导入校验与计算配置覆盖，不把预检结果当成封账或核算结果。 */
public final class HistoricalConfigurationContracts {
    private HistoricalConfigurationContracts() {}
    @Schema(name = "HistoricalConfigurationCoverageRequest")
    public record Request(@NotNull @Valid ImportRequest data, String classificationVersionId,
            @NotBlank String timezoneId, @NotBlank String method, @NotBlank String perspective,
            @NotBlank String consumptionScope, @NotBlank String regionCode) {}
    @Schema(name = "HistoricalConfigurationCoverageCheck")
    public record Check(int row, String pointId, String domain, String status,
            String versionId, String code, String message) {}
    @Schema(name = "HistoricalConfigurationCoverage")
    public record Coverage(String buildingId, String formatStatus, String configurationStatus,
            String executionStatus, List<Check> checks) {}
}
