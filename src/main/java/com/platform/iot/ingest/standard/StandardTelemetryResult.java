package com.platform.iot.ingest.standard;

/**
 * 标准多指标报文的批次结果。
 *
 * @param outcome 批次结果
 * @param processedMetrics 本次已由单点链路成功处理的指标数
 * @param detail 稳定的诊断说明
 */
public record StandardTelemetryResult(
        StandardTelemetryOutcome outcome,
        int processedMetrics,
        String detail
) {
    public static StandardTelemetryResult accepted(int processedMetrics) {
        return new StandardTelemetryResult(
                StandardTelemetryOutcome.ACCEPTED, processedMetrics, null);
    }

    public static StandardTelemetryResult rejected(String detail) {
        return new StandardTelemetryResult(StandardTelemetryOutcome.REJECTED, 0, detail);
    }

    public static StandardTelemetryResult retryable(int processedMetrics, String detail) {
        return new StandardTelemetryResult(
                StandardTelemetryOutcome.RETRYABLE_FAILURE, processedMetrics, detail);
    }

    /** 只有配置或存储等暂时性失败需要保留 MQTT QoS 1 重投。 */
    public boolean shouldAcknowledge() {
        return outcome != StandardTelemetryOutcome.RETRYABLE_FAILURE;
    }
}
