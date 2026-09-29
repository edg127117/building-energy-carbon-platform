package com.platform.hvac.asset.api;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** 表计覆盖档案只描述现场关系，不是能源分摊、结算或多联机拓扑。 */
public final class MeterCoverageContracts {
    private MeterCoverageContracts() {}

    public record Target(String equipmentId, String equipmentCode, String equipmentName,
                         String spaceId, String spaceName, boolean active) {}

    public record View(String equipmentId, long revision, Instant effectiveAt,
                       String installationSpaceId, String installationSpaceName,
                       String scopeLabel, String reason, List<Target> targets,
                       String quantityMode, String aggregationPolicy) {}

    public record SaveRequest(@NotNull @Min(0) Long expectedRevision,
                              @Size(max = 32) String installationSpaceId,
                              @NotBlank @Size(max = 160) String scopeLabel,
                              @NotNull @Size(max = 100) List<@NotBlank @Size(max = 32) String> targetEquipmentIds,
                              @NotBlank @Size(max = 500) String reason) {}
}
