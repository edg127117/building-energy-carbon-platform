package com.platform.iot.daikin.sync;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 大金后台目录同步的默认关闭、有界运行参数。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "daikin.directory-sync")
public class DaikinDirectorySyncProperties {
    private boolean enabled = false;

    @Min(10_000)
    @Max(3_600_000)
    private long scheduleDelayMs = 10_000;

    @Min(60)
    @Max(2_592_000)
    private long syncIntervalSeconds = 3_600;

    @Min(10)
    @Max(3_600)
    private long leaseSeconds = 120;

    @Min(1)
    @Max(10)
    private int maxAttempts = 3;

    @Min(1)
    @Max(3_600)
    private long baseBackoffSeconds = 30;

    @Min(1)
    @Max(20)
    private int batchSize = 4;
}
