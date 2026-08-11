package com.platform.iot.ingest.standard;

import com.platform.iot.identity.DeviceIdentityKey;

import java.util.List;

/**
 * 本地平台接收的标准多指标报文契约。
 *
 * <p>建筑和平台设备不在报文中传递，必须由本地预注册身份解析；eventTime 是设备事件时间，
 * receivedTime 是云端适配器接收时间，本地迟到判断另用 MQTT 本地接收时间。</p>
 */
public record StandardTelemetryMessage(
        String standardVersion,
        String profileCode,
        int profileVersion,
        DeviceIdentityKey deviceIdentity,
        long eventTime,
        long receivedTime,
        String timeSource,
        Long seq,
        List<StandardMetric> metrics
) {
}
