package com.platform.iot.dataquality.model;

/**
 * 人工重算批次可恢复执行的技术状态。
 */
public enum RecalculationJobStatus {
    WAITING,
    RUNNING,
    SUCCEEDED,
    FAILED
}
