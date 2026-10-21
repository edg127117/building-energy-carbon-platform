package com.platform.carbon;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;

/** 项目配置只引用平台能源与真实来源；预留类别不赋予计算能力。 */
public final class CarbonProjectContracts {
    private CarbonProjectContracts() { }
    public record Energy(String energyItemCode, String pathway, String capability) { }
    public record Exclusion(String energyItemCode, List<String> pointIds,
                            String reason, String evidence) { }
    public record Configuration(String classificationVersionId, List<Energy> energies,
                                List<Exclusion> exclusions) { }
    public record Create(@NotBlank @Size(max=500) String reason, String copiedFromVersionId) { }
    public record Save(@NotNull @Min(0) Long expectedRevision, @NotNull @Valid Configuration configuration,
                       @NotBlank @Size(max=500) String reason) { }
    public record Transition(@NotNull @Min(0) Long expectedRevision, @NotBlank @Size(max=500) String reason,
                             Instant effectiveFrom) { }
    public record Version(String versionId, String buildingId, int versionNo, long revision,
                          String status, Instant effectiveFrom, Instant effectiveTo,
                          long createdBy, Long reviewedBy, Configuration configuration) { }
    public record Issue(String code, String reference, String message) { }
    public record Readiness(String buildingId, String versionId, boolean ready,
                            List<Issue> issues) { }
    public record PathOption(String code,String capability,String scopeType) { }
}
