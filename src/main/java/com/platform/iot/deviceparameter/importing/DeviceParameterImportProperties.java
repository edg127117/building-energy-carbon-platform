package com.platform.iot.deviceparameter.importing;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "device-parameter.importing")
/** Excel 解析的技术安全上限；业务参数数量仍由已确认适用关系决定。 */
public class DeviceParameterImportProperties {
    @Min(1_024)
    @Max(20_000_000)
    private int maxFileBytes = 2_000_000;

    @Min(1)
    @Max(20_000)
    private int maxRows = 5_000;

    @Min(16)
    @Max(1_000)
    private int maxTextLength = 255;
}
