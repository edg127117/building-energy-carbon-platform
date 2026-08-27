package com.platform.audit;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

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
    private Duration passwordTokenTtl = Duration.ofMinutes(15);

    @PostConstruct
    void validate() {
        if (environmentMode == AuditEnvironmentMode.PRODUCTION && allowSelfApproval) {
            throw new IllegalStateException("生产环境禁止启用审计治理自审例外");
        }
        if (passwordTokenTtl == null || passwordTokenTtl.isNegative() || passwordTokenTtl.isZero()
                || passwordTokenTtl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalStateException("一次性密码令牌有效期必须大于0且不超过24小时");
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

    public Duration getPasswordTokenTtl() {
        return passwordTokenTtl;
    }

    public void setPasswordTokenTtl(Duration passwordTokenTtl) {
        this.passwordTokenTtl = passwordTokenTtl;
    }
}
