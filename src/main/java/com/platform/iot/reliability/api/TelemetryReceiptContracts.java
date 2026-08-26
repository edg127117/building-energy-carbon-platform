package com.platform.iot.reliability.api;

import java.util.List;

/**
 * `/v1/telemetry-receipts` 的稳定只读 DTO，不暴露数据库实体。
 *
 * <p>四个历史 ACK 证据字段为兼容 V1 调用方暂时保留。平台不再逐条持久化
 * 成功投递证据，因此返回 {@code UNKNOWN}/{@code NOT_TRACKED}，成功趋势应从
 * Micrometer 监控读取。</p>
 */
public final class TelemetryReceiptContracts {
    private TelemetryReceiptContracts() {
    }

    public record ReceiptView(
            String canonicalMessageId,
            String buildingId,
            String equipmentId,
            String profileCode,
            String sourceMessageId,
            Long sourceSeq,
            Long collectedAtEpochMillis,
            long adapterReceivedAtEpochMillis,
            long firstPlatformReceivedAtEpochMillis,
            long lastPlatformReceivedAtEpochMillis,
            long persistedAtEpochMillis,
            Long retransmittedAtEpochMillis,
            String batchId,
            String idSource,
            String timeSource,
            String dedupMode,
            String configuredAckMode,
            String actualAckMode,
            String downgradeReason,
            String receiptStatus,
            String resultCode,
            int metricCount,
            int attemptCount,
            String deviceToBrokerPuback,
            String adapterStandardPublishPuback,
            String platformInboundConsumerAck,
            String applicationAckPublishPuback,
            Long applicationAckPublishedAtEpochMillis) {
    }

    public record FailureView(
            String failureStage,
            String failureCode,
            String safeDetail,
            long occurredAtEpochMillis) {
    }

    public record ReceiptDetail(ReceiptView receipt, List<FailureView> failures) {
    }

    public record ReceiptStatistics(
            long persistedMessages,
            long duplicateAttempts,
            long directModeMessages,
            long adapterProxyMessages,
            long evidenceOnlyMessages,
            long conflictFailures,
            long rejectedFailures,
            long applicationAckFailures,
            long windowStartEpochMillis,
            long windowEndEpochMillis,
            String scope) {
    }

    public record FailureStatistics(
            long totalFailures,
            long conflictFailures,
            long rejectedFailures,
            long storageFailures,
            long applicationAckFailures,
            long windowStartEpochMillis,
            long windowEndEpochMillis,
            String scope) {
    }

    public record TransportFailureView(
            long bucketStartEpochMillis,
            String component,
            String failureCategory,
            String brokerEndpoint,
            long occurrenceCount,
            long firstOccurredAtEpochMillis,
            long lastOccurredAtEpochMillis) {
    }
}
