package com.platform.carbon;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 年度计划使用北京时间、tCO2e；十进制以字符串传输，空实际值不表示零。 */
public final class CarbonPlanContracts {
    private CarbonPlanContracts() { }
    public record BaselineMonth(int month,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal value,
            String unit, String source, String batchId, String evidenceHash, String referenceVersionId) { }
    public record Baseline(String nature, Integer sourceYear, String timezoneId, String boundary,
            String source, List<BaselineMonth> months,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal annualBaseline, String evidenceHash) { }
    public record Configuration(@NotNull @Valid Baseline baseline,
            @NotNull @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal reductionPercent) { }
    public record Create(@Min(2000) @Max(2200) int year, String copiedFromVersionId,
            @NotBlank @Size(max=500) String reason) { }
    public record Save(@NotNull @Min(0) Long expectedRevision, @NotNull @Valid Configuration configuration,
            @NotBlank @Size(max=500) String reason) { }
    public record Transition(@NotNull @Min(0) Long expectedRevision, @NotBlank @Size(max=500) String reason) { }
    public record Version(String versionId, String buildingId, int year, int versionNo, long revision,
            String status, long createdBy, Long reviewedBy, Long activatedBy, Instant activatedAt,
            String copiedFromVersionId, Configuration configuration) { }
    public record Month(int month,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal baseline,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal quota,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal actual,
            String actualStatus, String batchId, String evidenceHash, String referenceVersionId,
            Instant startInclusive, Instant endExclusive) { }
    public record Response(String buildingId, int year, String timezoneId, String unit, String status,
            String throughMonth, String latestCompletedMonth, List<Integer> missingMonths,
            Version plan,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal annualBaseline,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal reductionPercent,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal annualQuota,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal actualCumulative,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal remainingQuota, List<Month> months) { }
}
