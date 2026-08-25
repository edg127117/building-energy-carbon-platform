package com.platform.iot.ingest.v2;

import com.platform.iot.reliability.AckMode;

/** 可信配置和本次报文共同决定的实际 ACK 路由。 */
public record AckModeResolution(
        AckMode configuredMode,
        AckMode actualMode,
        String ackTopic,
        String downgradeReason
) {
}
