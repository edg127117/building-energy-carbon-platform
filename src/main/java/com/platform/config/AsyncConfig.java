package com.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.Objects;
import java.util.concurrent.ThreadPoolExecutor;
import com.platform.iot.qualityusage.QualityUsageProperties;

/**
 * 隔离 IoT 业务定时扫描、人工重算和迟到真实数据修正的执行资源。
 *
 * <p>名称为 {@code taskScheduler} 的 Bean 是 Spring 业务定时任务的明确默认入口，
 * 防止它们误用 MQTT 或 WebSocket 专用调度器。人工重算扫描与 TDengine 分块执行
 * 继续隔离，迟到修正使用有界队列；这里不决定业务开关，开关仍由各组件控制。</p>
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    private final DataQualityProperties properties;
    private final int businessSchedulerPoolSize;
    private final QualityUsageProperties qualityUsageProperties;

    @Autowired
    public AsyncConfig(
            DataQualityProperties properties,
            QualityUsageProperties qualityUsageProperties,
            @Value("${scheduling.business-pool-size:4}")
            int businessSchedulerPoolSize) {
        this.properties = Objects.requireNonNull(
                properties, "properties 不能为空");
        this.qualityUsageProperties = Objects.requireNonNull(
                qualityUsageProperties, "qualityUsageProperties 不能为空");
        if (businessSchedulerPoolSize < 1) {
            throw new IllegalArgumentException(
                    "businessSchedulerPoolSize 必须大于 0");
        }
        this.businessSchedulerPoolSize = businessSchedulerPoolSize;
    }

    /** 兼容只验证既有执行器的单元测试。 */
    AsyncConfig(DataQualityProperties properties, int businessSchedulerPoolSize) {
        this(properties, new QualityUsageProperties(), businessSchedulerPoolSize);
    }

    /** 承载普通业务定时任务，禁止框架从 WebSocket/MQTT 专用 Bean 中随机选择。 */
    @Bean("taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(businessSchedulerPoolSize);
        scheduler.setThreadNamePrefix("iot-business-scheduling-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    /** 只执行人工重算的 MySQL 扫描、条件领取和工作提交。 */
    @Bean("recalculationScanTaskScheduler")
    public ThreadPoolTaskScheduler recalculationScanTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("iot-recalculation-scan-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    /**
     * 有界执行迟到 Q0 修正；队列满时拒绝提交，原始证据由低频扫描恢复。
     */
    @Bean("lateRealCorrectionExecutor")
    public ThreadPoolTaskExecutor lateRealCorrectionExecutor() {
        return boundedExecutor(
                properties.getLateRealCorrectionConcurrency(),
                properties.getLateRealCorrectionQueueCapacity(),
                "iot-late-real-");
    }

    /**
     * 人工分块使用零容量直接交接；线程满时任务退回 MySQL WAITING，不在 JVM 排队。
     */
    @Bean("recalculationJobExecutor")
    public ThreadPoolTaskExecutor recalculationJobExecutor() {
        return boundedExecutor(
                properties.getRecalculationWorkerConcurrency(),
                0,
                "iot-recalculation-");
    }

    /** 质量策略变化后的测点状态纠正，队列满时丢弃旧任务而不反压采集。 */
    @Bean("qualityUsageRealtimeExecutor")
    public ThreadPoolTaskExecutor qualityUsageRealtimeExecutor() {
        return boundedExecutor(qualityUsageProperties.getRealtimeCorrectionConcurrency(),
                qualityUsageProperties.getRealtimeCorrectionQueueCapacity(),
                "iot-quality-usage-realtime-");
    }

    /** 指标策略恢复与重算使用独立队列，避免 WebSocket 拥塞占用计算资源。 */
    @Bean("qualityUsageIndicatorExecutor")
    public ThreadPoolTaskExecutor qualityUsageIndicatorExecutor() {
        return boundedExecutor(qualityUsageProperties.getIndicatorRecoveryConcurrency(),
                qualityUsageProperties.getIndicatorRecoveryQueueCapacity(),
                "iot-quality-usage-indicator-");
    }

    /** 审计导出使用单独的有界执行器，避免大文件生成占用业务调度线程。 */
    @Bean("auditExportExecutor")
    public ThreadPoolTaskExecutor auditExportExecutor() {
        return boundedExecutor(1, 20, "audit-export-");
    }

    private ThreadPoolTaskExecutor boundedExecutor(
            int concurrency,
            int queueCapacity,
            String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(
                new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
