package com.platform.relation.importing;

import com.platform.relation.RelationErrors;
import com.platform.relation.RelationGovernanceRepository;
import com.platform.relation.RelationGovernanceRepository.AuditRow;
import com.platform.relation.RelationGovernanceRepository.BoundaryRow;
import com.platform.relation.RelationGovernanceRepository.VersionRow;
import com.platform.relation.RelationGovernanceService;
import com.platform.relation.api.RelationContracts.MeteringImportIssue;
import com.platform.relation.api.RelationContracts.MeteringImportPreflightView;
import com.platform.relation.api.RelationContracts.MeteringImportPreviewRow;
import com.platform.relation.api.RelationContracts.MeteringImportResult;
import com.platform.security.FormalRole;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.platform.relation.importing.MeteringImportModels.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/** 平台标准表计 Excel 的无写预检和单事务草稿导入边界。 */
public class MeteringImportApplicationService {
    private final MeteringImportV1Parser parser;
    private final MeteringImportValidationService validationService;
    private final RelationGovernanceRepository repository;
    private final RelationGovernanceService governanceService;
    private final BuildingScopeService buildingScopeService;
    private final MeteringImportProperties properties;

    public MeteringImportPreflightView preflight(
            Long userId, Collection<String> roles, String buildingId,
            String versionId, long expectedRevision, MultipartFile file) {
        authorize(userId, roles, buildingId);
        ParsedWorkbook parsed = parser.parse(file);
        ValidationResult result = validationService.validate(
                buildingId, versionId, expectedRevision, parsed);
        return preflightView(buildingId, versionId, expectedRevision, result);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MeteringImportResult confirm(
            Long userId, Collection<String> roles, String buildingId, String versionId,
            long expectedRevision, String idempotencyKey, MultipartFile file) {
        authorize(userId, roles, buildingId);
        String key = requireKey(idempotencyKey);
        String fileHash = fileHash(file);
        String requestHash = digest("METER_IMPORT|" + userId + '|' + buildingId + '|'
                + versionId + '|' + expectedRevision + '|' + fileHash);
        AuditRow replay = repository.findAuditByIdempotency(key).orElse(null);
        if (replay != null) {
            if (!Objects.equals(requestHash, replay.requestSha256())
                    || !Objects.equals(userId, replay.operatorId())) {
                throw RelationErrors.error(409, RelationErrors.IDEMPOTENCY_REUSED,
                        "Idempotency-Key 已被其他请求使用");
            }
            return replayResult(replay);
        }

        ParsedWorkbook parsed = parser.parse(file);
        ValidationResult validated = validationService.validate(
                buildingId, versionId, expectedRevision, parsed);
        if (!validated.valid()) {
            MeteringImportIssue first = validated.issues().stream()
                    .filter(issue -> "ERROR".equals(issue.level())).findFirst().orElseThrow();
            throw RelationErrors.error(400, RelationErrors.IMPORT_REJECTED,
                    "Excel 预检未通过: " + first.code() + " - " + first.message());
        }
        VersionRow version = repository.findVersion(versionId, true)
                .orElseThrow(() -> RelationErrors.error(404,
                        RelationErrors.NOT_FOUND, "关系版本不存在"));
        if (!buildingId.equals(version.buildingId()) || !"DRAFT".equals(version.status())
                || version.configRevision() != expectedRevision) {
            throw RelationErrors.error(409, RelationErrors.VERSION_CONFLICT,
                    "目标草稿或修订号已变化");
        }
        if (repository.bumpDraftForImport(versionId, expectedRevision) != 1) {
            throw RelationErrors.error(409, RelationErrors.VERSION_CONFLICT, "草稿修订已变化");
        }

        Map<String, String> boundaryIds = createBoundaries(version, validated.rows(), userId);
        Map<String, ValidatedRow> structures = new LinkedHashMap<>();
        for (ValidatedRow row : validated.rows()) {
            structures.putIfAbsent(row.source().meterPointCode(), row);
        }
        for (ValidatedRow row : structures.values()) {
            String boundaryId = boundaryId(row, boundaryIds);
            var existing = repository.findMeterStructureByPoint(
                    versionId, row.meterPoint().nodeId()).orElse(null);
            if (existing == null) {
                repository.insertMeterStructureItem(id(), version, boundaryId,
                        row.meterPoint().nodeId(), row.source().meterRole(),
                        row.parentMeterPoint() == null ? null : row.parentMeterPoint().nodeId(),
                        row.source().meterDirection(), row.structureConfirmation(),
                        row.source().reasonCode(), row.source().reasonText(),
                        row.source().evidenceReference(), row.source().description(), "IMPORT", userId);
            } else {
                repository.updateMeterStructureItemForImport(versionId, row.meterPoint().nodeId(),
                        boundaryId, row.source().meterRole(),
                        row.parentMeterPoint() == null ? null : row.parentMeterPoint().nodeId(),
                        row.source().meterDirection(), row.structureConfirmation(),
                        row.source().reasonCode(), row.source().reasonText(),
                        row.source().evidenceReference(), row.source().description(), userId);
            }
        }

        int assignments = 0;
        for (ValidatedRow row : validated.rows()) {
            String boundaryId = boundaryId(row, boundaryIds);
            String meterId = row.meterPoint() == null ? null : row.meterPoint().nodeId();
            String targetId = row.target() == null ? null : row.target().nodeId();
            if (repository.meteringAssignmentExists(versionId, boundaryId, meterId, targetId)) {
                throw RelationErrors.error(409, RelationErrors.REFERENCE_CONFLICT,
                        "目标草稿已存在相同表计覆盖关系");
            }
            repository.insertMeteringAssignmentForImport(version, boundaryId, meterId, targetId,
                    row.source().allocationStatus(), row.source().reasonCode(),
                    row.source().reasonText(), row.source().evidenceReference(), userId);
            assignments++;
        }
        var validation = governanceService.validate(userId, roles, versionId);
        if (validation.errorCount() > 0) {
            throw RelationErrors.error(400, RelationErrors.IMPORT_REJECTED,
                    "导入后关系快照仍存在结构错误，整批已回滚");
        }
        VersionRow updated = repository.findVersion(versionId, false).orElseThrow();
        repository.insertAudit(buildingId, userId, "IMPORT_METERING_DRAFT",
                "RELATION_VERSION", versionId, versionId, null,
                Long.toString(expectedRevision), Long.toString(updated.configRevision()),
                "平台标准 V1 Excel 草稿导入",
                "rows=" + validated.rows().size() + ",structures=" + structures.size()
                        + ",assignments=" + assignments + ",boundaries=" + boundaryIds.size(),
                key, requestHash);
        return new MeteringImportResult(buildingId, versionId, updated.configRevision(),
                structures.size(), assignments, boundaryIds.size(), false);
    }

    private Map<String, String> createBoundaries(
            VersionRow version, List<ValidatedRow> rows, long userId) {
        Map<String, String> created = new HashMap<>();
        for (ValidatedRow row : rows) {
            if (row.source().boundaryCode() == null || row.existingBoundary() != null
                    || created.containsKey(row.source().boundaryCode())) continue;
            boolean pending = rows.stream()
                    .filter(candidate -> Objects.equals(candidate.source().boundaryCode(),
                            row.source().boundaryCode()))
                    .anyMatch(candidate -> "PENDING_EXPERT".equals(candidate.structureConfirmation()));
            String boundaryId = repository.insertMeteringBoundaryForImport(version,
                    row.source().boundaryCode(), row.source().boundaryName(), row.source().energyType(),
                    pending ? "PENDING_EXPERT" : "CONFIRMED",
                    row.source().evidenceReference(), userId);
            created.put(row.source().boundaryCode(), boundaryId);
        }
        return created;
    }

    private static String boundaryId(ValidatedRow row, Map<String, String> created) {
        return row.existingBoundary() == null
                ? created.get(row.source().boundaryCode()) : row.existingBoundary().boundaryId();
    }

    private MeteringImportPreflightView preflightView(
            String buildingId, String versionId, long expectedRevision, ValidationResult result) {
        int errors = (int) result.issues().stream()
                .filter(issue -> "ERROR".equals(issue.level())).count();
        int warnings = (int) result.issues().stream()
                .filter(issue -> "WARNING".equals(issue.level())).count();
        List<MeteringImportPreviewRow> preview = result.workbook().rows().stream()
                .map(row -> new MeteringImportPreviewRow(row.rowNumber(), row.templateVersion(),
                        row.boundaryCode(), row.boundaryName(), row.energyType(), row.meterPointCode(),
                        row.meterRole(), row.parentMeterCode(), row.meterDirection(), row.targetType(),
                        row.targetCode(), row.allocationStatus(), row.reasonCode(), row.reasonText(),
                        row.evidenceReference(), row.description())).toList();
        return new MeteringImportPreflightView(result.workbook().templateVersion(), buildingId,
                versionId, expectedRevision, result.workbook().totalRows(), result.rows().size(),
                warnings, errors, result.issues(), preview);
    }

    private void authorize(Long userId, Collection<String> roles, String buildingId) {
        boolean manager = roles != null && roles.stream()
                .map(role -> role.toUpperCase(Locale.ROOT))
                .anyMatch(FormalRole.ENERGY_MANAGER.name()::equals);
        if (!manager) {
            throw RelationErrors.error(403, RelationErrors.FORBIDDEN,
                    "只有能源管理员可以预检和导入关系草稿");
        }
        if (!buildingScopeService.canAccess(userId, roles, buildingId)) {
            throw RelationErrors.error(403, RelationErrors.FORBIDDEN, "无权访问该建筑");
        }
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 160) {
            throw RelationErrors.error(400, RelationErrors.VALIDATION_FAILED,
                    "Idempotency-Key 不能为空且不能超过160字符");
        }
        return key.trim();
    }

    private String fileHash(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > properties.getMaxFileBytes()) {
            throw RelationErrors.error(400, RelationErrors.IMPORT_REJECTED,
                    "只接受大小受限的无宏 .xlsx 文件");
        }
        try {
            return digest(file.getBytes());
        } catch (IOException exception) {
            throw RelationErrors.error(400, RelationErrors.IMPORT_REJECTED, "Excel 文件读取失败");
        }
    }

    private static String digest(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static MeteringImportResult replayResult(AuditRow replay) {
        Map<String, Integer> counts = new HashMap<>();
        if (replay.summary() != null) {
            for (String item : replay.summary().split(",")) {
                String[] pair = item.split("=", 2);
                if (pair.length == 2) {
                    try {
                        counts.put(pair[0], Integer.parseInt(pair[1]));
                    } catch (NumberFormatException ignored) {
                        // 审计摘要不可变；旧格式无法解析时使用安全的零值，不读取当前可变快照冒充原结果。
                    }
                }
            }
        }
        long revision;
        try {
            revision = Long.parseLong(replay.afterState());
        } catch (RuntimeException exception) {
            revision = 0L;
        }
        return new MeteringImportResult(replay.buildingId(), replay.versionId(), revision,
                counts.getOrDefault("structures", 0), counts.getOrDefault("assignments", 0),
                counts.getOrDefault("boundaries", 0), true);
    }
}
