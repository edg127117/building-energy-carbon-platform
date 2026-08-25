package com.platform.iot.deviceparameter;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "device-parameter.recalculation")
/** 参数追溯公式重算的有界调度配置。 */
public class DeviceParameterRecalculationProperties {
    private boolean enabled = true;

    @Min(1)
    @Max(20)
    private int batchSize = 2;

    @Min(1)
    @Max(20)
    private int maxRetries = 5;

    @Min(1)
    @Max(1_440)
    private int chunkMinutes = 60;
}
