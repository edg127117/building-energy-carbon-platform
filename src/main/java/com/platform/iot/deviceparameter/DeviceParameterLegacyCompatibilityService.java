package com.platform.iot.deviceparameter;

import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.LegacyMapping;
import com.platform.iot.deviceparameter.DeviceParameterModels.EquipmentIdentity;
import com.platform.iot.deviceparameter.DeviceParameterModels.ParameterVersion;
import com.platform.iot.deviceparameter.DeviceParameterModels.TimelineSegment;
import com.platform.iot.deviceparameter.DeviceParameterModels.VersionValue;
import com.platform.hvac.model.entity.BizEquipment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
/**
 * 旧资产三个参数字段与正式完整版本之间的只读兼容投影。
 *
 * <p>只有设备类型的三个遗留字段都经过专业确认并启用映射或明确标记不适用后才切换；
 * 否则继续回显原扁平列并标记 {@code LEGACY_UNGOVERNED}，且旧列从不作为公式回退来源。</p>
 */
public class DeviceParameterLegacyCompatibilityService {
    private static final List<String> LEGACY_FIELDS = List.of(
            "rated_capacity", "rated_power", "design_cop");

    private final DeviceParameterJdbcRepository repository;

    public LegacyProjection read(BizEquipment equipment) {
        List<LegacyMapping> mappings = repository.listEnabledLegacyMappings(equipment.getTypeCode());
        if (!completeMappings(mappings)) {
            return legacy(equipment);
        }
        ParameterVersion effective = currentEffective(equipment.getEquipId());
        if (effective == null) {
            return legacy(equipment);
        }
        LegacyProjection projection = project(effective, mappings);
        return projection == null ? legacy(equipment) : projection;
    }

    /** 发布事务内最佳努力同步旧列；映射不完整时保持原证据，不猜测参数编码。 */
    public void refreshProjection(EquipmentIdentity equipment, ParameterVersion version) {
        List<LegacyMapping> mappings = repository.listEnabledLegacyMappings(
                equipment.equipmentTypeCode());
        if (!completeMappings(mappings)) {
            return;
        }
        LegacyProjection projection = project(version, mappings);
        if (projection != null) {
            repository.updateLegacyProjection(equipment.equipmentId(), projection.ratedCapacity(),
                    projection.ratedPower(), projection.designCop());
        }
    }

    private ParameterVersion currentEffective(String equipmentId) {
        LocalDateTime current = DeviceParameterSupport.now();
        return repository.findParameterSet(equipmentId, false)
                .flatMap(set -> repository.findCurrentTimeline(set.parameterSetId(), false))
                .flatMap(timeline -> timeline.segments().stream()
                        .filter(segment -> contains(segment, current)).findFirst())
                .flatMap(segment -> repository.findVersion(segment.versionId(), false))
                .orElse(null);
    }

    private LegacyProjection project(
            ParameterVersion version, List<LegacyMapping> mappings) {
        Map<String, VersionValue> values = version.values().stream()
                .collect(Collectors.toMap(VersionValue::definitionId, value -> value));
        BigDecimal capacity = null;
        BigDecimal power = null;
        BigDecimal cop = null;
        for (LegacyMapping mapping : mappings) {
            BigDecimal value = null;
            if ("MAPPED".equals(mapping.mappingMode())) {
                VersionValue versionValue = values.get(mapping.definitionId());
                if (versionValue == null || !"VALUE".equals(versionValue.valueStatus())) {
                    return null;
                }
                value = versionValue.value();
            }
            switch (mapping.legacyField()) {
                case "rated_capacity" -> capacity = value;
                case "rated_power" -> power = value;
                case "design_cop" -> cop = value;
                default -> throw new IllegalStateException("未知遗留参数字段");
            }
        }
        return new LegacyProjection(capacity, power, cop, "GOVERNED");
    }

    private static boolean completeMappings(List<LegacyMapping> mappings) {
        return mappings.size() == LEGACY_FIELDS.size()
                && mappings.stream().map(LegacyMapping::legacyField).collect(Collectors.toSet())
                .containsAll(LEGACY_FIELDS);
    }

    private static boolean contains(TimelineSegment segment, LocalDateTime value) {
        return !value.isBefore(segment.businessEffectiveFrom())
                && (segment.businessEffectiveTo() == null
                || value.isBefore(segment.businessEffectiveTo()));
    }

    private static LegacyProjection legacy(BizEquipment equipment) {
        return new LegacyProjection(equipment.getRatedCapacity(), equipment.getRatedPower(),
                equipment.getDesignCop(), "LEGACY_UNGOVERNED");
    }

    public record LegacyProjection(
            BigDecimal ratedCapacity,
            BigDecimal ratedPower,
            BigDecimal designCop,
            String governanceStatus) {
    }
}
