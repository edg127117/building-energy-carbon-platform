package com.platform.iot.deviceparameter.ingest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashSet;
import java.util.Set;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "device-parameter.ingest")
/** 厂家参数报文未确认前保持关闭的设备上行安全开关。 */
public class DeviceParameterIngestProperties {
    /** 未取得硬件契约时默认关闭，避免把合成字段解释为生产事实。 */
    private boolean enabled = false;

    /** 经硬件合同确认后显式配置允许的 FULL/DELTA 语义；空集合拒绝正式写入。 */
    private Set<String> allowedSemantics = new LinkedHashSet<>();

    /** 单份报告参数项的技术保护上限，不代表业务参数数量。 */
    @Min(1)
    @Max(512)
    private int maxItems = 128;
}
