package com.platform.energy.efficiency;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 显式任务的资源预算；上限也是契约边界，超过时拒绝而非截断输入。 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "energy.eerp")
public class EerpLimits {
    @Min(1) @Max(86400) private long maximumPeriodSeconds = 86400;
    @Min(1) @Max(32) private int maximumPoints = 32;
    @Min(366) @Max(5000) private int maximumAnnualSegments = 1024;
    @Min(1) @Max(4) private int maximumConcurrentTasks = 2;
    @Min(5) @Max(60) private int executionTimeoutSeconds = 45;
    @Min(65536) @Max(16777216) private int maximumEvidenceBytes = 8388608;
}
