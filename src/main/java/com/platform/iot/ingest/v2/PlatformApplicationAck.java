package com.platform.iot.ingest.v2;

import com.platform.iot.reliability.AckMode;
import com.platform.iot.reliability.ReceiptStatus;

/** 平台在终态持久化或永久拒绝后发布的应用 ACK。 */
public record PlatformApplicationAck(
        String version,
        String canonicalMessageId,
        ReceiptStatus status,
        String resultCode,
        long platformReceivedAtEpochMillis,
        Long persistedAtEpochMillis,
        AckMode ackMode,
        String deliveryScope
) {
}
