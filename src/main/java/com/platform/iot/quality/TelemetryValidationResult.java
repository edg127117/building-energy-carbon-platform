package com.platform.iot.quality;

/**
 * 遥测校验结果，同时表达“通过”或“拒绝”，避免使用异常处理正常的脏数据分支。
 */
public record TelemetryValidationResult(
        boolean accepted,
        ValidatedHvacTelemetry telemetry,
        TelemetryRejectionReason reason,
        String detail
) {
    public static TelemetryValidationResult accept(ValidatedHvacTelemetry telemetry) {
        return new TelemetryValidationResult(true, telemetry, null, null);
    }

    public static TelemetryValidationResult reject(TelemetryRejectionReason reason, String detail) {
        return new TelemetryValidationResult(false, null, reason, detail);
    }
}
