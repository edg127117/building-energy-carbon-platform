package com.platform.iot.daikin.runtime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 运行统计独立限额，不占用分钟采集执行器；开启前须安装确认统计口径的可信适配。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "daikin.runtime")
public class DaikinRuntimeProperties {
    private boolean enabled;
    @Min(1) @Max(10) private int sourceBatchSize = 2;
    @Min(1) @Max(10) private int jobsPerSource = 2;
    @Min(30) @Max(3600) private int leaseSeconds = 180;
    @Min(1) @Max(5) private int maxAttempts = 3;
    @Min(1) @Max(3600) private int retrySeconds = 30;
}
