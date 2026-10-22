package com.platform.iot.reliability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.temporal.model.RawTelemetryEvent;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** 规范化已校验的测点事实；接收时间和迟到标志冻结在快照中，但不参与重传内容比较。 */
@Component
public class RawTelemetryCodec {
    private final ObjectMapper mapper;

    public RawTelemetryCodec(ObjectMapper mapper) { this.mapper = mapper; }

    public String key(String pointId) {
        if (pointId == null || !pointId.matches("[A-Za-z0-9_]{1,32}")) {
            throw new IllegalArgumentException("Invalid telemetry point identifier");
        }
        // TDengine 子表标识符不区分大小写，MySQL 冲突键必须采用同一物理身份。
        return pointId.toLowerCase(Locale.ROOT);
    }

    public String encode(RawTelemetryEvent value) {
        if (!Double.isFinite(value.value())) throw new IllegalArgumentException("Finite telemetry value required");
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot encode telemetry snapshot", failure); }
    }

    public RawTelemetryEvent decode(String json) {
        try { return mapper.readValue(json, RawTelemetryEvent.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Invalid telemetry snapshot", failure); }
    }

    public String hash(RawTelemetryEvent e) {
        RawTelemetryEvent semantic = new RawTelemetryEvent(e.pointId(), e.pointCode(), e.sourceSystem(),
                e.sourcePointCode(), e.sourceDeviceId(), e.buildingId(), e.systemGroupId(), e.equipId(),
                e.equipCode(), e.familyCode(), e.componentCode(), e.suffixCode(),
                e.value() == 0 ? 0 : e.value(), e.eventTime(), 0, e.dataQuality(), e.isForCalc(), false);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(encode(semantic).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    public RawTelemetryEvent withValue(RawTelemetryEvent e, double value) {
        return new RawTelemetryEvent(e.pointId(), e.pointCode(), e.sourceSystem(), e.sourcePointCode(),
                e.sourceDeviceId(), e.buildingId(), e.systemGroupId(), e.equipId(), e.equipCode(),
                e.familyCode(), e.componentCode(), e.suffixCode(), value, e.eventTime(), e.receivedTime(),
                e.dataQuality(), e.isForCalc(), e.late());
    }
}
