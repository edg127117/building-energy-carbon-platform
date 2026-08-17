package com.platform.iot.identity;

import com.platform.iot.ingest.standard.StandardTelemetryMessage;
import com.platform.iot.onboarding.PendingDeviceDiscoveryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
/**
 * 未登记设备的有界发现与 MQTT 确认边界。
 *
 * <p>只把规范化有限样例交给本地 MySQL 待绑定服务。即使写入和有限重试失败，
 * 上游仍按未知设备业务拒绝并确认消息；这里不创建设备、不推断建筑，也不调用
 * 正式时序链。日志和指标均不记录完整身份值。</p>
 */
public class UnknownDeviceHandler {

    private final PendingDeviceDiscoveryService discoveryService;
    private final MeterRegistry meterRegistry;

    public UnknownDeviceHandler(
            PendingDeviceDiscoveryService discoveryService,
            MeterRegistry meterRegistry) {
        this.discoveryService = discoveryService;
        this.meterRegistry = meterRegistry;
    }

    public void recordDiscovered(
            StandardTelemetryMessage message,
            long localReceivedTime) {
        String safeProfile = message.profileCode() == null
                || message.profileCode().isBlank()
                ? "UNKNOWN" : message.profileCode();
        Counter.builder("iot.telemetry.unknown-device")
                .description("未在本地业务库登记而被拒绝正式存储的设备报文数")
                .tag("identity.type", message.deviceIdentity().type())
                .tag("profile.code", safeProfile)
                .register(meterRegistry)
                .increment();
        try {
            boolean truncated = discoveryService.discover(message, localReceivedTime);
            meterRegistry.counter("iot.device-onboarding.discovery",
                    "outcome", "recorded").increment();
            if (truncated) {
                meterRegistry.counter("iot.device-onboarding.discovery",
                        "outcome", "truncated").increment();
            }
            log.warn("未知设备已记录待绑定样例并拒绝正式存储: identityType={}, profile={}, truncated={}",
                    message.deviceIdentity().type(), safeProfile, truncated);
        } catch (RuntimeException exception) {
            meterRegistry.counter("iot.device-onboarding.discovery",
                    "outcome", "failed").increment();
            log.error("未知设备待绑定记录失败，消息仍确认且不写正式时序: identityType={}, profile={}",
                    message.deviceIdentity().type(), safeProfile, exception);
        }
    }
}
