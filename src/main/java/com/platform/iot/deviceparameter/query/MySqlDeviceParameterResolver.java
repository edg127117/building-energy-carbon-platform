package com.platform.iot.deviceparameter.query;

import com.platform.iot.deviceparameter.DeviceParameterErrors;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.ParameterSetHead;
import com.platform.iot.deviceparameter.DeviceParameterModels.Applicability;
import com.platform.iot.deviceparameter.DeviceParameterModels.Definition;
import com.platform.iot.deviceparameter.DeviceParameterModels.EquipmentIdentity;
import com.platform.iot.deviceparameter.DeviceParameterModels.ParameterVersion;
import com.platform.iot.deviceparameter.DeviceParameterModels.ResolvedParameter;
import com.platform.iot.deviceparameter.DeviceParameterModels.ResolvedParameters;
import com.platform.iot.deviceparameter.DeviceParameterModels.TimelineRevision;
import com.platform.iot.deviceparameter.DeviceParameterModels.TimelineSegment;
import com.platform.iot.deviceparameter.DeviceParameterModels.VersionValue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
/**
 * 使用 MySQL 不可变时间线解析公式参数。
 *
 * <p>解析器从不回退到候选、模板或设备旧扁平列。一次调用先固定时间线修订和完整版本，
 * 再解析全部参数，因此同一公式计算不会在中途跨版本。</p>
 */
public class MySqlDeviceParameterResolver implements DeviceParameterResolver {
    private final DeviceParameterJdbcRepository repository;

    @Override
    public ResolvedParameters resolve(
            String equipmentId,
            LocalDateTime businessTime,
            LocalDateTime knowledgeTime,
            Collection<String> requiredParameterCodes) {
        if (businessTime == null) {
            throw DeviceParameterErrors.error(400, DeviceParameterErrors.VALIDATION_FAILED,
                    "公式参数解析必须提供业务时间");
        }
        EquipmentIdentity equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.NOT_FOUND, "设备不存在"));
        ParameterSetHead set = repository.findParameterSet(equipmentId, false)
                .orElseThrow(MySqlDeviceParameterResolver::noEffectiveVersion);
        TimelineRevision timeline = knowledgeTime == null
                ? repository.findCurrentTimeline(set.parameterSetId(), false)
                .orElseThrow(MySqlDeviceParameterResolver::noEffectiveVersion)
                : repository.findTimelineAt(set.parameterSetId(), knowledgeTime)
                .orElseThrow(MySqlDeviceParameterResolver::noEffectiveVersion);
        TimelineSegment segment = timeline.segments().stream()
                .filter(item -> !businessTime.isBefore(item.businessEffectiveFrom()))
                .filter(item -> item.businessEffectiveTo() == null
                        || businessTime.isBefore(item.businessEffectiveTo()))
                .findFirst().orElseThrow(MySqlDeviceParameterResolver::noEffectiveVersion);
        ParameterVersion version = repository.findVersion(segment.versionId(), false)
                .orElseThrow(MySqlDeviceParameterResolver::noEffectiveVersion);

        Map<String, VersionValue> byCode = new LinkedHashMap<>();
        version.values().forEach(value -> byCode.put(value.parameterCode(), value));
        Map<String, ResolvedParameter> resolved = new LinkedHashMap<>();
        for (String requested : requiredParameterCodes) {
            String code = normalizeCode(requested);
            Definition definition = repository.findDefinitionByCode(code)
                    .orElseThrow(() -> DeviceParameterErrors.error(404,
                            DeviceParameterErrors.DEFINITION_NOT_FOUND,
                            "公式依赖的标准参数不存在: " + code));
            Applicability applicability = repository.findApplicability(
                    equipment.equipmentTypeCode(), definition.definitionId()).orElse(null);
            if (!"ENABLED".equals(definition.status()) || applicability == null
                    || !"ENABLED".equals(applicability.status())
                    || !applicability.formulaReadable()) {
                throw DeviceParameterErrors.error(409,
                        DeviceParameterErrors.NO_EFFECTIVE_VERSION,
                        "参数未启用或不允许公式读取: " + code);
            }
            VersionValue value = byCode.get(code);
            if (value == null || !"VALUE".equals(value.valueStatus()) || value.value() == null) {
                throw DeviceParameterErrors.error(409,
                        DeviceParameterErrors.NO_EFFECTIVE_VERSION,
                        "有效参数版本缺少公式依赖: " + code);
            }
            resolved.put(code, new ResolvedParameter(code, value.value(), value.standardUnit(),
                    value.definitionId(), version.versionId(), timeline.timelineRevisionId(),
                    value.sourceType(), value.sourceReference(), value.observedAt(),
                    segment.businessEffectiveFrom(), timeline.publishedAt()));
        }
        return new ResolvedParameters(equipmentId, businessTime, knowledgeTime,
                version.versionId(), timeline.timelineRevisionId(), resolved);
    }

    private static String normalizeCode(String value) {
        if (value == null || value.isBlank()) {
            throw DeviceParameterErrors.error(400, DeviceParameterErrors.VALIDATION_FAILED,
                    "标准参数编码不能为空");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static RuntimeException noEffectiveVersion() {
        return DeviceParameterErrors.error(404, DeviceParameterErrors.NO_EFFECTIVE_VERSION,
                "指定业务时间和认知时间没有正式设备参数版本");
    }
}
