package com.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
/** HVAC WebSocket 生命周期超时的基础装配。 */
public class HvacRealtimeConfig {

    /**
     * 只执行未订阅或心跳过期连接的关闭任务。
     *
     * <p>该 daemon 调度器不参与公式、数据库、MQTT 或重试业务，服务停机时也不等待超时任务，
     * 避免实时会话清理影响既有采集和计算链路的关闭顺序。</p>
     */
    @Bean("hvacRealtimeTaskScheduler")
    public ThreadPoolTaskScheduler hvacRealtimeTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("hvac-realtime-timeout-");
        scheduler.setDaemon(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
