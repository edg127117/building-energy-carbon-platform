package com.platform.iot.identity;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
/**
 * 未预注册设备的 V1 异常处理器。
 *
 * <p>当前只记录告警和低基数指标，不创建设备、不推断建筑，也不写正式时序数据。
 * 设备身份值保留在日志中供运维定位，但不会作为指标标签，避免监控系统基数失控。</p>
 */
public class UnknownDeviceHandler {

    private final MeterRegistry meterRegistry;

    public void recordRejected(DeviceIdentityKey key, String profileCode) {
        String safeProfile = profileCode == null || profileCode.isBlank()
                ? "UNKNOWN" : profileCode;
        Counter.builder("iot.telemetry.unknown-device")
                .description("未完成本地预注册而被拒绝的设备报文数")
                .tag("identity.type", key.type())
                .tag("profile.code", safeProfile)
                .register(meterRegistry)
                .increment();
        log.warn("未知设备报文已拒绝正式存储: identityType={}, identityValue={}, profile={}",
                key.type(), key.value(), safeProfile);
    }
}
