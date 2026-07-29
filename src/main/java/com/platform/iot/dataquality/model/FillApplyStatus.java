package com.platform.iot.dataquality.model;

/**
 * 补全任务在 MySQL、TDengine 跨库应用过程中的技术状态。
 *
 * <p>该状态只用于写入、重试和审计，不是等待管理员逐条审批的队列。</p>
 */
public enum FillApplyStatus {
    WAITING,
    APPLIED,
    FAILED,
    REPLACED,
    VOIDED
}
