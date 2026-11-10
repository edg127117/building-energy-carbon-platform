package com.platform.carbon;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 联合图是既有冻结结果的只读分析视图；展示重分类不构成核算修订。 */
public final class JointFlowContracts {
    private JointFlowContracts() { }
    @Schema(name="JointFlowConversionSelection")
    public record Conversion(@NotBlank @Size(max=64) String energyItemCode, @NotBlank @Size(max=64) String inputUnitCode,
                             @NotBlank @Size(max=32) String parameterVersionId) { }
    @Schema(name="JointFlowRequest")
    public record Request(@NotBlank @Size(max=32) String buildingId,
                          @NotBlank @Pattern(regexp="MONTH|YEAR") String periodType,
                          @NotBlank @Size(max=7) String period, @Size(max=7) String throughMonth,
                          @Size(max=32) String configurationVersionId) { }
    @Schema(name="JointFlowNode")
    public record Node(String id, String code, String name, int depth,
                       @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal value, int order) { }
    @Schema(name="JointFlowLink")
    public record Link(String source, String target,
                       @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal value) { }
    @Schema(name="JointFlowGraph")
    public record Graph(String state, String reason, String unit,
                        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal total,
                        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal unallocated,
                        List<Node> nodes, List<Link> links) { }
    @Schema(name="JointFlowSource")
    public record Source(String month, String batchId, String classificationVersionId,
                         String relationVersionId, String roundingPolicyVersionId,
                         @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal rawCarbonKg,
                         @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal publishedCarbonTons,
                         String sourceNature, List<String> evidenceHashes) { }
    @Schema(name="JointFlowConversionOption")
    public record Option(String energyItemCode, String parameterVersionId, String method,
                         String perspective, String consumptionScope, String regionCode,
                         String effectiveFrom, String effectiveTo) { }
    @Schema(name="JointFlowDimensionCoverage")
    public record DimensionCoverage(String key, int allocatedAtoms, int unallocatedAtoms) { }
    @Schema(name="JointFlowCoverage")
    public record Coverage(int totalAtoms, int allocatedAtoms, List<DimensionCoverage> dimensions) { }
    @Schema(name="JointFlowView")
    public record View(String buildingId, String periodType, String period, String throughMonth,
                       String timezoneId, Instant startInclusive, Instant endExclusive,
                       String state, String classificationBasis, String configurationVersionId,
                       String configurationHash, String resultVersion, List<String> missingMonths,
                       List<Source> sources, List<String> parameterVersionIds,
                       List<String> formulaVersionIds, List<String> standardCoalVersionIds,
                       List<Option> conversionOptions, List<String> warnings, Graph carbon, Graph energy,
                       List<JointFlowConfigurationContracts.Dimension> dimensions, Coverage coverage) { }
}
