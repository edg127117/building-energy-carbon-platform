package com.platform.modbus.config;

import com.platform.modbus.service.RetrySleeper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;

@Configuration
public class ModbusEdgeConfiguration {

    @Bean
    public Clock modbusEdgeClock() {
        return Clock.systemUTC();
    }

    @Bean
    public RetrySleeper modbusRetrySleeper() {
        return duration -> Thread.sleep(duration.toMillis());
    }

    @Bean(name = "modbusEdgeTaskScheduler")
    public ThreadPoolTaskScheduler modbusEdgeTaskScheduler(ModbusEdgeProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, Math.min(properties.getWorkerThreads(), 64)));
        scheduler.setThreadNamePrefix("modbus-edge-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
