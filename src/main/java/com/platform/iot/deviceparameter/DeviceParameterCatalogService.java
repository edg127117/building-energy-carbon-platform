package com.platform.iot.deviceparameter;

import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.MappingHead;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.MappingVersion;
import com.platform.iot.deviceparameter.DeviceParameterModels.Applicability;
import com.platform.iot.deviceparameter.DeviceParameterModels.Definition;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.ApplicabilityRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.DefinitionRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.MappingDraftRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.MappingRollbackRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.UnitRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.LegacyMappingRequest;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.LegacyMapping;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

import static com.platform.iot.deviceparameter.DeviceParameterSupport.id;
import static com.platform.iot.deviceparameter.DeviceParameterSupport.invalid;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/**
 * 全局标准参数、单位、适用关系和设备字段映射的配置服务。
 *
 * <p>本服务只保存显式证据，不创建生产默认参数。定义被引用后，参数编码、物理量和标准单位冻结；
 * 不兼容变化必须新建参数编码，避免历史候选和版本被重新解释。</p>
 */
public class DeviceParameterCatalogService {
    private final DeviceParameterJdbcRepository repository;
    private final DeviceParameterAuthorization authorization;

    @Transactional
    public void createUnit(long userId, Collection<String> roles, UnitRequest request) {
        authorization.requireGlobalConfigurator(roles);
        if (request.expectedRevision() != -1) {
            throw invalid("新建单位的 expectedRevision 必须为 -1");
        }
        try {
            repository.insertUnit(normalizeCode(request.unitCode()), normalizeCode(request.quantityKind()),
                    request.unitSymbol().trim(), request.evidenceReference().trim(), userId, "DRAFT");
            audit(null, userId, "CREATE_UNIT", "UNIT", request.unitCode(), null);
        } catch (DataIntegrityViolationException exception) {
            throw DeviceParameterErrors.error(409, DeviceParameterErrors.VERSION_CONFLICT,
                    "单位编码已经存在");
        }
    }

    @Transactional
    public void updateUnit(
            long userId, Collection<String> roles, String unitCode,
            UnitRequest request, String status) {
        authorization.requireGlobalConfigurator(roles);
        String normalizedStatus = requireConfigStatus(status);
        int changed = repository.updateUnit(normalizeCode(unitCode),
                normalizeCode(request.quantityKind()), request.unitSymbol().trim(),
                request.evidenceReference().trim(), normalizedStatus,
                request.expectedRevision(), userId);
        requireUpdated(changed, "单位不存在或修订号已过期");
        audit(null, userId, "UPDATE_UNIT", "UNIT", unitCode, normalizedStatus);
    }

    @Transactional
    public Definition createDefinition(
            long userId, Collection<String> roles, DefinitionRequest request) {
        authorization.requireGlobalConfigurator(roles);
        validateScales(request.storageScale(), request.displayScale());
        String quantityKind = normalizeCode(request.quantityKind());
        String unit = normalizeCode(request.standardUnit());
        if (!repository.unitExists(unit, quantityKind, true)) {
            throw DeviceParameterErrors.error(400, DeviceParameterErrors.UNIT_INCOMPATIBLE,
                    "标准单位不存在、未启用或物理量不匹配");
        }
        Definition value = new Definition(id(), normalizeCode(request.parameterCode()),
                request.parameterName().trim(), request.businessDefinition().trim(), quantityKind,
                unit, request.storageScale(), request.displayScale(),
                request.evidenceReference().trim(), "DRAFT", 0);
        try {
            repository.insertDefinition(value, userId);
        } catch (DataIntegrityViolationException exception) {
            throw DeviceParameterErrors.error(409, DeviceParameterErrors.VERSION_CONFLICT,
                    "标准参数编码已经存在");
        }
        audit(null, userId, "CREATE_DEFINITION", "DEFINITION", value.definitionId(), value.parameterCode());
        return value;
    }

    @Transactional
    public Definition updateDefinition(
            long userId, Collection<String> roles, String definitionId,
            DefinitionRequest request, String status) {
        authorization.requireGlobalConfigurator(roles);
        Definition existing = requireDefinition(definitionId);
        if (!existing.parameterCode().equals(normalizeCode(request.parameterCode()))
                || !existing.quantityKind().equals(normalizeCode(request.quantityKind()))
                || !existing.standardUnit().equals(normalizeCode(request.standardUnit()))) {
            throw DeviceParameterErrors.error(409, DeviceParameterErrors.VERSION_CONFLICT,
                    "参数编码、物理量和标准单位不能原地修改");
        }
        validateScales(request.storageScale(), request.displayScale());
        if ("ENABLED".equals(status)
                && (request.businessDefinition().isBlank() || request.evidenceReference().isBlank())) {
            throw invalid("启用定义前必须提供业务定义和专业依据");
        }
        Definition updated = new Definition(existing.definitionId(), existing.parameterCode(),
                request.parameterName().trim(), request.businessDefinition().trim(),
                existing.quantityKind(), existing.standardUnit(), request.storageScale(),
                request.displayScale(), request.evidenceReference().trim(),
                requireConfigStatus(status), existing.configRevision() + 1);
        requireUpdated(repository.updateDefinition(updated, request.expectedRevision(), userId),
                "标准参数不存在或修订号已过期");
        audit(null, userId, "UPDATE_DEFINITION", "DEFINITION", definitionId, status);
        return requireDefinition(definitionId);
    }

    public List<Definition> listDefinitions(long userId, Collection<String> roles) {
        authorization.requireReader(roles);
        return repository.listDefinitions();
    }

    @Transactional
    public Applicability saveApplicability(
            long userId, Collection<String> roles, ApplicabilityRequest request, String status) {
        authorization.requireGlobalConfigurator(roles);
        Definition definition = requireDefinition(request.definitionId());
        validateRanges(request);
        if ("ENABLED".equals(status) && !"ENABLED".equals(definition.status())) {
            throw invalid("只有已启用的标准参数才能启用适用关系");
        }
        Applicability existing = repository.findApplicability(
                normalizeCode(request.equipmentTypeCode()), request.definitionId()).orElse(null);
        Applicability value = new Applicability(existing == null ? id() : existing.applicabilityId(),
                normalizeCode(request.equipmentTypeCode()), request.definitionId(), request.required(),
                request.formulaReadable(), request.hardMin(), request.hardMax(), request.warningMin(),
                request.warningMax(), request.comparisonTolerance(), request.evidenceReference().trim(),
                requireConfigStatus(status), existing == null ? 0 : existing.configRevision() + 1);
        if (existing == null) {
            if (request.expectedRevision() != -1) {
                throw invalid("新建适用关系的 expectedRevision 必须为 -1");
            }
            repository.insertApplicability(value, userId);
        } else {
            requireUpdated(repository.updateApplicability(value, request.expectedRevision(), userId),
                    "适用关系修订号已过期");
        }
        audit(null, userId, "SAVE_APPLICABILITY", "APPLICABILITY",
                value.applicabilityId(), value.status());
        return repository.findApplicability(value.equipmentTypeCode(), value.definitionId()).orElseThrow();
    }

    @Transactional
    public MappingVersion createMappingDraft(
            long userId, Collection<String> roles, MappingDraftRequest request) {
        authorization.requireGlobalConfigurator(roles);
        Definition definition = requireDefinition(request.definitionId());
        if (!repository.unitExists(normalizeCode(request.sourceUnit()), definition.quantityKind(), true)) {
            throw DeviceParameterErrors.error(400, DeviceParameterErrors.UNIT_INCOMPATIBLE,
                    "映射来源单位不存在、未启用或物理量不匹配");
        }
        MappingHead head = repository.findMappingHead(
                normalizeCode(request.profileCode()), request.sourcePath().trim(), true).orElse(null);
        if (head == null) {
            if (request.expectedRevision() != -1) {
                throw invalid("新建映射的 expectedRevision 必须为 -1");
            }
            String mappingId = id();
            repository.insertMappingHead(mappingId, normalizeCode(request.profileCode()),
                    request.sourcePath().trim(), userId);
            head = new MappingHead(mappingId, normalizeCode(request.profileCode()),
                    request.sourcePath().trim(), null, 0);
        } else if (head.configRevision() != request.expectedRevision()) {
            throw DeviceParameterErrors.error(409, DeviceParameterErrors.VERSION_CONFLICT,
                    "映射修订号已过期");
        }
        int versionNo = repository.nextMappingVersionNo(head.mappingId());
        MappingVersion version = new MappingVersion(id(), head.mappingId(), versionNo, "DRAFT",
                head.profileCode(), request.profileVersion().trim(), head.sourcePath(),
                request.definitionId(), normalizeCode(request.sourceUnit()), request.scale(),
                request.offset(), request.required(), head.currentActiveVersionId(), null,
                request.changeReason().trim(), request.evidenceReference().trim(), userId);
        repository.insertMappingVersion(version);
        audit(null, userId, "CREATE_MAPPING_DRAFT", "MAPPING", head.mappingId(), version.mappingVersionId());
        return version;
    }

    @Transactional
    public MappingVersion publishMapping(
            long userId, Collection<String> roles, String mappingVersionId, int expectedRevision) {
        authorization.requireGlobalConfigurator(roles);
        MappingVersion version = repository.findMappingVersion(mappingVersionId)
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.MAPPING_NOT_FOUND, "参数映射版本不存在"));
        Definition definition = requireDefinition(version.definitionId());
        if (!"ENABLED".equals(definition.status())
                || !repository.unitExists(version.sourceUnit(), definition.quantityKind(), true)) {
            throw invalid("映射发布前标准定义和来源单位必须保持启用且物理量匹配");
        }
        requireUpdated(repository.publishMappingVersion(
                version.mappingId(), mappingVersionId, expectedRevision, userId),
                "映射状态或修订号已变化");
        audit(null, userId, "PUBLISH_MAPPING", "MAPPING", version.mappingId(), mappingVersionId);
        return repository.findMappingVersion(mappingVersionId).orElseThrow();
    }

    public List<MappingVersion> listMappingVersions(
            long userId, Collection<String> roles, String mappingVersionId) {
        authorization.requireReader(roles);
        MappingVersion version = requireMappingVersion(mappingVersionId);
        return repository.listMappingVersions(version.mappingId());
    }

    @Transactional
    public MappingVersion createMappingRollbackDraft(
            long userId, Collection<String> roles, MappingRollbackRequest request) {
        authorization.requireGlobalConfigurator(roles);
        MappingVersion source = requireMappingVersion(request.sourceVersionId());
        MappingHead head = repository.findMappingHead(
                source.profileCode(), source.sourcePath(), true).orElseThrow();
        if (head.configRevision() != request.expectedRevision()) {
            throw DeviceParameterErrors.error(409, DeviceParameterErrors.VERSION_CONFLICT,
                    "映射修订号已过期");
        }
        MappingVersion rollback = new MappingVersion(id(), head.mappingId(),
                repository.nextMappingVersionNo(head.mappingId()), "DRAFT",
                source.profileCode(), source.profileVersion(), source.sourcePath(),
                source.definitionId(), source.sourceUnit(), source.scale(), source.offset(),
                source.required(), head.currentActiveVersionId(), source.mappingVersionId(),
                request.reason().trim(), request.evidenceReference().trim(), userId);
        repository.insertMappingVersion(rollback);
        audit(null, userId, "CREATE_MAPPING_ROLLBACK_DRAFT", "MAPPING",
                head.mappingId(), rollback.mappingVersionId());
        return rollback;
    }

    private MappingVersion requireMappingVersion(String mappingVersionId) {
        return repository.findMappingVersion(mappingVersionId)
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.MAPPING_NOT_FOUND, "参数映射版本不存在"));
    }

    public Definition requireDefinition(String definitionId) {
        return repository.findDefinition(definitionId)
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.DEFINITION_NOT_FOUND, "标准参数定义不存在"));
    }

    @Transactional
    public LegacyMapping saveLegacyMapping(
            long userId, Collection<String> roles, LegacyMappingRequest request, String status) {
        authorization.requireGlobalConfigurator(roles);
        String type = normalizeCode(request.equipmentTypeCode());
        String mode = normalizeCode(request.mappingMode());
        String definitionId = "NOT_APPLICABLE".equals(mode) ? null : request.definitionId();
        String sourceUnit = null;
        java.math.BigDecimal scale = null;
        java.math.BigDecimal offset = null;
        if ("MAPPED".equals(mode)) {
            Definition definition = requireDefinition(definitionId);
            Applicability applicability = repository.findApplicability(type, definitionId).orElse(null);
            if (applicability == null || !"ENABLED".equals(applicability.status())) {
                throw invalid("遗留字段只能映射到该设备类型已启用的标准适用关系");
            }
            if (request.sourceUnit() == null || request.sourceUnit().isBlank()
                    || request.scale() == null || request.offset() == null) {
                throw invalid("遗留字段映射必须明确来源单位、换算比例和偏移量");
            }
            sourceUnit = normalizeCode(request.sourceUnit());
            if (!repository.unitExists(sourceUnit, definition.quantityKind(), true)) {
                throw DeviceParameterErrors.error(400, DeviceParameterErrors.UNIT_INCOMPATIBLE,
                        "遗留字段来源单位不存在、未启用或物理量不匹配");
            }
            scale = request.scale();
            offset = request.offset();
        } else if (!"NOT_APPLICABLE".equals(mode)) {
            throw invalid("遗留字段映射模式无效");
        }
        LegacyMapping existing = repository.findLegacyMapping(type, request.legacyField()).orElse(null);
        LegacyMapping value = new LegacyMapping(existing == null ? id() : existing.mappingId(),
                type, request.legacyField(), mode, definitionId, sourceUnit, scale, offset,
                request.evidenceReference().trim(), requireConfigStatus(status),
                existing == null ? 0 : existing.configRevision() + 1);
        if (existing == null) {
            if (request.expectedRevision() != -1) {
                throw invalid("新建遗留字段映射的 expectedRevision 必须为 -1");
            }
            repository.insertLegacyMapping(value, userId);
        } else if (repository.updateLegacyMapping(
                value, request.expectedRevision(), userId) != 1) {
            throw DeviceParameterErrors.error(409, DeviceParameterErrors.VERSION_CONFLICT,
                    "遗留字段映射修订号已过期");
        }
        audit(null, userId, "SAVE_LEGACY_MAPPING", "LEGACY_MAPPING",
                value.mappingId(), value.status());
        return repository.findLegacyMapping(type, request.legacyField()).orElseThrow();
    }

    private void audit(
            String buildingId, long userId, String action, String type,
            String objectId, String summary) {
        repository.audit(id(), buildingId, "USER", userId, action, type, objectId,
                null, null, summary, "SUCCESS", null, null, null);
    }

    private static void validateScales(int storageScale, int displayScale) {
        if (storageScale < 0 || storageScale > 12 || displayScale < 0
                || displayScale > storageScale) {
            throw invalid("存储精度必须为 0-12，展示精度不能超过存储精度");
        }
    }

    private static void validateRanges(ApplicabilityRequest request) {
        if (request.hardMin() != null && request.hardMax() != null
                && request.hardMin().compareTo(request.hardMax()) > 0) {
            throw invalid("专业硬限制下限不能大于上限");
        }
        if (request.warningMin() != null && request.warningMax() != null
                && request.warningMin().compareTo(request.warningMax()) > 0) {
            throw invalid("建议范围下限不能大于上限");
        }
        if (request.comparisonTolerance() != null
                && request.comparisonTolerance().signum() < 0) {
            throw invalid("比较容差不能为负数");
        }
    }

    private static String requireConfigStatus(String status) {
        String value = normalizeCode(status);
        if (!List.of("DRAFT", "ENABLED", "DISABLED").contains(value)) {
            throw invalid("配置状态必须为 DRAFT、ENABLED 或 DISABLED");
        }
        return value;
    }

    private static String normalizeCode(String value) {
        if (value == null || value.isBlank()) {
            throw invalid("编码不能为空");
        }
        return value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static void requireUpdated(int changed, String message) {
        if (changed != 1) {
            throw DeviceParameterErrors.error(409, DeviceParameterErrors.VERSION_CONFLICT, message);
        }
    }
}
