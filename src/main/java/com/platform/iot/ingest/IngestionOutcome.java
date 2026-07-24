package com.platform.iot.ingest;

/**
 * HVAC MQTT 报文的最终处理结果。
 */
public enum IngestionOutcome {
    ACCEPTED,
    DUPLICATE,
    CONFLICT_UPDATED,
    REJECTED,
    STORAGE_FAILED
}
