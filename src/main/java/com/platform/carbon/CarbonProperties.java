package com.platform.carbon;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "carbon-management")
/** 自动重算、普通计算和数据库领取使用的有界运行参数。 */
public class CarbonProperties {
    private boolean recalculationEnabled = true;
    private int maximumSnapshots = 500;
    private int maximumDetails = 2_000;
    private int maximumBatchItems = 100;
    private int maximumRetries = 3;
    private Duration calculationTimeout = Duration.ofSeconds(20);
    private Duration slowCalculationThreshold = Duration.ofSeconds(5);
    private Duration recalculationScanDelay = Duration.ofSeconds(30);
    private Duration recalculationLease = Duration.ofMinutes(2);
    private Duration retryBackoff = Duration.ofMinutes(1);

    @PostConstruct
    void validate() {
        if (maximumSnapshots < 1 || maximumSnapshots > 100_000
                || maximumDetails < 1 || maximumDetails > 1_000_000
                || maximumBatchItems < 1 || maximumBatchItems > 10_000
                || maximumRetries < 0 || maximumRetries > 100
                || !positive(calculationTimeout) || !positive(slowCalculationThreshold)
                || !positive(recalculationScanDelay) || !positive(recalculationLease)
                || !positive(retryBackoff)) {
            throw new IllegalStateException("碳管理容量、超时或重试配置无效");
        }
    }

    public int getMaximumSnapshots() { return maximumSnapshots; }
    public boolean isRecalculationEnabled() { return recalculationEnabled; }
    public void setRecalculationEnabled(boolean value) { recalculationEnabled = value; }
    public void setMaximumSnapshots(int value) { maximumSnapshots = value; }
    public int getMaximumDetails() { return maximumDetails; }
    public void setMaximumDetails(int value) { maximumDetails = value; }
    public int getMaximumBatchItems() { return maximumBatchItems; }
    public void setMaximumBatchItems(int value) { maximumBatchItems = value; }
    public int getMaximumRetries() { return maximumRetries; }
    public void setMaximumRetries(int value) { maximumRetries = value; }
    public Duration getCalculationTimeout() { return calculationTimeout; }
    public void setCalculationTimeout(Duration value) { calculationTimeout = value; }
    public Duration getSlowCalculationThreshold() { return slowCalculationThreshold; }
    public void setSlowCalculationThreshold(Duration value) { slowCalculationThreshold = value; }
    public Duration getRecalculationScanDelay() { return recalculationScanDelay; }
    public void setRecalculationScanDelay(Duration value) { recalculationScanDelay = value; }
    public Duration getRecalculationLease() { return recalculationLease; }
    public void setRecalculationLease(Duration value) { recalculationLease = value; }
    public Duration getRetryBackoff() { return retryBackoff; }
    public void setRetryBackoff(Duration value) { retryBackoff = value; }

    private static boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }
}
