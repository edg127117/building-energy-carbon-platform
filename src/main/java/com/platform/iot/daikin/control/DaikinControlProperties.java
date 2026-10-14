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
    /** 仅显式 TEST 环境启用；开放空调模块账号/设备许可，并为新操作使用快速目标回显。 */
    private boolean testOpenControls = false;
    private long fastReadbackTimeoutMillis = 60_000;
    /** 原限时测试模式的设备清单；TEST 全开放忽略此值，空清单不赋予设备许可。 */
    private java.util.List<String> testEquipmentIds = java.util.List.of();
    /** 原限时窗口的 epoch 毫秒截止时间；TEST 全开放忽略此值，其他模式保留最后五分钟拦截。 */
    private long testWindowExpiresAt = 0;
}
