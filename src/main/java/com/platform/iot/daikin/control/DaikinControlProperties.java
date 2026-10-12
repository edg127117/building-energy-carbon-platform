package com.platform.iot.daikin.control;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 控制与读取独立启用；默认不注册真实控制网关，也不运行回读任务。 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "daikin.control")
public class DaikinControlProperties {
    private boolean enabled = false;
    private boolean wireConfirmed = false;
    /** 测试期复用当前管理员，无需逐人授予职责；生产默认关闭，设备与建筑边界仍生效。 */
    private boolean testAccessEnabled = false;
}
