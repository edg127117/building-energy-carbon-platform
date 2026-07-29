package com.platform.iot.dataquality.model;

/**
 * 作废重算批次的执行阶段；范围重算从 {@link #RECALCULATING} 开始。
 */
public enum RecalculationJobPhase {
    VOIDING,
    RECALCULATING
}
