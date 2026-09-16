package com.platform.iot.daikin.monitoring;

import com.platform.iot.daikin.model.DaikinDeviceObservation;
import com.platform.iot.daikin.model.DaikinFieldValue;
import com.platform.iot.ingest.HvacIngestionService;
import com.platform.iot.ingest.IngestionOutcome;
import com.platform.iot.quality.DataPointConfigProvider;
import com.platform.iot.quality.PointAliasKey;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 将已确认且明确绑定的两种温度送入既有数值校验和不可覆盖写入链，不创建测点、不参与冷站公式。
 * MySQL检查点必须保存原始observedAt再调用本服务；TDengine失败重试时保持同一测点/观测时间幂等键。
 */
@Service
public class DaikinTemperatureIngestion {
    public static final String SOURCE_SYSTEM = "DAIKIN_V2";
    public static final Set<String> TEMPERATURE_FIELDS = Set.of("roomTemp", "temperature");
    private final DataPointConfigProvider points;
    private final HvacIngestionService ingestion;

    public DaikinTemperatureIngestion(DataPointConfigProvider points, HvacIngestionService ingestion) {
        this.points = points;
        this.ingestion = ingestion;
    }

    /** 检查点同时冻结别名对应的数值配置，防止延迟重放时将旧观测解释成新测点。 */
    public Map<String, com.platform.iot.quality.PointRuntimeConfig> bindingSnapshot(DaikinMonitoringTargets.Target target) {
        var snapshot = new LinkedHashMap<String, com.platform.iot.quality.PointRuntimeConfig>();
        for (String field : TEMPERATURE_FIELDS) {
            points.find(new PointAliasKey(target.buildingId(), SOURCE_SYSTEM,
                    "DAIKIN_UNIT:" + target.identityValue() + ":" + field)).ifPresent(point -> snapshot.put(field, point));
        }
        return Map.copyOf(snapshot);
    }

    public DaikinDeviceObservation persist(DaikinMonitoringTargets.Target target, DaikinDeviceObservation observed) {
        if (!target.key().equals(observed.key())) throw new IllegalArgumentException("DAIKIN_IDENTITY_MISMATCH");
        Map<String, DaikinFieldValue> fields = new LinkedHashMap<>(observed.fields());
        for (String field : TEMPERATURE_FIELDS) {
            DaikinFieldValue value = fields.get(field);
            if (value == null || value.status() != DaikinFieldValue.Status.PRESENT) continue;
            String alias = "DAIKIN_UNIT:" + target.identityValue() + ":" + field;
            var point = points.find(new PointAliasKey(target.buildingId(), SOURCE_SYSTEM, alias)).orElse(null);
            if (point == null || !target.equipmentId().equals(point.equipId())
                    || !target.buildingId().equals(point.buildingId()) || point.isForCalc() != 0
                    || !"ONLINE".equals(point.status()) || !"°C".equals(point.unit())
                    || !"AI".equals(point.dataType())) {
                // 未配置点或配置不匹配不能以厂家字段替代正式测点，也不能刷新温度的成功观测时间。
                fields.put(field, new DaikinFieldValue(DaikinFieldValue.Status.UNCONFIRMED, value.rawJson(), null));
                continue;
            }
            BigDecimal number;
            try { number = new BigDecimal(value.normalizedValue()); }
            catch (RuntimeException invalid) {
                fields.put(field, new DaikinFieldValue(DaikinFieldValue.Status.INVALID, value.rawJson(), null));
                continue;
            }
            var result = ingestion.ingestImmutable(Map.of("buildingId", target.buildingId(),
                    "deviceId", target.equipmentCode(), "pointCode", alias, "val", number,
                    "timestamp", observed.observedAt().toEpochMilli()), observed.observedAt().toEpochMilli(), SOURCE_SYSTEM);
            if (result.outcome() == IngestionOutcome.STORAGE_FAILED) {
                throw new IllegalStateException("DAIKIN_TEMPERATURE_STORAGE_FAILED");
            }
            if (result.outcome() != IngestionOutcome.ACCEPTED && result.outcome() != IngestionOutcome.DUPLICATE) {
                fields.put(field, new DaikinFieldValue(DaikinFieldValue.Status.INVALID, value.rawJson(), null));
            }
        }
        return new DaikinDeviceObservation(observed.key(), observed.equipmentId(), observed.siteName(),
                observed.deviceName(), observed.observedAt(), observed.responseTime(), fields);
    }
}
