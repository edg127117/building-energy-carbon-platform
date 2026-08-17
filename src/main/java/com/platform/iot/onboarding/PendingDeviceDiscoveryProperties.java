package com.platform.iot.onboarding;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "device-onboarding.discovery")
/**
 * 待绑定设备发现与清理的集中边界配置。
 *
 * <p>限制只作用于本地 MySQL 待处理样例，不改变标准 MQTT 报文和正式时序链契约。
 * 自动化测试可关闭清理调度，避免后台线程访问测试结束后的数据源。</p>
 */
public class PendingDeviceDiscoveryProperties {

    /** 最近样例最多保留的规范化指标数。 */
    @Min(1)
    @Max(256)
    private int maxMetricCount = 64;

    /** 指标代码和单位允许保留的最大字符数。 */
    @Min(8)
    @Max(512)
    private int maxStringLength = 128;

    /** 最近样例序列化后的最大 UTF-8 字节数。 */
    @Min(512)
    @Max(65_536)
    private int maxSampleBytes = 8_192;

    /** MySQL 短暂失败时一次发现允许的总尝试次数。 */
    @Min(1)
    @Max(5)
    private int maxAttempts = 3;

    /** 两次发现写入之间的固定退避，单位毫秒。 */
    @Min(1)
    @Max(1_000)
    private long retryDelayMs = 50L;

    /** 是否注册待绑定记录清理任务；关闭后保留已有记录。 */
    private boolean cleanupEnabled = true;

    /** DISCOVERED、IGNORED 记录的保留天数。 */
    @Min(1)
    private int retentionDays = 30;

    /** 单条删除 SQL 允许处理的最大记录数。 */
    @Min(1)
    @Max(1_000)
    private int cleanupBatchSize = 200;

    /** 单轮调度最多执行的删除批次数。 */
    @Min(1)
    @Max(100)
    private int cleanupMaxBatches = 5;

    /** 单轮清理的 JVM 执行时间上限，单位毫秒。 */
    @Min(10)
    @Max(60_000)
    private long cleanupTimeoutMs = 5_000L;
}
