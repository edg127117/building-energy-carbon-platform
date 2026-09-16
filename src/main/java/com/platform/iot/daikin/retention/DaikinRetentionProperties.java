package com.platform.iot.daikin.retention;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 大金物理清理的单轮工作预算；业务保留天数固定在清理任务中，不开放环境改写。 */
@Component
@Validated
@ConfigurationProperties(prefix = "daikin.retention")
public class DaikinRetentionProperties {
    @Min(1) @Max(1000)
    private int batchSize = 200;
    @Min(1) @Max(100)
    private int maximumBatchesPerRun = 12;
    @Min(1) @Max(3600)
    private long leaseSeconds = 120;
    @Min(1) @Max(30)
    private int tdengineWindowDays = 7;

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaximumBatchesPerRun() {
        return maximumBatchesPerRun;
    }

    public void setMaximumBatchesPerRun(int maximumBatchesPerRun) {
        this.maximumBatchesPerRun = maximumBatchesPerRun;
    }

    public long getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(long leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public int getTdengineWindowDays() {
        return tdengineWindowDays;
    }

    public void setTdengineWindowDays(int tdengineWindowDays) {
        this.tdengineWindowDays = tdengineWindowDays;
    }
}
