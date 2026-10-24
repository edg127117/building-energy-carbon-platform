package com.platform.iot.temporal.model;

/** 原始写入结果；冲突已持久化但未采用，退役数据不重新写入。 */
public enum RawEventWriteResult {
    INSERTED, DUPLICATE, CONFLICT_REJECTED, EXPIRED
}
