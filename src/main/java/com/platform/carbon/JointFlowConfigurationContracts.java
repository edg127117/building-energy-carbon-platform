package com.platform.carbon;

import com.platform.energy.classification.ClassificationContracts;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** 展示规则独立版本化；不具有正式分类生效或核算修订的含义。 */
public final class JointFlowConfigurationContracts {
    private JointFlowConfigurationContracts() { }
    @Schema(name="JointFlowDisplayNode")
    public record DisplayNode(String code, String name, int order) { }
    @Schema(name="JointFlowDimension")
    public record Dimension(String key, String title, String source, String schemeCode,
                            String scopeLevel, List<DisplayNode> nodes) { }
    @Schema(name="JointFlowFloorReference")
    public record FloorReference(String relationVersionId, String schemeCode, String nodeCode, String spaceId) { }
    @Schema(name="JointFlowDisplayConfiguration")
    public record Configuration(int schemaVersion, Instant startInclusive, Instant endExclusive,
                                List<String> relationVersionIds, List<Dimension> dimensions,
                                List<ClassificationContracts.Configuration> mappings,
                                List<JointFlowContracts.Conversion> conversions, String sourceReference,
                                List<FloorReference> floorReferences) { }
    @Schema(name="JointFlowConfigurationVersion")
    public record Version(String versionId, String buildingId, int versionNo, long revision, String status,
                          Configuration configuration, String configurationHash, long createdBy, Long reviewedBy) { }
    @Schema(name="JointFlowConfigurationCreate")
    public record Create(@NotBlank @Size(max=500) String reason, @Size(max=32) String copiedFromVersionId) { }
    @Schema(name="JointFlowConfigurationSave")
    public record Save(@NotNull @PositiveOrZero Long expectedRevision, @NotNull @Valid Configuration configuration,
                       @NotBlank @Size(max=500) String reason) { }
    @Schema(name="JointFlowConfigurationTransition")
    public record Transition(@NotNull @PositiveOrZero Long expectedRevision, @NotBlank @Size(max=500) String reason) { }
    @Schema(name="JointFlowConfigurationSelect")
    public record Select(@NotNull @PositiveOrZero Long expectedRevision, @NotBlank @Size(max=32) String versionId,
                         @NotBlank @Size(max=500) String reason) { }
    @Schema(name="JointFlowConfigurationSelection")
    public record Selection(String buildingId, String versionId, long revision) { }
}
