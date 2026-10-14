package com.platform.iot.daikin.control;

import com.platform.audit.AuditEnvironmentMode;
import com.platform.audit.AuditGovernanceProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 测试全开放只改变空调模块；错误环境配置在启动时拒绝，不能误带入生产。 */
@Component
public class DaikinControlTestGuard {
    private final DaikinControlProperties properties;
    private final AuditGovernanceProperties audit;
    public DaikinControlTestGuard(DaikinControlProperties properties, AuditGovernanceProperties audit) {
        this.properties = properties; this.audit = audit;
    }
    @PostConstruct
    void validate() {
        if (properties.isTestOpenControls() && audit.getEnvironmentMode() != AuditEnvironmentMode.TEST)
            throw new IllegalStateException("空调测试全开放仅允许在 TEST 环境启用");
        if (properties.getFastReadbackTimeoutMillis() < 10_000 || properties.getFastReadbackTimeoutMillis() > 300_000)
            throw new IllegalStateException("空调快速回显期限必须为 10～300 秒");
    }
}
