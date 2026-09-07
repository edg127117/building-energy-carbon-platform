package com.platform.modbus.model;

import com.platform.modbus.config.ModbusEdgeProperties.Device;
import com.platform.modbus.config.ModbusEdgeProperties.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 用进程启动号和单调序号生成可关联身份；进程重启会更换启动号，避免序号复用冲突。
 */
@Component
public class TelemetryMessageFactory {

    private final Clock clock;
    private final String bootId;
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    @Autowired
    public TelemetryMessageFactory(Clock clock) {
        this(clock, UUID.randomUUID().toString());
    }

    TelemetryMessageFactory(Clock clock, String bootId) {
        this.clock = clock;
        this.bootId = bootId;
    }

    public StandardTelemetryMessage create(Device device, Map<String, BigDecimal> values) {
        String identityScope = device.getIdentityType() + '\u001f' + device.getIdentityValue();
        long sequence = sequences.computeIfAbsent(identityScope, ignored -> new AtomicLong())
                .incrementAndGet();
        long receivedAt = clock.millis();
        String canonicalId = hash(identityScope + '\u001f' + bootId + '\u001f' + sequence);
        List<StandardMetric> metrics = device.getPoints().stream()
                .map(point -> metric(point, values))
                .toList();
        return new StandardTelemetryMessage(
                "2.0",
                device.getProfileCode(),
                device.getProfileVersion(),
                new DeviceIdentity(device.getIdentityType(), device.getIdentityValue()),
                canonicalId,
                null,
                bootId,
                sequence,
                null,
                receivedAt,
                null,
                null,
                null,
                null,
                "ADAPTER_DERIVED",
                "ADAPTER_RECEIVED",
                "DERIVED",
                "ADAPTER_PROXY",
                "BOOT_ID_AND_SEQ",
                metrics);
    }

    private StandardMetric metric(Point point, Map<String, BigDecimal> values) {
        BigDecimal value = values.get(point.getCode());
        if (value == null) {
            throw new IllegalArgumentException("缺少已读取测点: " + point.getCode());
        }
        String sourceField = point.getFunction().name() + ':' + point.getAddress();
        return new StandardMetric(point.getCode(), value, point.getUnit(), sourceField);
    }

    private String hash(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK缺少SHA-256", exception);
        }
    }
}
