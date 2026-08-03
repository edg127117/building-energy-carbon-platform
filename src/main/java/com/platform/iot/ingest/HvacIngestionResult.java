package com.platform.iot.ingest;

import com.platform.iot.quality.TelemetryRejectionReason;

/**
 * 接入服务返回给 MQTT 手动确认层的处理结果。
 *
 * <p>{@code outcome} 区分正常落盘、重复、冲突覆盖、业务拒绝和存储失败；拒绝原因
 * 只用于日志与监控，不要求设备重投。{@link #shouldAcknowledge()} 把这些业务结果
 * 收敛为 QoS 1 ACK 决策，避免协议层重新推导存储语义。</p>
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
