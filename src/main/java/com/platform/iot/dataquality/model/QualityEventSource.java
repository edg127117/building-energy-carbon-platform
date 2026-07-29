package com.platform.iot.dataquality.model;

/**
 * 质量就绪事件的业务来源，用于决定公式是首次计算还是权威修正。
 */
public enum QualityEventSource {
    NORMAL_FREEZE,
    TYPICAL_FILL,
    INTERPOLATION_CORRECTION,
    LATE_REAL_CORRECTION,
    MANUAL_RECALCULATION
}
