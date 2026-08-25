package com.platform.iot.ingest.v2;

import com.platform.iot.reliability.AckMode;
import com.platform.iot.reliability.ReceiptStatus;

/** MQTT 回调需要的 V2 处理、应用 ACK 和原入站确认决策。 */
public record V2ProcessingResult(
        String canonicalMessageId,
        ReceiptStatus status,
        String resultCode,
        int processedMetrics,
        boolean retryable,
        AckMode actualAckMode,
        String ackTopic,
        PlatformApplicationAck applicationAck,
        String detail
) {
    public boolean requiresApplicationAck() {
        return !retryable && actualAckMode != AckMode.EVIDENCE_ONLY
                && ackTopic != null && applicationAck != null;
    }
}
