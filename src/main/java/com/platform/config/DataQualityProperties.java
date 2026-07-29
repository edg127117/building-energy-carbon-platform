package com.platform.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * HVAC 分钟数据质量补全的集中配置。
 *
 * <p>该配置控制质量 1 插值、质量 2 典型值、迟到真实数据修正和后台补偿任务。
 * 测试环境可整体关闭补全及收口，避免普通自动化测试连接真实外部资源。</p>
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "data-quality")
public class DataQualityProperties {

    /** 是否启用分钟质量补全层及其管理能力。 */
    private boolean enabled = true;

    /** 质量 1 线性插值参数。 */
    @Valid
    private Interpolation interpolation = new Interpolation();

    /** 迟到质量 0 可自动替换生成数据的最长历史时段，单位小时。 */
    @Min(1)
    private int lateRealCorrectionHours = 24;

    /** 已批准典型值配置快照的刷新间隔，单位毫秒。 */
    @Min(1)
    private long typicalConfigRefreshMs = 60_000L;

    /** 失败补全任务两轮重试之间的等待时间，单位毫秒。 */
    @Min(1)
    private long retryDelayMs = 600_000L;

    /** 是否启用小时批次收口和跨库状态核对。 */
    private boolean reconciliationEnabled = true;

    /**
     * 线性插值只处理有两个质量 0 端点的短缺口，不能串联使用生成数据。
     */
    @Data
    public static class Interpolation {

        /** 右侧真实端点到达后允许回溯插值的最大连续缺失分钟数。 */
        @Min(1)
        private int maxGapMinutes = 5;
    }
}
