package com.platform.modbus.service;

import com.platform.modbus.config.ModbusEdgeProperties;
import com.platform.modbus.config.ModbusEdgeProperties.Device;
import com.platform.modbus.model.StandardTelemetryMessage;
import com.platform.modbus.model.TelemetryMessageFactory;
import com.platform.modbus.mqtt.TelemetryPublishException;
import com.platform.modbus.mqtt.TelemetryPublisher;
import com.platform.modbus.protocol.ModbusReadValues;
import com.platform.modbus.protocol.ModbusSession;
import com.platform.modbus.protocol.ModbusTransportException;
import com.platform.modbus.protocol.ModbusTransportFactory;
import com.platform.modbus.protocol.ReadPlanBuilder;
import com.platform.modbus.protocol.ReadPlanBuilder.ReadBlock;
import com.platform.modbus.protocol.RegisterValueDecoder;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每次采样先完整读取并转换全部测点，再生成一条V2消息；部分成功不会发布半包。
 */
@Component
@ConditionalOnProperty(prefix = "modbus-edge", name = "enabled", havingValue = "true")
public class ModbusPollingService {

    private static final Logger log = LoggerFactory.getLogger(ModbusPollingService.class);

    private final ModbusEdgeProperties properties;
    private final ReadPlanBuilder planBuilder;
    private final RegisterValueDecoder decoder;
    private final ModbusTransportFactory transportFactory;
    private final TelemetryMessageFactory messageFactory;
    private final TelemetryPublisher publisher;
    private final RetrySleeper sleeper;
    private final MeterRegistry meterRegistry;

    public ModbusPollingService(
            ModbusEdgeProperties properties,
            ReadPlanBuilder planBuilder,
            RegisterValueDecoder decoder,
            ModbusTransportFactory transportFactory,
            TelemetryMessageFactory messageFactory,
            TelemetryPublisher publisher,
            RetrySleeper sleeper,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.planBuilder = planBuilder;
        this.decoder = decoder;
        this.transportFactory = transportFactory;
        this.messageFactory = messageFactory;
        this.publisher = publisher;
        this.sleeper = sleeper;
        this.meterRegistry = meterRegistry;
    }

    public PollResult poll(Device device) {
        Map<String, BigDecimal> values = readWithRetry(device);
        if (values == null) {
            return PollResult.READ_FAILED;
        }
        StandardTelemetryMessage message = messageFactory.create(device, values);
        if (!publishWithRetry(device, message)) {
            return PollResult.PUBLISH_FAILED;
        }
        meterRegistry.counter("modbus.edge.poll", "result", "success").increment();
        return PollResult.SUCCESS;
    }

    private Map<String, BigDecimal> readWithRetry(Device device) {
        List<ReadBlock> plan = planBuilder.build(device.getPoints());
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try (ModbusSession session = transportFactory.open(device.getConnection())) {
                Map<String, BigDecimal> values = new LinkedHashMap<>();
                for (ReadBlock block : plan) {
                    ModbusReadValues response = session.read(
                            block.function(),
                            block.startAddress(),
                            block.quantity(),
                            device.getConnection().getUnitId());
                    block.items().forEach(item -> values.put(
                            item.point().getCode(),
                            decoder.decode(item.point(), response, item.offset())));
                }
                return values;
            } catch (ModbusTransportException | IllegalArgumentException exception) {
                String failureType = exception instanceof ModbusTransportException transport
                        ? transport.failureType().name() : "DECODE";
                meterRegistry.counter("modbus.edge.read", "result", "failure",
                        "failure.type", failureType).increment();
                if (attempt == properties.getMaxAttempts()) {
                    log.error("Modbus读取重试耗尽: device={}, failureType={}, attempts={}, reason={}",
                            device.getName(), failureType, attempt, exception.getMessage());
                    return null;
                }
                log.warn("Modbus读取失败准备重试: device={}, failureType={}, attempt={}",
                        device.getName(), failureType, attempt);
                if (!pause(properties.getRetryDelay())) {
                    return null;
                }
            }
        }
        return null;
    }

    private boolean publishWithRetry(Device device, StandardTelemetryMessage message) {
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try {
                publisher.publish(message);
                return true;
            } catch (TelemetryPublishException exception) {
                meterRegistry.counter("modbus.edge.publish", "result", "failure").increment();
                if (attempt == properties.getMaxAttempts()) {
                    log.error("V2发布重试耗尽且当前无本地持久化恢复能力: device={}, "
                                    + "attempts={}, reason={}",
                            device.getName(), attempt, exception.getMessage());
                    return false;
                }
                log.warn("V2发布失败准备重试: device={}, attempt={}",
                        device.getName(), attempt);
                if (!pause(properties.getRetryDelay())) {
                    return false;
                }
            }
        }
        return false;
    }

    private boolean pause(Duration duration) {
        try {
            sleeper.sleep(duration);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Modbus采集重试被中断");
            return false;
        }
    }

    public enum PollResult {
        SUCCESS,
        READ_FAILED,
        PUBLISH_FAILED
    }
}
