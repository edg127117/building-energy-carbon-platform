package com.platform.iot.onboarding;

import com.platform.iot.identity.DeviceIdentityKey;

import java.time.LocalDateTime;

/**
 * 一次未知设备发现写入所需的规范化数据。
 *
 * <p>这里只携带云端标准报文中的允许字段；原始厂商载荷、来源字段路径和任何凭据
 * 都不会进入本地待绑定表。</p>
 */
public record PendingDeviceDiscovery(
        String pendingId,
        DeviceIdentityKey identity,
        String profileCode,
        int profileVersion,
        LocalDateTime seenAt,
        long eventTime,
        String timeSource,
        String metricsJson,
        boolean sampleTruncated
) {
}
