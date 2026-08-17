package com.platform.iot.onboarding;

import com.platform.iot.ingest.standard.StandardTelemetryMessage;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

@Service
/**
 * 将真正未知的标准设备报文写入本地 MySQL 待绑定区。
 *
 * <p>写入最多进行配置次数的短退避重试；耗尽后异常交给 MQTT 未知设备边界记录，
 * 上游仍会确认消息，后续周期报文再次尝试发现。该服务从不调用正式 TDengine 链。</p>
 */
public class PendingDeviceDiscoveryService {

    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    private final PendingDeviceRepository repository;
    private final PendingDeviceSampleEncoder sampleEncoder;
    private final RetryTemplate retryTemplate;

    public PendingDeviceDiscoveryService(
            PendingDeviceRepository repository,
            PendingDeviceSampleEncoder sampleEncoder,
            PendingDeviceDiscoveryProperties properties) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.sampleEncoder = Objects.requireNonNull(sampleEncoder, "sampleEncoder 不能为空");
        Objects.requireNonNull(properties, "properties 不能为空");
        this.retryTemplate = RetryTemplate.builder()
                .maxAttempts(properties.getMaxAttempts())
                .fixedBackoff(properties.getRetryDelayMs())
                .retryOn(RuntimeException.class)
                .build();
    }

    /** 返回本次样例是否因任一上限发生确定性截断。 */
    public boolean discover(
            StandardTelemetryMessage message,
            long localReceivedTime) {
        if (localReceivedTime <= 0) {
            throw new IllegalArgumentException("localReceivedTime 必须大于 0");
        }
        PendingDeviceSample sample = sampleEncoder.encode(message);
        PendingDeviceDiscovery discovery = new PendingDeviceDiscovery(
                UUID.randomUUID().toString().replace("-", ""),
                message.deviceIdentity(),
                message.profileCode(),
                message.profileVersion(),
                LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(localReceivedTime), PROJECT_ZONE),
                message.eventTime(),
                message.timeSource(),
                sample.json(),
                sample.truncated());
        retryTemplate.execute(context -> {
            repository.upsertDiscovery(discovery);
            return null;
        });
        return sample.truncated();
    }
}
