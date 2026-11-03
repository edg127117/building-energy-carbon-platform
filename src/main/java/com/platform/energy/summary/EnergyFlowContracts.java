package com.platform.energy.summary;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

/** 月度能流只读契约。选择折标参数只影响本次查看，不写入楼宇配置。 */
public final class EnergyFlowContracts {
    private EnergyFlowContracts() { }

    @Schema(name="EnergyFlowParameterSelection")
    public record Selection(@NotBlank String energyItemCode, @NotBlank String unit,
                            @NotBlank String parameterVersionId) { }
    @Schema(name="EnergyFlowQueryRequest")
    public record Request(@NotBlank @Size(max=32) String buildingId,
                          @NotBlank @Pattern(regexp="(?:20|21|22)\\d{2}-(?:0[1-9]|1[0-2])") String month,
                          String classificationVersionId,
                          @Size(max=16) List<@Valid Selection> selections) { }
    @Schema(name="EnergyFlowParameterOption")
    public record Option(String energyItemCode, String unit, String parameterVersionId,
                         String parameterCode, String method, String perspective,
                         String consumptionScope, String regionCode) { }
    @Schema(name="EnergyFlowNode")
    public record Node(String id, String type, String code, String name) { }
    @Schema(name="EnergyFlowLink")
    public record Link(String source, String target,
                       @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal value) { }
    @Schema(name="EnergyFlowGraph")
    public record Graph(String schemeCode, boolean available, String unavailableReason,
                        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal total,
                        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal unallocated,
                        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal residual,
                        List<Node> nodes, List<Link> links) { }
    @Schema(name="EnergyFlowQueryView")
    public record View(String buildingId, String month, String unit, String state, String reason,
                       String resultNature, String classificationVersionId, String relationVersionId,
                       String resultVersion, List<String> parameterVersionIds,
                       List<String> formulaVersionIds, List<String> standardCoalVersionIds,
                       List<String> sourceEvidenceIds, List<Option> conversionOptions,
                       List<Graph> flows) { }
}
