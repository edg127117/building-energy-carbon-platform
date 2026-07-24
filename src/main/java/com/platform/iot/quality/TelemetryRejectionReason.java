package com.platform.iot.quality;

/**
 * HVAC 遥测数据拒绝原因。
 *
 * <p>使用固定编码而不是自由文本，便于日志检索、监控计数和后续运维统计。</p>
 */
public enum TelemetryRejectionReason {
    MALFORMED_PAYLOAD,
    INVALID_TIMESTAMP,
    POINT_NOT_FOUND,
    POINT_DISABLED,
    DEVICE_MISMATCH,
    INVALID_NUMBER,
    BELOW_MINIMUM,
    ABOVE_MAXIMUM
}
