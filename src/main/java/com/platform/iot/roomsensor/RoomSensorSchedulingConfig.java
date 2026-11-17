package com.platform.iot.roomsensor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 房间采集的网络重试和清理共用独立单线程，不占用现有业务调度池。 */
@Configuration
@ConditionalOnProperty(name = "room-sensor.enabled", havingValue = "true")
public class RoomSensorSchedulingConfig {
    @Bean(name = "roomSensorTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler roomSensorTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("room-sensor-");
        return scheduler;
    }
}
