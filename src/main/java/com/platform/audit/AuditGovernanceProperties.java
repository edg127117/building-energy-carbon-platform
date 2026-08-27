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
    private Duration queryDefaultRange = Duration.ofDays(1);
    private Duration queryMaxRange = Duration.ofDays(31);
    private int queryDefaultSize = 50;
    private int queryMaxSize = 200;
    private int exportMaxRows = 10_000;
    private Duration exportFileTtl = Duration.ofMinutes(15);
    private Duration exportCleanupDelay = Duration.ofMinutes(1);
    private String exportDirectory = java.nio.file.Path.of(
            System.getProperty("java.io.tmpdir"), "building-energy-carbon-audit-exports").toString();

    @PostConstruct
    void validate() {
        if (environmentMode == AuditEnvironmentMode.PRODUCTION && allowSelfApproval) {
            throw new IllegalStateException("生产环境禁止启用审计治理自审例外");
        }
        if (passwordTokenTtl == null || passwordTokenTtl.isNegative() || passwordTokenTtl.isZero()
                || passwordTokenTtl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalStateException("一次性密码令牌有效期必须大于0且不超过24小时");
        }
        if (!positive(queryDefaultRange) || !positive(queryMaxRange)
                || queryDefaultRange.compareTo(queryMaxRange) > 0) {
            throw new IllegalStateException("审计默认查询窗口必须大于0且不超过最大查询窗口");
        }
        if (queryDefaultSize < 1 || queryMaxSize < queryDefaultSize || queryMaxSize > 1000) {
            throw new IllegalStateException("审计查询页大小配置无效");
        }
        if (exportMaxRows < 1 || exportMaxRows > 1_000_000) {
            throw new IllegalStateException("审计导出行数上限配置无效");
        }
        if (!positive(exportFileTtl) || !positive(exportCleanupDelay)
                || exportDirectory == null || exportDirectory.isBlank()) {
            throw new IllegalStateException("审计导出目录、有效期或清理周期配置无效");
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

    public Duration getQueryDefaultRange() { return queryDefaultRange; }
    public void setQueryDefaultRange(Duration value) { this.queryDefaultRange = value; }
    public Duration getQueryMaxRange() { return queryMaxRange; }
    public void setQueryMaxRange(Duration value) { this.queryMaxRange = value; }
    public int getQueryDefaultSize() { return queryDefaultSize; }
    public void setQueryDefaultSize(int value) { this.queryDefaultSize = value; }
    public int getQueryMaxSize() { return queryMaxSize; }
    public void setQueryMaxSize(int value) { this.queryMaxSize = value; }
    public int getExportMaxRows() { return exportMaxRows; }
    public void setExportMaxRows(int value) { this.exportMaxRows = value; }
    public Duration getExportFileTtl() { return exportFileTtl; }
    public void setExportFileTtl(Duration value) { this.exportFileTtl = value; }
    public Duration getExportCleanupDelay() { return exportCleanupDelay; }
    public void setExportCleanupDelay(Duration value) { this.exportCleanupDelay = value; }
    public String getExportDirectory() { return exportDirectory; }
    public void setExportDirectory(String value) { this.exportDirectory = value; }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
