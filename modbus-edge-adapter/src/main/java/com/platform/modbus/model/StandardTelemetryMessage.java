package com.platform.modbus.model;

import java.util.List;

/**
 * 边缘适配器直发平台内部 Topic 的 V2 契约，不携带可信建筑或平台设备归属。
 */
public record StandardTelemetryMessage(
        String standardVersion,
        String profileCode,
        int profileVersion,
        DeviceIdentity deviceIdentity,
        String canonicalMessageId,
        String sourceMessageId,
        String bootId,
        Long sourceSeq,
        Long collectedAt,
        long adapterReceivedAt,
        Long retransmittedAt,
        String batchId,
        Integer batchItemIndex,
        Integer batchItemCount,
        String idSource,
        String timeSource,
        String dedupMode,
        String declaredAckMode,
        String correlationPolicy,
        List<StandardMetric> metrics) {

    public StandardTelemetryMessage {
        metrics = List.copyOf(metrics);
    }
}
