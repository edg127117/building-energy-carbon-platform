package com.platform.iot.quality;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

import static com.platform.iot.quality.TelemetryRejectionReason.*;

/**
 * 将不可信 MQTT 载荷校验并映射为平台标准测点身份。
 *
 * <p>校验顺序覆盖必填字段、13 位整数毫秒时间、
 * {@code buildingId + sourceSystem + pointCode} 别名、测点启用状态、设备归属和
 * MySQL 配置的数值上下限。通过后输出 {@link ValidatedHvacTelemetry}，并统一标记为
 * 真实质量 Q0；插值 Q1 和典型值 Q2 只能由平台质量补全流程生成，设备无权声明。</p>
 */
@Component
@RequiredArgsConstructor
public class TelemetryQualityValidator {

    static final long MIN_MILLISECOND_TIMESTAMP = 1_000_000_000_000L;
    static final long MAX_MILLISECOND_TIMESTAMP_EXCLUSIVE = 10_000_000_000_000L;

    private final DataPointConfigProvider configProvider;

    /**
     * 按服务端可信来源解析一条设备上报。
     *
     * <p>环境测点允许没有设备归属；其余测点的 {@code deviceId} 必须匹配 MySQL
     * 设备编码，防止同建筑内串点。任何预期的脏数据都返回固定拒绝码，不以异常
     * 中断 MQTT 消费；只有完整通过后才携带可写入 TDengine 的标准身份。</p>
     */
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
     * 使用默认冻结协议命名空间校验载荷，供不负责选择来源系统的内部调用方使用。
     * MQTT 主链路调用三参数重载，来源系统始终取服务端配置。
     */
    public TelemetryValidationResult validate(Map<String, Object> payload, long receivedTime) {
        return validate(payload, receivedTime, "MQTT_FREEZE_V1");
    }

    private TelemetryValidationResult reject(TelemetryRejectionReason reason, String detail) {
        return TelemetryValidationResult.reject(reason, detail);
    }
}
