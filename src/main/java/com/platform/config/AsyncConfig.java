package com.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.Executors;

/**
 * 注册数据质量后台任务使用的异步执行器，并开启 Spring 定时调度。
 *
 * <p>{@code virtualThreadExecutor} 当前用于迟到真实数据修正，使 TDengine 回读、
 * 分钟重聚合和公式重算不占用 MQTT 事件线程；定时调度则承载补全恢复、跨库收口
 * 和人工重算扫描。这里仅装配执行机制，不决定任务是否启用，业务开关由各组件的
 * {@code data-quality.*} 条件控制。</p>
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /** 为每个迟到数据修正任务分配虚拟线程，避免外部存储等待耗尽平台线程池。 */
    @Bean("virtualThreadExecutor")
    public AsyncTaskExecutor asyncTaskExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
