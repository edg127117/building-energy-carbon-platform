package com.platform.iot.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 设备控制功能开关，供 {@code @PreAuthorize} 表达式调用。
 *
 * <p>本期设计为只采集、只计算，因此即使平台管理员已登录，只有配置项
 * {@code features.control-enabled=true} 时控制接口才会开放；默认值为 {@code false}。</p>
 */
@Component("controlFeature")
public class ControlFeature {
    @Value("${features.control-enabled:false}")
    private boolean enabled;

    /** 返回当前部署环境是否允许下发设备控制指令。 */
    public boolean isEnabled() { return enabled; }
}
