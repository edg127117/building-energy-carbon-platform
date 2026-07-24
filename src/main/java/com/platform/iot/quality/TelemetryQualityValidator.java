package com.platform.iot.quality;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

import static com.platform.iot.quality.TelemetryRejectionReason.*;

/**
 * HVAC 真实遥测质量入口。
 *
 * <p>负责把不可信 MQTT 载荷转换为可信内部数据。本阶段只接收真实数据，
 * 因此通过校验后统一写成质量等级 0，不接受设备自行声明插值或典型值。</p>
 */
@Component
@RequiredArgsConstructor
public class TelemetryQualityValidator {

    static final long MIN_MILLISECOND_TIMESTAMP = 1_000_000_000_000L;
    static final long MAX_MILLISECOND_TIMESTAMP_EXCLUSIVE = 10_000_000_000_000L;

    private final DataPointConfigProvider configProvider;

    public TelemetryValidationResult validate(
            Map<String, Object> payload,
            long receivedTime,
            String sourceSystem) {
        if (payload == null) {
            return reject(MALFORMED_PAYLOAD, "MQTT载荷为空");
        }

        Object buildingObject = payload.get("buildingId");
        Object deviceObject = payload.get("deviceId");
        Object pointObject = payload.get("pointCode");
        Object valueObject = payload.get("val");
        Object timestampObject = payload.get("timestamp");
        if (!(buildingObject instanceof String buildingId) || buildingId.isBlank()
                || !(deviceObject instanceof String deviceId) || deviceId.isBlank()
                || !(pointObject instanceof String pointCode) || pointCode.isBlank()
                || !(valueObject instanceof Number number)
                || !(timestampObject instanceof Number timestampNumber)
                || sourceSystem == null || sourceSystem.isBlank()) {
            return reject(MALFORMED_PAYLOAD,
                    "buildingId、deviceId、pointCode、val、timestamp缺失或类型错误");
        }

        long eventTime;
        try {
            // timestamp 必须能无损转换为整数毫秒；12.5 这类小数不能被静默截断成 12。
            eventTime = new BigDecimal(timestampNumber.toString()).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            return reject(INVALID_TIMESTAMP, "timestamp必须是整数Unix毫秒时间戳");
        }
        if (eventTime < MIN_MILLISECOND_TIMESTAMP
                || eventTime >= MAX_MILLISECOND_TIMESTAMP_EXCLUSIVE) {
            return reject(INVALID_TIMESTAMP, "timestamp必须是13位Unix毫秒时间戳");
        }

        PointRuntimeConfig config = configProvider.find(
                new PointAliasKey(buildingId, sourceSystem, pointCode)).orElse(null);
        if (config == null) {
            return reject(POINT_NOT_FOUND,
                    "来源测点未配置: buildingId=" + buildingId + ", pointCode=" + pointCode);
        }
        if (!"ONLINE".equalsIgnoreCase(config.status())) {
            return reject(POINT_DISABLED, "测点未启用: " + pointCode);
        }

        // DBO 环境测点没有设备归属；其它测点必须由配置中的设备上报，防止串点。
        if (config.equipId() != null && !deviceId.equals(config.equipCode())) {
            return reject(DEVICE_MISMATCH,
                    "deviceId=" + deviceId + " 与测点设备编码="
                            + config.equipCode() + " 不一致");
        }

        double value = number.doubleValue();
        if (!Double.isFinite(value)) {
            return reject(INVALID_NUMBER, "测点值不是有限数值");
        }

        BigDecimal decimalValue = BigDecimal.valueOf(value);
        if (config.valueMin() != null && decimalValue.compareTo(config.valueMin()) < 0) {
            return reject(BELOW_MINIMUM,
                    "测点值=" + value + " 低于配置下限=" + config.valueMin());
        }
        if (config.valueMax() != null && decimalValue.compareTo(config.valueMax()) > 0) {
            return reject(ABOVE_MAXIMUM,
                    "测点值=" + value + " 高于配置上限=" + config.valueMax());
        }

        ValidatedHvacTelemetry telemetry = new ValidatedHvacTelemetry(
                config.pointId(),
                config.pointCode(),
                sourceSystem,
                pointCode,
                deviceId,
                config.buildingId(),
                config.systemGroupId(),
                config.equipId(),
                config.equipCode(),
                config.familyCode(),
                config.componentCode(),
                config.suffixCode(),
                value,
                eventTime,
                receivedTime,
                0,
                config.isForCalc()
        );
        return TelemetryValidationResult.accept(telemetry);
    }

    /**
     * 测试和非MQTT调用的兼容入口；正式MQTT链路显式传入可信来源配置。
     */
    public TelemetryValidationResult validate(Map<String, Object> payload, long receivedTime) {
        return validate(payload, receivedTime, "MQTT_FREEZE_V1");
    }

    private TelemetryValidationResult reject(TelemetryRejectionReason reason, String detail) {
        return TelemetryValidationResult.reject(reason, detail);
    }
}
