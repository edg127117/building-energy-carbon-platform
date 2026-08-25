package com.platform.iot.deviceparameter;

import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.LegacyValue;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.LegacyMapping;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.LegacyStagedValue;
import com.platform.iot.deviceparameter.DeviceParameterCandidateService.CandidateCommand;
import com.platform.iot.deviceparameter.DeviceParameterModels.Candidate;
import com.platform.iot.deviceparameter.DeviceParameterModels.ParameterVersion;
import com.platform.iot.deviceparameter.DeviceParameterModels.SourceType;
import com.platform.iot.deviceparameter.DeviceParameterModels.ValidationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.platform.iot.deviceparameter.DeviceParameterSupport.id;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/**
 * 既有扁平参数的无损迁移暂存服务。
 *
 * <p>它先无损复制执行时快照；只有已启用映射同时明确标准定义、来源单位和换算规则时，才创建
 * 一次性迁移候选并尝试生成完整 {@code MIGRATION} 草稿。草稿不会自动提交、发布或追溯生效。</p>
 */
public class DeviceParameterLegacyMigrationService {
    private final DeviceParameterJdbcRepository repository;
    private final DeviceParameterAuthorization authorization;
    private final DeviceParameterCandidateService candidateService;
    private final DeviceParameterGovernanceService governanceService;

    public MigrationPreview preview(long userId, Collection<String> roles) {
        authorization.requireGlobalConfigurator(roles);
        List<LegacyValue> values = repository.listUnstagedLegacyValues("__PREVIEW__");
        return summarize(null, values, 0, 0, 0, 0);
    }

    @Transactional
    public MigrationPreview execute(
            long userId, Collection<String> roles, String migrationBatchId) {
        authorization.requireGlobalConfigurator(roles);
        String batchId = requireBatchId(migrationBatchId);
        long before = repository.countLegacyStaging(batchId);
        List<LegacyValue> values = repository.listUnstagedLegacyValues(batchId);
        long inserted = 0;
        for (LegacyValue value : values) {
            if (value.ratedCapacity() != null) {
                repository.stageLegacyValue(id(), batchId, value.equipment(),
                        "rated_capacity", value.ratedCapacity());
                inserted++;
            }
            if (value.ratedPower() != null) {
                repository.stageLegacyValue(id(), batchId, value.equipment(),
                        "rated_power", value.ratedPower());
                inserted++;
            }
            if (value.designCop() != null) {
                repository.stageLegacyValue(id(), batchId, value.equipment(),
                        "design_cop", value.designCop());
                inserted++;
            }
        }
        long after = repository.countLegacyStaging(batchId);
        if (after != before + inserted) {
            throw DeviceParameterErrors.error(409,
                    DeviceParameterErrors.LEGACY_MAPPING_MISSING,
                    "遗留参数迁移前后数量核对失败");
        }
        ProcessingResult processing = processBatch(userId, roles, batchId);
        String auditKey = "LEGACY_MIGRATION|" + batchId;
        if (!repository.auditExistsByIdempotency(auditKey)) {
            repository.audit(id(), null, "SYSTEM_MIGRATION", userId,
                    "STAGE_LEGACY_PARAMETERS", "LEGACY_MIGRATION", batchId, null,
                    "before=" + before, "after=" + after
                            + ",drafts=" + processing.draftCount(), "SUCCESS", null,
                    auditKey, null);
        }
        return summarize(batchId, values, after, processing.readyFacts(),
                processing.unresolvedFacts(), processing.draftCount());
    }

    private ProcessingResult processBatch(
            long userId, Collection<String> roles, String batchId) {
        List<LegacyStagedValue> staged = repository.listLegacyStaging(batchId);
        Map<String, List<LegacyStagedValue>> byEquipment = new LinkedHashMap<>();
        staged.forEach(value -> byEquipment.computeIfAbsent(
                value.equipment().equipmentId(), ignored -> new java.util.ArrayList<>()).add(value));
        int ready = 0;
        int unresolved = 0;
        int drafts = 0;
        for (List<LegacyStagedValue> equipmentValues : byEquipment.values()) {
            Set<String> candidateIds = new LinkedHashSet<>();
            for (LegacyStagedValue stagedValue : equipmentValues) {
                if (stagedValue.candidateId() != null) {
                    candidateIds.add(stagedValue.candidateId());
                    ready++;
                    continue;
                }
                LegacyMapping mapping = repository.findLegacyMapping(
                        stagedValue.equipment().equipmentTypeCode(), stagedValue.legacyField())
                        .filter(item -> "ENABLED".equals(item.status())).orElse(null);
                if (mapping == null) {
                    unresolved++;
                    continue;
                }
                if ("NOT_APPLICABLE".equals(mapping.mappingMode())) {
                    repository.markLegacyStagingNotApplicable(stagedValue.stagingId(), mapping);
                    continue;
                }
                var definition = repository.findDefinition(mapping.definitionId()).orElse(null);
                if (definition == null || !"ENABLED".equals(definition.status())) {
                    unresolved++;
                    continue;
                }
                Candidate candidate = candidateService.ingestLegacyMigration(
                        new CandidateCommand(stagedValue.equipment(), SourceType.LEGACY_MIGRATION,
                                "legacy://biz_equipment/" + stagedValue.equipment().equipmentId()
                                        + '/' + stagedValue.legacyField(),
                                batchId, stagedValue.legacyField(),
                                stagedValue.rawValue().toPlainString(), mapping.sourceUnit(),
                                null, definition.parameterCode(), null,
                                "LEGACY|" + batchId + '|' + stagedValue.stagingId(), userId),
                        mapping.sourceUnit(), mapping.scale(), mapping.offset());
                String status = candidate.validationStatus() == ValidationStatus.READY
                        ? "READY_FOR_REVIEW" : "PENDING_PROFESSIONAL_REVIEW";
                repository.markLegacyStagingCandidate(
                        stagedValue.stagingId(), mapping, candidate.candidateId(), status);
                if (candidate.validationStatus() == ValidationStatus.READY) {
                    candidateIds.add(candidate.candidateId());
                    ready++;
                } else {
                    unresolved++;
                }
            }
            if (canCreateDraft(equipmentValues.getFirst(), candidateIds)) {
                ParameterVersion draft = governanceService.createMigrationDraft(
                        userId, roles, equipmentValues.getFirst().equipment().equipmentId(),
                        List.copyOf(candidateIds), batchId,
                        "legacy-migration-batch://" + batchId);
                repository.markLegacyStagingVersion(batchId,
                        equipmentValues.getFirst().equipment().equipmentId(), draft.versionId());
                drafts++;
            }
        }
        return new ProcessingResult(ready, unresolved, drafts);
    }

    private boolean canCreateDraft(LegacyStagedValue sample, Set<String> candidateIds) {
        if (candidateIds.isEmpty()) {
            return false;
        }
        Set<String> definitions = candidateIds.stream()
                .map(repository::findCandidate).flatMap(java.util.Optional::stream)
                .map(Candidate::definitionId).collect(java.util.stream.Collectors.toSet());
        return repository.listApplicabilities(
                        sample.equipment().equipmentTypeCode(), true).stream()
                .filter(DeviceParameterModels.Applicability::required)
                .allMatch(item -> definitions.contains(item.definitionId()));
    }

    private static MigrationPreview summarize(
            String batchId, List<LegacyValue> values, long stagedFacts,
            long readyFacts, long unresolvedFacts, long draftCount) {
        long fields = values.stream().mapToLong(value ->
                (value.ratedCapacity() == null ? 0 : 1)
                        + (value.ratedPower() == null ? 0 : 1)
                        + (value.designCop() == null ? 0 : 1)).sum();
        String status = draftCount > 0 ? "DRAFTS_CREATED"
                : readyFacts > 0 ? "READY_FOR_REVIEW" : "PENDING_MAPPING";
        return new MigrationPreview(batchId, values.size(), fields, stagedFacts,
                readyFacts, unresolvedFacts, draftCount, status, false);
    }

    private static String requireBatchId(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 32
                || !value.trim().matches("[A-Za-z0-9_-]+")) {
            throw DeviceParameterSupport.invalid("迁移批次 ID 必须为 1-32 位字母、数字、下划线或横线");
        }
        return value.trim();
    }

    public record MigrationPreview(
            String migrationBatchId,
            long equipmentCount,
            long fieldFactCount,
            long stagedFactCount,
            long readyFactCount,
            long unresolvedFactCount,
            long draftCount,
            String mappingStatus,
            boolean automaticallyPublished) {
    }

    private record ProcessingResult(int readyFacts, int unresolvedFacts, int draftCount) {
    }
}
