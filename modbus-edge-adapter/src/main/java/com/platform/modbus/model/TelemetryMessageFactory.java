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
        var mappings=intervalMappings(device);
        var interval=com.platform.adapter.parser.IntervalMappings.extract(mappings,metrics.stream().map(m->new com.platform.adapter.model.StandardMetric(m.code(),m.value(),m.unit(),m.sourceField())).toList(),"MODBUS_V2:"+device.getProfileCode());
        if(interval!=null) {
            // 本进程的轮询序号不证明设备历史数据的新旧，平台不能据此自动覆盖同一时段的异值。
            if(interval.revision()==0)interval=new com.platform.adapter.model.ElectricityInterval(interval.metricCode(),interval.startMs(),interval.endMs(),interval.revision(),interval.electricityKwh(),interval.factorKgCo2PerKwh(),
                    interval.electricityUnit(),interval.factorUnit(),interval.sourceNature(),interval.sourceReference(),"ADAPTER_POLL");
            var consumed=mappings.stream().filter(m->com.platform.adapter.parser.IntervalMappings.interval(m.valueType())).map(com.platform.adapter.profile.ProtocolFieldMapping::metricCode).collect(java.util.stream.Collectors.toSet());
            metrics=metrics.stream().filter(m->!consumed.contains(m.code())).toList();
        }
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
                metrics,interval);
    }

    /** 角色来自部署点表，时界和修订来自设备读取值，禁止拿轮询时钟补造15分钟量。 */
    public static List<com.platform.adapter.profile.ProtocolFieldMapping> intervalMappings(Device device) {
        return device.getPoints().stream().map(p->new com.platform.adapter.profile.ProtocolFieldMapping(p.getCode(),"MODBUS",p.getCode(),p.getCode(),p.getValueType(),
                p.getUnit(),p.getUnit(),BigDecimal.ONE,BigDecimal.ZERO,true,true,0)).toList();
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
