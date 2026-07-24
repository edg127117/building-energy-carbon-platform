package com.platform.iot.ingest;

import com.platform.iot.quality.TelemetryRejectionReason;

/**
 * MQTT 确认层可直接使用的接入结果。
 */
public record HvacIngestionResult(
        IngestionOutcome outcome,
        TelemetryRejectionReason rejectionReason,
        String detail
) {
    public static HvacIngestionResult of(IngestionOutcome outcome) {
        return new HvacIngestionResult(outcome, null, null);
    }

    public static HvacIngestionResult rejected(TelemetryRejectionReason reason, String detail) {
        return new HvacIngestionResult(IngestionOutcome.REJECTED, reason, detail);
    }

    public static HvacIngestionResult storageFailed(String detail) {
        return new HvacIngestionResult(IngestionOutcome.STORAGE_FAILED, null, detail);
    }

    /**
     * 存储失败不向 EMQX 确认，为 QoS 1 保留重新投递机会。
     */
    public boolean shouldAcknowledge() {
        return outcome != IngestionOutcome.STORAGE_FAILED;
    }
}
