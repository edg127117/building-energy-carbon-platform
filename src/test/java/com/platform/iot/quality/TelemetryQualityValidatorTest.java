package com.platform.iot.quality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryQualityValidatorTest {

    private static final long EVENT_TIME = 1_784_788_220_000L;
    private static final long RECEIVED_TIME = EVENT_TIME + 1_000L;

    private TelemetryQualityValidator validator;
    private PointRuntimeConfig configuredPoint;

    @BeforeEach
    void setUp() {
        configuredPoint = point("WCR1_TWin", "WCR1", "ONLINE");
        DataPointConfigProvider provider = aliasKey ->
                aliasKey.equals(new PointAliasKey(
                        "BLD001", "MQTT_FREEZE_V1", configuredPoint.pointCode()))
                        ? Optional.of(configuredPoint) : Optional.empty();
        validator = new TelemetryQualityValidator(provider);
    }

    @Test
    void acceptsConfiguredRealPointAndForcesQualityZero() {
        Map<String, Object> payload = payload("WCR1", "WCR1_TWin", 12.3);
        payload.put("dataQuality", 2);

        TelemetryValidationResult result = validator.validate(payload, RECEIVED_TIME);

        assertThat(result.accepted()).isTrue();
        assertThat(result.telemetry().dataQuality()).isZero();
        assertThat(result.telemetry().eventTime()).isEqualTo(EVENT_TIME);
        assertThat(result.telemetry().receivedTime()).isEqualTo(RECEIVED_TIME);
    }

    @Test
    void acceptsGlobalDboPointWithoutEquipmentMatch() {
        configuredPoint = point("DBO_TDB", null, "ONLINE");

        TelemetryValidationResult result =
                validator.validate(payload("WEATHER_GATEWAY", "DBO_TDB", 30.2), RECEIVED_TIME);

        assertThat(result.accepted()).isTrue();
        assertThat(result.telemetry().equipId()).isNull();
    }

    @Test
    void rejectsMalformedPayloadAndNonMillisecondTimestamp() {
        assertThat(validator.validate(Map.of("deviceId", "WCR1"), RECEIVED_TIME).reason())
                .isEqualTo(TelemetryRejectionReason.MALFORMED_PAYLOAD);

        Map<String, Object> secondsTimestamp = payload("WCR1", "WCR1_TWin", 12.3);
        secondsTimestamp.put("timestamp", 1_784_788_220L);
        assertThat(validator.validate(secondsTimestamp, RECEIVED_TIME).reason())
                .isEqualTo(TelemetryRejectionReason.INVALID_TIMESTAMP);

        Map<String, Object> fractionalTimestamp = payload("WCR1", "WCR1_TWin", 12.3);
        fractionalTimestamp.put("timestamp", 1_784_788_220_000.5);
        assertThat(validator.validate(fractionalTimestamp, RECEIVED_TIME).reason())
                .isEqualTo(TelemetryRejectionReason.INVALID_TIMESTAMP);
    }

    @Test
    void rejectsUnknownDisabledAndMismatchedPoints() {
        assertThat(validator.validate(payload("WCR1", "UNKNOWN", 12.3), RECEIVED_TIME).reason())
                .isEqualTo(TelemetryRejectionReason.POINT_NOT_FOUND);

        configuredPoint = point("WCR1_TWin", "WCR1", "OFFLINE");
        assertThat(validator.validate(payload("WCR1", "WCR1_TWin", 12.3), RECEIVED_TIME).reason())
                .isEqualTo(TelemetryRejectionReason.POINT_DISABLED);

        configuredPoint = point("WCR1_TWin", "WCR1", "ONLINE");
        assertThat(validator.validate(payload("OTHER", "WCR1_TWin", 12.3), RECEIVED_TIME).reason())
                .isEqualTo(TelemetryRejectionReason.DEVICE_MISMATCH);
    }

    @Test
    void rejectsNonFiniteNumbers() {
        assertThat(validator.validate(payload("WCR1", "WCR1_TWin", Double.NaN), RECEIVED_TIME).reason())
                .isEqualTo(TelemetryRejectionReason.INVALID_NUMBER);
        assertThat(validator.validate(payload("WCR1", "WCR1_TWin", Double.POSITIVE_INFINITY), RECEIVED_TIME).reason())
                .isEqualTo(TelemetryRejectionReason.INVALID_NUMBER);
    }

    @Test
    void appliesOnlyConfiguredRangeSides() {
        configuredPoint = point("WCR1_TWin", "WCR1", "ONLINE",
                new BigDecimal("5.0"), null);
        assertThat(validator.validate(payload("WCR1", "WCR1_TWin", 4.9), RECEIVED_TIME).reason())
                .isEqualTo(TelemetryRejectionReason.BELOW_MINIMUM);
        assertThat(validator.validate(payload("WCR1", "WCR1_TWin", 500.0), RECEIVED_TIME).accepted())
                .isTrue();

        configuredPoint = point("WCR1_TWin", "WCR1", "ONLINE",
                null, new BigDecimal("60.0"));
        assertThat(validator.validate(payload("WCR1", "WCR1_TWin", 60.1), RECEIVED_TIME).reason())
                .isEqualTo(TelemetryRejectionReason.ABOVE_MAXIMUM);
        assertThat(validator.validate(payload("WCR1", "WCR1_TWin", -100.0), RECEIVED_TIME).accepted())
                .isTrue();
    }

    private Map<String, Object> payload(String deviceId, String pointCode, double value) {
        return new java.util.HashMap<>(Map.of(
                "deviceId", deviceId,
                "buildingId", "BLD001",
                "pointCode", pointCode,
                "val", value,
                "timestamp", EVENT_TIME
        ));
    }

    private PointRuntimeConfig point(String pointCode, String equipCode, String status) {
        return point(pointCode, equipCode, status, null, null);
    }

    private PointRuntimeConfig point(
            String pointCode, String equipCode, String status,
            BigDecimal valueMin, BigDecimal valueMax) {
        return new PointRuntimeConfig(
                "POINT001", pointCode, pointCode, "BLD001", "GROUP001",
                equipCode == null ? null : "EQUIP001", equipCode,
                pointCode.startsWith("DBO") ? "DBO" : "WCR", "MAIN",
                pointCode.substring(pointCode.indexOf('_') + 1),
                status, 1, valueMin, valueMax);
    }
}
