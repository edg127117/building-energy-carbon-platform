package com.platform.iot.ingest.standard;

import com.platform.iot.identity.DeviceIdentityBinding;
import com.platform.iot.identity.DeviceIdentityKey;
import com.platform.iot.identity.DeviceIdentityProvider;
import com.platform.iot.identity.DeviceIdentitySnapshotUnavailableException;
import com.platform.iot.identity.UnknownDeviceHandler;
import com.platform.iot.ingest.HvacIngestionResult;
import com.platform.iot.ingest.HvacIngestionService;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointAliasKey;
import com.platform.iot.quality.PointRuntimeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
/**
 * 将一条标准多指标报文安全展开到现有单测点 TDengine 接入链。
 *
 * <p>服务先用本地 MySQL 快照完成设备、建筑、协议、测点别名、设备归属、状态、单位和
 * 数值边界的整批预检；全部通过后才开始逐点写入。任一点存储失败会让整个 MQTT 报文
 * 不确认，重投时已成功的点依靠现有幂等键成为重复数据。</p>
 */
public class StandardTelemetryIngestionService {

    static final long MIN_MILLISECOND_TIMESTAMP = 1_000_000_000_000L;
    static final long MAX_MILLISECOND_TIMESTAMP_EXCLUSIVE = 10_000_000_000_000L;

    private final DeviceIdentityProvider identityProvider;
    private final UnknownDeviceHandler unknownDeviceHandler;
    private final DataPointConfigProvider pointProvider;
    private final HvacIngestionService hvacIngestionService;
    private final String standardSourceSystem;

    public StandardTelemetryIngestionService(
            DeviceIdentityProvider identityProvider,
            UnknownDeviceHandler unknownDeviceHandler,
            DataPointConfigProvider pointProvider,
            HvacIngestionService hvacIngestionService,
            @Value("${ingestion.standard-source-system:MQTT_STANDARD_V1}")
            String standardSourceSystem) {
        this.identityProvider = identityProvider;
        this.unknownDeviceHandler = unknownDeviceHandler;
        this.pointProvider = pointProvider;
        this.hvacIngestionService = hvacIngestionService;
        this.standardSourceSystem = standardSourceSystem;
    }

    /** 使用本地 MQTT 接收时间执行整批预检和单点幂等写入。 */
    public StandardTelemetryResult ingest(
            StandardTelemetryMessage message, long localReceivedTime) {
        return ingestInternal(message, localReceivedTime, false);
    }

    /** V2 复用整批校验，但禁止同一测点同一事件时间的新值覆盖旧证据。 */
    public StandardTelemetryResult ingestImmutable(
            StandardTelemetryMessage message, long localReceivedTime) {
        return ingestInternal(message, localReceivedTime, true);
    }

    private StandardTelemetryResult ingestInternal(
            StandardTelemetryMessage message, long localReceivedTime, boolean immutable) {
        String malformed = validateEnvelope(message, localReceivedTime);
        if (malformed != null) {
            return StandardTelemetryResult.rejected(malformed);
        }

        Set<String> metricCodes = new HashSet<>();
        for (StandardMetric metric : message.metrics()) {
            String metricError = validateMetric(metric, metricCodes);
            if (metricError != null) {
                return StandardTelemetryResult.rejected(metricError);
            }
        }

        DeviceIdentityBinding binding;
        try {
            binding = identityProvider.find(message.deviceIdentity()).orElse(null);
        } catch (DeviceIdentitySnapshotUnavailableException exception) {
            return StandardTelemetryResult.retryable(0, exception.getMessage());
        }
        if (binding == null) {
            if (identityProvider.isKnown(message.deviceIdentity())) {
                return StandardTelemetryResult.rejected("设备身份已登记但未启用");
            }
            unknownDeviceHandler.recordDiscovered(message, localReceivedTime);
            return StandardTelemetryResult.rejected("设备尚未完成本地绑定");
        }
        if (!binding.expectedProfileCode().equals(message.profileCode())) {
            return StandardTelemetryResult.rejected("设备上报协议与预注册协议不一致");
        }

        List<PreparedMetric> prepared = new ArrayList<>(message.metrics().size());
        try {
            for (StandardMetric metric : message.metrics()) {
                String sourcePointCode = "%s:%s:%s".formatted(
                        message.deviceIdentity().type(),
                        message.deviceIdentity().value(),
                        metric.code());
                PointAliasKey aliasKey = new PointAliasKey(
                        binding.buildingId(), standardSourceSystem, sourcePointCode);
                PointRuntimeConfig point = pointProvider.find(aliasKey).orElse(null);
                String pointError = validatePoint(binding, metric, point);
                if (pointError != null) {
                    return StandardTelemetryResult.rejected(pointError);
                }
                prepared.add(new PreparedMetric(sourcePointCode, metric));
            }
        } catch (RuntimeException exception) {
            // 配置快照或 MySQL 相关运行时异常不能被当成毒消息确认，否则数据会永久丢失。
            log.warn("标准报文预检暂时失败，保留MQTT重投: identityType={}, reason={}",
                    message.deviceIdentity().type(), exception.getMessage());
            return StandardTelemetryResult.retryable(0, exception.getMessage());
        }

        int processed = 0;
        for (PreparedMetric item : prepared) {
            Map<String, Object> singlePointPayload = Map.of(
                    "buildingId", binding.buildingId(),
                    "deviceId", binding.equipmentCode(),
                    "pointCode", item.sourcePointCode(),
                    "val", item.metric().value(),
                    "timestamp", message.eventTime());
            HvacIngestionResult result = immutable
                    ? hvacIngestionService.ingestImmutable(
                            singlePointPayload, localReceivedTime, standardSourceSystem)
                    : hvacIngestionService.ingest(
                            singlePointPayload, localReceivedTime, standardSourceSystem);
            if (!result.shouldAcknowledge()) {
                return StandardTelemetryResult.retryable(processed, result.detail());
            }
            if (result.outcome() == com.platform.iot.ingest.IngestionOutcome.REJECTED) {
                return StandardTelemetryResult.rejected(result.detail());
            }
            if (immutable && result.outcome()
                    == com.platform.iot.ingest.IngestionOutcome.CONFLICT_UPDATED) {
                return StandardTelemetryResult.rejected(
                        "MESSAGE_CONFLICT: 同一测点同一采集时间存在不同值");
            }
            processed++;
        }
        return StandardTelemetryResult.accepted(processed);
    }

    private String validateEnvelope(StandardTelemetryMessage message, long localReceivedTime) {
        if (message == null
                || !"1.0".equals(message.standardVersion())
                || message.profileCode() == null || message.profileCode().isBlank()
                || message.profileVersion() <= 0
                || message.deviceIdentity() == null
                || message.eventTime() < MIN_MILLISECOND_TIMESTAMP
                || message.eventTime() >= MAX_MILLISECOND_TIMESTAMP_EXCLUSIVE
                || message.receivedTime() <= 0
                || localReceivedTime <= 0
                || !("DEVICE_REPORTED".equals(message.timeSource())
                || "SERVER_RECEIVED".equals(message.timeSource()))
                || message.seq() != null && message.seq() < 0
                || message.metrics() == null || message.metrics().isEmpty()) {
            return "标准报文结构、版本或时间字段无效";
        }
        return null;
    }

    private String validateMetric(StandardMetric metric, Set<String> metricCodes) {
        if (metric == null || metric.code() == null || metric.code().isBlank()
                || metric.value() == null || metric.unit() == null || metric.unit().isBlank()
                || metric.sourceField() == null || metric.sourceField().isBlank()) {
            return "标准指标字段缺失";
        }
        if (!metricCodes.add(metric.code())) {
            return "标准报文包含重复指标: " + metric.code();
        }
        return null;
    }

    private String validatePoint(
            DeviceIdentityBinding binding,
            StandardMetric metric,
            PointRuntimeConfig point) {
        if (point == null) {
            return "设备指标未配置本地测点别名: " + metric.code();
        }
        if (!Objects.equals(binding.buildingId(), point.buildingId())
                || !Objects.equals(binding.equipmentId(), point.equipId())) {
            return "设备指标测点归属与预注册设备不一致: " + metric.code();
        }
        if (!"ONLINE".equalsIgnoreCase(point.status())) {
            return "设备指标测点未启用: " + metric.code();
        }
        if (!Objects.equals(metric.unit(), point.unit())) {
            return "设备指标单位与本地测点不一致: " + metric.code();
        }
        if (point.valueMin() != null && metric.value().compareTo(point.valueMin()) < 0) {
            return "设备指标低于本地测点下限: " + metric.code();
        }
        if (point.valueMax() != null && metric.value().compareTo(point.valueMax()) > 0) {
            return "设备指标高于本地测点上限: " + metric.code();
        }
        return null;
    }

    private record PreparedMetric(String sourcePointCode, StandardMetric metric) {
    }
}
