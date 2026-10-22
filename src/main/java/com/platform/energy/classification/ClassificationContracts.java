package com.platform.energy.classification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 分类配置与原生用量统计契约；分类不赋予碳核算语义，所有数量保留原单位。 */
public final class ClassificationContracts {
    private ClassificationContracts() {}

    @Schema(name="EnergyClassificationScheme")
    public record Scheme(String code, String name, String dimension, String mode) {}
    @Schema(name="EnergyClassificationNode")
    public record Node(String schemeCode, String code, String name, String parentCode) {}
    @Schema(name="EnergyClassificationBinding")
    public record Binding(String schemeCode, String nodeCode, String objectType, String objectId,
                          String meterPointId, String semantics, String status, String evidence) {}
    @Schema(name="EnergyClassificationBoundaryRule")
    public record BoundaryRule(String boundaryId, String mode, String status, String evidence,
                               String energyItemCode, String unit, boolean independentChannelsConfirmed) {
        public BoundaryRule(String boundaryId,String mode,String status,String evidence,String energyItemCode,String unit) {
            this(boundaryId,mode,status,evidence,energyItemCode,unit,false);
        }
    }
    @Schema(name="EnergyClassificationConfiguration")
    public record Configuration(String relationVersionId, List<Scheme> schemes, List<Node> nodes,
                                List<Binding> bindings, List<BoundaryRule> boundaryRules) {}
    @Schema(name="EnergyClassificationCreateRequest")
    public record CreateRequest(@NotBlank @Size(max = 500) String reason, String copiedFromVersionId) {}
    @Schema(name="EnergyClassificationSaveRequest")
    public record SaveRequest(@NotNull Long expectedRevision, @NotNull @Valid Configuration configuration,
                              @NotBlank @Size(max = 500) String reason) {}
    @Schema(name="EnergyClassificationTransitionRequest")
    public record TransitionRequest(@NotNull Long expectedRevision,
                                    @NotBlank @Size(max = 500) String reason, Instant effectiveFrom) {}
    @Schema(name="EnergyClassificationVersionView")
    public record VersionView(String versionId, String buildingId, int versionNo, long revision,
                              String status, Instant effectiveFrom, Instant effectiveTo,
                              long createdBy, Long reviewedBy, Configuration configuration,
                              List<Issue> issues) {}
    @Schema(name="EnergyClassificationIssue")
    public record Issue(String severity, String code, String reference, String message) {}
    @Schema(name="EnergyClassificationFilter")
    public record Filter(String schemeCode, String nodeCode) {}
    @Schema(name="EnergyClassificationQueryRequest")
    public record QueryRequest(@NotNull Instant startInclusive, @NotNull Instant endExclusive,
                               @NotBlank String periodType, @NotBlank String energyItemCode,
                               @NotBlank String unit, @NotBlank String schemeCode,
                               String versionId, List<Filter> filters) {}
    public record NodeResult(String nodeCode, String nodeName, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal quantity,
                             String status, List<String> pointIds, List<String> evidenceIds) {}
    public record Balance(String boundaryId, String pointId, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal quantity,
                          @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal childQuantity, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal residual, String status) {}
    @Schema(name="EnergyClassificationQueryView")
    public record QueryView(String buildingId, String versionId, String relationVersionId,
                            String unit, Instant startInclusive, Instant endExclusive,
                            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal total, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal assigned, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal unallocated,
                            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal residual, String status, String resultNature, String totalKind,
                            boolean additive, List<NodeResult> nodes, List<Balance> balances,
                            List<Issue> issues, List<String> evidenceIds) {}
    @Schema(name="EnergyClassificationReading")
    public record Reading(String pointId, Instant startInclusive, Instant endExclusive,
                          String energyItemCode, String unit, String semantics,
                          @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal startReading, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal endReading, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal quantity,
                          @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal multiplier, boolean alreadyScaled, String status,
                          String sourceReference, String timePrecision, String supersedesFactId) {}
    @Schema(name="EnergyClassificationImportRequest")
    public record ImportRequest(@NotBlank String idempotencyKey, @NotNull @Size(min=1,max=500)
                                 List<Reading> readings, @NotBlank String reason) {}
    @Schema(name="EnergyClassificationImportView")
    public record ImportView(String batchId, String status, int accepted,
                             List<Issue> issues) {}
    public record ReadingBatchView(String batchId, String buildingId, String status,
                                   long revision, long createdBy, int count) {}
    @Schema(name="EnergyClassificationReadingView")
    public record ReadingView(String factId, String batchId, Reading reading,
                              @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal normalizedQuantity, long createdBy, String batchStatus) {}
}
