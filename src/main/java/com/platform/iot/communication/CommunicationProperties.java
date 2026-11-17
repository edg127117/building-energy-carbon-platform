package com.platform.iot.communication;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;

/** 工程默认周期只在产品/协议未配置时使用；扫描开关不改变已保存的通讯事实。 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix="device-communication")
public class CommunicationProperties {
    private boolean scanEnabled = true;
    @Min(5) @Max(86400) private int defaultPeriodSeconds = 60;
    @Min(1) @Max(1000) private int batchSize = 200;
    @Min(15) @Max(300) private int sourceLeaseSeconds = 45;
}
