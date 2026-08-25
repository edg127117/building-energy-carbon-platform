package com.platform.iot.deviceparameter;

import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.ConflictHead;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.MappingVersion;
import com.platform.iot.deviceparameter.DeviceParameterModels.Applicability;
import com.platform.iot.deviceparameter.DeviceParameterModels.Candidate;
import com.platform.iot.deviceparameter.DeviceParameterModels.Conflict;
import com.platform.iot.deviceparameter.DeviceParameterModels.ConflictStatus;
import com.platform.iot.deviceparameter.DeviceParameterModels.Definition;
import com.platform.iot.deviceparameter.DeviceParameterModels.EquipmentIdentity;
import com.platform.iot.deviceparameter.DeviceParameterModels.SourceType;
import com.platform.iot.deviceparameter.DeviceParameterModels.ValidationStatus;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.CandidateCreateRequest;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.ConflictResolutionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.platform.iot.deviceparameter.DeviceParameterSupport.id;
import static com.platform.iot.deviceparameter.DeviceParameterSupport.invalid;
import static com.platform.iot.deviceparameter.DeviceParameterSupport.now;
import static com.platform.iot.deviceparameter.DeviceParameterSupport.sha256;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/**
 * 四类日常来源候选及一次性遗留迁移证据的归一化、幂等、槽位替换和冲突投影服务。
 *
 * <p>候选只表达来源声称的事实。只有新 {@code READY} 候选会替换同来源槽位的旧当前候选；
 * 未映射和非法输入会保留问题事实，但不会使最后一个有效候选失效，更不会改变正式版本。</p>
 */
public class DeviceParameterCandidateService {
    public static final String RANGE_NOT_CONFIGURED = "RANGE_NOT_CONFIGURED";
    public static final String WARNING_RANGE_EXCEEDED = "WARNING_RANGE_EXCEEDED";

    private final DeviceParameterJdbcRepository repository;
    private final DeviceParameterAuthorization authorization;

    @Transactional
    public Candidate createManualCandidate(
            long userId, Collection<String> roles, CandidateCreateRequest request) {
        authorization.requireMaintainer(roles);
        if (!"MANUAL".equalsIgnoreCase(request.sourceType())) {
            throw invalid("人工管理 API 只能创建 MANUAL 来源候选");
        }
        EquipmentIdentity equipment = requireEquipment(request.equipmentId());
        authorization.checkBuilding(userId, roles, equipment.buildingId());
        return ingest(new CandidateCommand(equipment, SourceType.MANUAL,
                request.sourceReference(), normalizeVersion(request.sourceVersion()),
                request.sourceParameterKey(), request.rawValue(), request.rawUnit(),
                request.mappingVersionId(), request.parameterCode(), request.observedAt(),
                request.idempotencyKey(), userId));
    }

    /** 供设备报文、Excel 和模板适配器调用；调用方必须先完成身份和权限边界校验。 */
    @Transactional
    public Candidate ingest(CandidateCommand command) {
        return ingest(command, null);
    }

    /** 遗留值只有携带已确认单位和显式换算规则时，才能形成一次性迁移候选。 */
    @Transactional
    public Candidate ingestLegacyMigration(
            CandidateCommand command, String sourceUnit, BigDecimal scale, BigDecimal offset) {
        if (command.sourceType() != SourceType.LEGACY_MIGRATION
                || sourceUnit == null || sourceUnit.isBlank()
                || scale == null || offset == null) {
            throw invalid("遗留迁移候选必须携带显式换算规则");
        }
        return ingest(command, new LegacyConversion(
                normalizeOptionalCode(sourceUnit), scale, offset));
    }

    private Candidate ingest(CandidateCommand command, LegacyConversion legacyConversion) {
        EquipmentIdentity equipment = requireEquipment(command.equipment().equipmentId());
        if (!equipment.buildingId().equals(command.equipment().buildingId())) {
            throw DeviceParameterErrors.error(409, DeviceParameterErrors.VERSION_CONFLICT,
                    "设备建筑归属在候选写入前已变化");
        }
        LocalDateTime receivedAt = now();
        Normalization normalization = normalize(command, equipment, legacyConversion);
        String sourceVersion = normalizeVersion(command.sourceVersion());
        String canonical = equipment.equipmentId() + '|' + command.sourceType() + '|'
                + command.sourceParameterKey().trim() + '|' + sourceVersion + '|'
                + command.rawValue().trim() + '|' + safe(command.rawUnit()) + '|'
                + safe(normalization.mappingVersionId()) + '|' + command.idempotencyKey().trim();
        if (legacyConversion != null) {
            canonical += '|' + legacyConversion.scale().toPlainString()
                    + '|' + legacyConversion.offset().toPlainString()
                    + '|' + legacyConversion.sourceUnit();
        }
        String payloadHash = sha256(canonical);
        Candidate existing = repository.findIdempotentCandidate(equipment.equipmentId(),
                command.sourceType(), command.sourceParameterKey().trim(), sourceVersion,
                payloadHash).orElse(null);
        if (existing != null) {
            repository.touchCandidate(existing.candidateId(), receivedAt);
            return repository.findCandidate(existing.candidateId()).orElseThrow();
        }

        boolean ready = normalization.status() == ValidationStatus.READY;
        String slotKey = ready ? sha256(equipment.equipmentId() + '|'
                + normalization.definition().definitionId() + '|' + command.sourceType()) : null;
        Candidate candidate = new Candidate(id(), equipment.buildingId(), equipment.equipmentId(),
                normalization.definition() == null ? null : normalization.definition().definitionId(),
                normalization.definition() == null ? normalizeOptionalCode(command.parameterCode())
                        : normalization.definition().parameterCode(),
                command.sourceType(), command.sourceReference().trim(), sourceVersion,
                command.sourceParameterKey().trim(), command.rawValue().trim(),
                normalizeOptionalCode(command.rawUnit()), normalization.normalizedValue(),
                normalization.definition() == null ? null : normalization.definition().standardUnit(),
                normalization.mappingVersionId(), command.observedAt(), normalization.status(),
                normalization.reason(), normalization.warning(), ready, payloadHash,
                receivedAt, receivedAt);
        repository.insertCandidate(candidate, slotKey, command.actorUserId());
        if (ready) {
            repository.replaceCurrentCandidate(slotKey, candidate.candidateId());
            rebuildConflict(equipment, normalization.definition(), normalization.applicability());
        }
        repository.audit(id(), equipment.buildingId(), actorType(command.sourceType()),
                command.actorUserId(), "CREATE_CANDIDATE", "CANDIDATE", candidate.candidateId(),
                null, null, normalization.status().name(), "SUCCESS", normalization.reason(),
                command.idempotencyKey(), payloadHash);
        return repository.findCandidate(candidate.candidateId()).orElseThrow();
    }

    @Transactional
    public List<Candidate> listCandidates(
            long userId, Collection<String> roles, String equipmentId, boolean currentOnly) {
        authorization.requireReader(roles);
        EquipmentIdentity equipment = requireEquipment(equipmentId);
        authorization.checkBuilding(userId, roles, equipment.buildingId());
        List<Candidate> values = repository.listCandidates(equipmentId, currentOnly);
        repository.audit(id(), equipment.buildingId(), "USER", userId,
                "READ_CANDIDATES", "EQUIPMENT", equipmentId, null,
                null, "count=" + values.size(), "SUCCESS", null, null, null);
        return values;
    }

    @Transactional
    public List<Conflict> listConflicts(
            long userId, Collection<String> roles, String equipmentId) {
        authorization.requireReader(roles);
        EquipmentIdentity equipment = requireEquipment(equipmentId);
        authorization.checkBuilding(userId, roles, equipment.buildingId());
        List<Conflict> values = repository.listCurrentConflicts(equipmentId).stream()
                .map(this::conflictView).toList();
        repository.audit(id(), equipment.buildingId(), "USER", userId,
                "READ_CONFLICTS", "EQUIPMENT", equipmentId, null,
                null, "count=" + values.size(), "SUCCESS", null, null, null);
        return values;
    }

    @Transactional
    public Conflict resolveConflict(
            long userId, Collection<String> roles, String conflictId,
            ConflictResolutionRequest request) {
        authorization.requireMaintainer(roles);
        ConflictHead conflict = repository.listCurrentConflictsForId(conflictId)
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.NOT_FOUND, "当前冲突不存在"));
        authorization.checkBuilding(userId, roles, conflict.buildingId());
        Candidate selected = repository.findCandidate(request.selectedCandidateId())
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.NOT_FOUND, "选择的候选不存在"));
        if (!repository.listConflictMemberIds(conflictId).contains(selected.candidateId())
                || selected.validationStatus() != ValidationStatus.READY
                || !selected.equipmentId().equals(conflict.equipmentId())
                || !selected.definitionId().equals(conflict.definitionId())) {
            throw invalid("只能选择当前冲突中的 READY 候选");
        }
        int changed = repository.resolveConflict(conflictId, selected.candidateId(),
                request.reason().trim(), request.expectedRevision(), userId);
        if (changed != 1) {
            throw DeviceParameterErrors.error(409, DeviceParameterErrors.CANDIDATE_CONFLICT,
                    "冲突状态或修订号已变化");
        }
        repository.audit(id(), conflict.buildingId(), "USER", userId,
                "RESOLVE_CONFLICT", "CONFLICT", conflictId, null, null,
                selected.candidateId(), "SUCCESS", null, null, null);
        return conflictView(repository.findCurrentConflict(
                conflict.equipmentId(), conflict.definitionId(), false).orElseThrow());
    }

    public EquipmentIdentity requireEquipment(String equipmentId) {
        return repository.findEquipment(equipmentId)
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.NOT_FOUND, "设备不存在"));
    }

    /** Excel 预校验复用正式归一化规则，但不写候选、冲突或审计。 */
    public CandidateValidation validateOnly(CandidateCommand command) {
        EquipmentIdentity equipment = requireEquipment(command.equipment().equipmentId());
        Normalization value = normalize(command, equipment, null);
        return new CandidateValidation(value.status(), value.reason(), value.normalizedValue(),
                value.definition() == null ? null : value.definition().definitionId());
    }

    private Normalization normalize(
            CandidateCommand command, EquipmentIdentity equipment,
            LegacyConversion legacyConversion) {
        Definition definition = null;
        MappingVersion mapping = null;
        if (command.mappingVersionId() != null && !command.mappingVersionId().isBlank()) {
            mapping = repository.findMappingVersion(command.mappingVersionId()).orElse(null);
            if (mapping == null || !"ACTIVE".equals(mapping.status())) {
                return Normalization.problem(ValidationStatus.UNMAPPED,
                        DeviceParameterErrors.MAPPING_NOT_FOUND, command.mappingVersionId());
            }
            definition = repository.findDefinition(mapping.definitionId()).orElse(null);
        } else if (command.parameterCode() != null && !command.parameterCode().isBlank()) {
            definition = repository.findDefinitionByCode(
                    normalizeOptionalCode(command.parameterCode())).orElse(null);
        }
        if (definition == null) {
            return Normalization.problem(ValidationStatus.UNMAPPED,
                    DeviceParameterErrors.DEFINITION_NOT_FOUND,
                    mapping == null ? null : mapping.mappingVersionId());
        }
        Applicability applicability = repository.findApplicability(
                equipment.equipmentTypeCode(), definition.definitionId()).orElse(null);
        if (!"ENABLED".equals(definition.status()) || applicability == null
                || !"ENABLED".equals(applicability.status())) {
            return new Normalization(definition, applicability, null,
                    ValidationStatus.INVALID, DeviceParameterErrors.VALIDATION_FAILED,
                    false, mapping == null ? null : mapping.mappingVersionId());
        }
        BigDecimal raw;
        try {
            raw = new BigDecimal(command.rawValue().trim());
        } catch (NumberFormatException exception) {
            return new Normalization(definition, applicability, null,
                    ValidationStatus.INVALID, DeviceParameterErrors.VALIDATION_FAILED,
                    false, mapping == null ? null : mapping.mappingVersionId());
        }
        BigDecimal normalized;
        if (legacyConversion != null) {
            if (command.sourceType() != SourceType.LEGACY_MIGRATION
                    || !legacyConversion.sourceUnit().equals(
                    normalizeOptionalCode(command.rawUnit()))) {
                return new Normalization(definition, applicability, null,
                        ValidationStatus.INVALID, DeviceParameterErrors.UNIT_INCOMPATIBLE,
                        false, null);
            }
            normalized = raw.multiply(legacyConversion.scale()).add(legacyConversion.offset());
        } else if (mapping != null) {
            if (!mapping.sourceUnit().equals(normalizeOptionalCode(command.rawUnit()))) {
                return new Normalization(definition, applicability, null,
                        ValidationStatus.INVALID, DeviceParameterErrors.UNIT_INCOMPATIBLE,
                        false, mapping.mappingVersionId());
            }
            normalized = raw.multiply(mapping.scale()).add(mapping.offset());
        } else {
            if (!definition.standardUnit().equals(normalizeOptionalCode(command.rawUnit()))) {
                return new Normalization(definition, applicability, null,
                        ValidationStatus.INVALID, DeviceParameterErrors.UNIT_INCOMPATIBLE,
                        false, null);
            }
            normalized = raw;
        }
        if (decimalScale(normalized) > definition.storageScale()) {
            return new Normalization(definition, applicability, null,
                    ValidationStatus.INVALID, DeviceParameterErrors.PRECISION_INVALID,
                    false, mapping == null ? null : mapping.mappingVersionId());
        }
        if (outside(normalized, applicability.hardMin(), applicability.hardMax())) {
            return new Normalization(definition, applicability, null,
                    ValidationStatus.INVALID, DeviceParameterErrors.VALUE_OUT_OF_RANGE,
                    false, mapping == null ? null : mapping.mappingVersionId());
        }
        boolean rangeMissing = applicability.warningMin() == null
                && applicability.warningMax() == null;
        boolean warning = rangeMissing || outside(normalized,
                applicability.warningMin(), applicability.warningMax());
        String warningReason = rangeMissing ? RANGE_NOT_CONFIGURED
                : warning ? WARNING_RANGE_EXCEEDED : null;
        return new Normalization(definition, applicability, normalized,
                ValidationStatus.READY, warningReason, warning,
                mapping == null ? null : mapping.mappingVersionId());
    }

    private void rebuildConflict(
            EquipmentIdentity equipment, Definition definition, Applicability applicability) {
        repository.obsoleteConflicts(equipment.equipmentId(), definition.definitionId());
        List<Candidate> ready = repository.listCurrentReadyCandidates(
                equipment.equipmentId(), definition.definitionId());
        if (ready.size() < 2) {
            return;
        }
        BigDecimal min = ready.stream().map(Candidate::normalizedValue).min(Comparator.naturalOrder()).orElseThrow();
        BigDecimal max = ready.stream().map(Candidate::normalizedValue).max(Comparator.naturalOrder()).orElseThrow();
        BigDecimal tolerance = applicability.comparisonTolerance();
        boolean consistent = tolerance == null ? min.compareTo(max) == 0
                : max.subtract(min).compareTo(tolerance) <= 0;
        if (!consistent) {
            repository.insertConflict(id(), equipment.buildingId(), equipment.equipmentId(),
                    definition.definitionId(), "OPEN",
                    ready.stream().map(Candidate::candidateId).toList());
        }
    }

    private Conflict conflictView(ConflictHead head) {
        List<Candidate> members = repository.listConflictMemberIds(head.conflictId()).stream()
                .map(repository::findCandidate).flatMap(Optional::stream).toList();
        ConflictStatus status = "RESOLVED".equals(head.status())
                ? ConflictStatus.RESOLVED : ConflictStatus.CONFLICTING;
        return new Conflict(head.conflictId(), head.equipmentId(), head.definitionId(), status,
                head.configRevision(), head.selectedCandidateId(), head.resolutionReason(), members);
    }

    private static boolean outside(BigDecimal value, BigDecimal min, BigDecimal max) {
        return min != null && value.compareTo(min) < 0 || max != null && value.compareTo(max) > 0;
    }

    private static int decimalScale(BigDecimal value) {
        return Math.max(0, value.stripTrailingZeros().scale());
    }

    private static String normalizeOptionalCode(String value) {
        return value == null || value.isBlank() ? null
                : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeVersion(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String actorType(SourceType sourceType) {
        return sourceType == SourceType.DEVICE ? "DEVICE"
                : sourceType == SourceType.LEGACY_MIGRATION ? "SYSTEM_MIGRATION" : "USER";
    }

    public record CandidateCommand(
            EquipmentIdentity equipment,
            SourceType sourceType,
            String sourceReference,
            String sourceVersion,
            String sourceParameterKey,
            String rawValue,
            String rawUnit,
            String mappingVersionId,
            String parameterCode,
            LocalDateTime observedAt,
            String idempotencyKey,
            Long actorUserId) {

        public CandidateCommand {
            if (equipment == null || sourceType == null || sourceReference == null
                    || sourceReference.isBlank() || sourceParameterKey == null
                    || sourceParameterKey.isBlank() || rawValue == null || rawValue.isBlank()
                    || idempotencyKey == null || idempotencyKey.isBlank()) {
                throw invalid("候选来源身份、引用、参数键、值和幂等键不能为空");
            }
        }
    }

    public record CandidateValidation(
            ValidationStatus status,
            String reason,
            BigDecimal normalizedValue,
            String definitionId) {
    }

    private record LegacyConversion(String sourceUnit, BigDecimal scale, BigDecimal offset) {
    }

    private record Normalization(
            Definition definition,
            Applicability applicability,
            BigDecimal normalizedValue,
            ValidationStatus status,
            String reason,
            boolean warning,
            String mappingVersionId) {

        private static Normalization problem(
                ValidationStatus status, String reason, String mappingVersionId) {
            return new Normalization(null, null, null, status, reason, false, mappingVersionId);
        }
    }
}
