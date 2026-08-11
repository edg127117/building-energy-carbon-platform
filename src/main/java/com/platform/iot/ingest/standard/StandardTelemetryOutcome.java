package com.platform.iot.ingest.standard;

/** 标准多指标报文在本地完成处理后的稳定结果。 */
public enum StandardTelemetryOutcome {
    ACCEPTED,
    REJECTED,
    RETRYABLE_FAILURE
}
