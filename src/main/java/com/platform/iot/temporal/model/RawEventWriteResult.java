package com.platform.iot.temporal.model;

/**
 * 原始事件幂等写入结果。
 */
public enum RawEventWriteResult {
    INSERTED,
    DUPLICATE,
    CONFLICT_UPDATED
}
