package com.platform.audit;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "audit-governance")
/**
 * 审计治理的环境安全开关。
 *
 * <p>研发自审默认关闭，必须显式开启；生产环境即使误配开启也拒绝启动，防止研发例外随配置进入上线环境。</p>
 */
public class AuditGovernanceProperties {
    private AuditEnvironmentMode environmentMode = AuditEnvironmentMode.DEVELOPMENT;
    private boolean allowSelfApproval;

    @PostConstruct
    void validate() {
        if (environmentMode == AuditEnvironmentMode.PRODUCTION && allowSelfApproval) {
            throw new IllegalStateException("生产环境禁止启用审计治理自审例外");
        }
    }

    public AuditEnvironmentMode getEnvironmentMode() {
        return environmentMode;
    }

    public void setEnvironmentMode(AuditEnvironmentMode environmentMode) {
        this.environmentMode = environmentMode;
    }

    public boolean isAllowSelfApproval() {
        return allowSelfApproval;
    }

    public void setAllowSelfApproval(boolean allowSelfApproval) {
        this.allowSelfApproval = allowSelfApproval;
    }
}
