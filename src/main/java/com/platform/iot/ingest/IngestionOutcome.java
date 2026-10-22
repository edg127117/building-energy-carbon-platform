package com.platform.iot.ingest;

/** 原始事实持久化或冲突证据留存后可确认；存储失败保留 Broker 重投。 */
public enum IngestionOutcome {
    ACCEPTED, DUPLICATE, CONFLICT_REJECTED, REJECTED, STORAGE_FAILED
}
