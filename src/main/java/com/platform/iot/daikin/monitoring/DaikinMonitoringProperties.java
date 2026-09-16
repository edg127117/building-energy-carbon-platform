package com.platform.iot.daikin.monitoring;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 分钟采集默认关闭；批次、租约和重试有界，不因打开页面增加厂家请求。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "daikin.monitoring")
public class DaikinMonitoringProperties {
    private boolean enabled = false;
    @Min(60) @Max(3600) private int intervalSeconds = 60;
    @Min(30) @Max(3600) private int leaseSeconds = 180;
    @Min(1) @Max(5) private int maxAttempts = 3;
    @Min(1) @Max(300) private int retrySeconds = 5;
    @Min(1) @Max(10) private int sourceBatchSize = 2;
    @Min(1) @Max(100) private int replayBatchSize = 20;
    @Min(100) @Max(100000) private int maxPendingObservations = 10000;
}
