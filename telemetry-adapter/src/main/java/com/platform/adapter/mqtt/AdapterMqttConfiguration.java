package com.platform.adapter.mqtt;

import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 只在适配器 MQTT 开关启用时创建真实 Broker 客户端和重连调度器。 */
@Configuration
@ConditionalOnProperty(
        prefix = "adapter.mqtt",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AdapterMqttConfiguration {

    @Bean
    public IMqttClient adapterMqttClient(AdapterMqttProperties properties) throws MqttException {
        return new MqttClient(
                properties.getBrokerUrl(),
                properties.getClientId(),
                new MemoryPersistence());
    }

    @Bean("adapterMqttTaskScheduler")
    public TaskScheduler adapterMqttTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("adapter-mqtt-retry-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.initialize();
        return scheduler;
    }
}
