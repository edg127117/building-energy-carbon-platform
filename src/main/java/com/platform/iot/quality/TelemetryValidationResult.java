package com.platform.iot.quality;

/**
 * 遥测校验的互斥结果，避免用异常表达预期的脏数据分支。
 *
 * <p>通过时 {@code telemetry} 非空且拒绝字段为空；拒绝时只携带稳定原因码和诊断
 * 明细，不产生可写入 TDengine 的数据。接入服务用原因码记录指标，MQTT 层仍会确认
 * 这类无法通过重投修复的业务拒绝。</p>
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
