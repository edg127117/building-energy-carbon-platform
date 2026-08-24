package com.platform.iot.qualityusage;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "quality-usage")
/** 质量使用快照、纠正、恢复和对账的有界运行参数。 */
public class QualityUsageProperties {
    @Min(1)
    private long revisionCheckMs = 60_000L;

    @Min(1)
    private int recoveryBatchSize = 500;

    @Min(1)
    private int realtimeCorrectionQueueCapacity = 1_000;

    @Min(1)
    private int realtimeCorrectionConcurrency = 1;

    @Min(1)
    private int indicatorRecoveryQueueCapacity = 500;

    @Min(1)
    private int indicatorRecoveryConcurrency = 1;

    @Min(1)
    private int reconciliationBatchSize = 200;
}
