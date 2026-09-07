package com.platform.modbus.service;

import com.platform.modbus.config.ModbusConfigurationValidator;
import com.platform.modbus.config.ModbusEdgeProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/** 为每台启用设备建立独立固定间隔任务，避免单设备超时阻塞其他设备。 */
@Component
@ConditionalOnProperty(prefix = "modbus-edge", name = "enabled", havingValue = "true")
public class ModbusEdgeRuntime {

    private final ModbusEdgeProperties properties;
    private final ModbusConfigurationValidator validator;
    private final ModbusPollingService pollingService;
    private final TaskScheduler scheduler;
    private final List<ScheduledFuture<?>> tasks = new ArrayList<>();

    public ModbusEdgeRuntime(
            ModbusEdgeProperties properties,
            ModbusConfigurationValidator validator,
            ModbusPollingService pollingService,
            @Qualifier("modbusEdgeTaskScheduler") TaskScheduler scheduler) {
        this.properties = properties;
        this.validator = validator;
        this.pollingService = pollingService;
        this.scheduler = scheduler;
    }

    @PostConstruct
    public void start() {
        validator.validate(properties);
        properties.getDevices().stream()
                .filter(ModbusEdgeProperties.Device::isEnabled)
                .forEach(device -> tasks.add(scheduler.scheduleWithFixedDelay(
                        () -> pollingService.poll(device),
                        Instant.now(),
                        device.getPollInterval())));
    }

    @PreDestroy
    public void stop() {
        tasks.forEach(task -> task.cancel(false));
        tasks.clear();
    }
}
