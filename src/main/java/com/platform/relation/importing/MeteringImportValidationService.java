package com.platform.relation.importing;

import com.platform.framework.exception.BusinessException;
import com.platform.relation.MeteringDomainValidator;
import com.platform.relation.RelationErrors;
import com.platform.relation.RelationGovernanceRepository;
import com.platform.relation.RelationGovernanceRepository.BoundaryRow;
import com.platform.relation.RelationGovernanceRepository.MeterStructureRow;
import com.platform.relation.RelationGovernanceRepository.NodeRow;
import com.platform.relation.RelationGovernanceRepository.VersionRow;
import com.platform.relation.api.RelationContracts.MeterStructureRequest;
import com.platform.relation.api.RelationContracts.MeteringAssignmentRequest;
import com.platform.relation.api.RelationContracts.MeteringImportIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.platform.relation.importing.MeteringImportModels.*;

@Service
@RequiredArgsConstructor
/** 将 V1 行映射为平台身份，并复用关系领域规则完成无写入预检。 */
public class MeteringImportValidationService {
    private final RelationGovernanceRepository repository;
    private final MeteringDomainValidator domainValidator;

    public ValidationResult validate(
            String buildingId, String versionId, long expectedRevision, ParsedWorkbook workbook) {
        List<MeteringImportIssue> issues = new ArrayList<>(workbook.issues());
        VersionRow version = repository.findVersion(versionId, false).orElse(null);
        if (version == null || !buildingId.equals(version.buildingId())) {
            issues.add(error(0, "versionId", RelationErrors.CROSS_BUILDING,
                    "目标关系版本不存在或不属于指定建筑"));
            return new ValidationResult(workbook, List.of(), List.copyOf(issues));
        }
        if (!"DRAFT".equals(version.status())) {
            issues.add(error(0, "versionId", RelationErrors.VERSION_CONFLICT,
                    "Excel 只能导入草稿关系版本"));
        }
        if (version.configRevision() != expectedRevision) {
            issues.add(error(0, "expectedRevision", RelationErrors.VERSION_CONFLICT,
                    "草稿修订已变化"));
        }

        Map<String, StructuralFingerprint> fingerprints = new HashMap<>();
        Map<String, BoundaryFingerprint> boundaryFingerprints = new HashMap<>();
        Set<String> coverageKeys = new HashSet<>();
        Map<String, String> importedParents = new LinkedHashMap<>();
        List<ValidatedRow> validRows = new ArrayList<>();
        for (ParsedRow row : workbook.rows()) {
            int before = errors(issues);
            validateRequiredEnums(row, issues);
            NodeRow meter = resolveNode(buildingId, "POINT", row.meterPointCode(),
                    row, "表计测点编码", issues);
            NodeRow parent = resolveNode(buildingId, "POINT", row.parentMeterCode(),
                    row, "上级表计编码", issues);
            NodeRow target = resolveTarget(buildingId, row, issues);
            BoundaryRow boundary = validateBoundary(buildingId, row, issues);
            if (row.boundaryCode() != null) {
                BoundaryFingerprint boundaryFingerprint = new BoundaryFingerprint(
                        row.boundaryName(), row.energyType(), row.evidenceReference());
                BoundaryFingerprint previousBoundary = boundaryFingerprints.putIfAbsent(
                        row.boundaryCode(), boundaryFingerprint);
                if (previousBoundary != null && !previousBoundary.equals(boundaryFingerprint)) {
                    issues.add(error(row.rowNumber(), "边界编码", "IMPORT_BOUNDARY_CONFLICT",
                            "同一新增边界编码在多行中的名称、能源类型或证据不一致"));
                }
            }
            String confirmation = pending(row) ? "PENDING_EXPERT" : "CONFIRMED";
            if (meter != null) {
                try {
                    domainValidator.validateStructure(version, new MeterStructureRequest(
                            boundary == null ? null : boundary.boundaryId(), meter.nodeId(),
                            row.meterRole(), parent == null ? null : parent.nodeId(),
                            row.meterDirection(), confirmation, row.reasonCode(), row.reasonText(),
                            row.evidenceReference(), row.description(), expectedRevision));
                } catch (BusinessException exception) {
                    issues.add(error(row.rowNumber(), "表计结构", code(exception), exception.getMessage()));
                }
            }
            String virtualBoundary = row.boundaryCode() == null ? null
                    : boundary == null ? "NEW:" + row.boundaryCode() : boundary.boundaryId();
            try {
                domainValidator.validateAssignmentShape(new MeteringAssignmentRequest(
                        virtualBoundary, meter == null ? null : meter.nodeId(),
                        target == null ? null : target.nodeId(), row.allocationStatus(),
                        row.reasonCode(), row.reasonText(), row.evidenceReference(), expectedRevision));
            } catch (BusinessException exception) {
                issues.add(error(row.rowNumber(), "分配状态", code(exception), exception.getMessage()));
            }
            if (boundary != null && meter != null && target != null
                    && repository.meteringAssignmentExists(versionId, boundary.boundaryId(),
                    meter.nodeId(), target.nodeId())) {
                issues.add(error(row.rowNumber(), "覆盖对象编码", "IMPORT_DUPLICATE_COVERAGE",
                        "目标草稿已存在相同表计覆盖关系"));
            }

            if (meter != null) {
                StructuralFingerprint fingerprint = new StructuralFingerprint(
                        row.boundaryCode(), row.meterRole(), row.parentMeterCode(), row.meterDirection(),
                        confirmation, row.reasonCode(), row.reasonText(), row.evidenceReference(),
                        row.description());
                StructuralFingerprint previous = fingerprints.putIfAbsent(row.meterPointCode(), fingerprint);
                if (previous != null && !previous.equals(fingerprint)) {
                    issues.add(error(row.rowNumber(), "表计测点编码",
                            "IMPORT_METER_STRUCTURE_CONFLICT",
                            "同一表计在多行中的边界、角色、上级、方向或确认信息不一致"));
                }
                importedParents.put(row.meterPointCode(), row.parentMeterCode());
            }
            String coverageKey = safe(row.meterPointCode()) + '|' + safe(row.targetType())
                    + '|' + safe(row.targetCode());
            if (!coverageKeys.add(coverageKey)) {
                issues.add(error(row.rowNumber(), "覆盖对象编码", "IMPORT_DUPLICATE_COVERAGE",
                        "工作簿包含重复的表计覆盖关系"));
            }
            if (pending(row)) {
                issues.add(new MeteringImportIssue("WARNING", MeteringTemplateService.DATA_SHEET,
                        row.rowNumber(), "分配状态", "IMPORT_PENDING_EXPERT",
                        "该行允许进入草稿，但会阻止审核通过和生效"));
            }
            if (errors(issues) == before) {
                validRows.add(new ValidatedRow(row, boundary, meter, parent, target, confirmation));
            }
        }
        validateHierarchy(versionId, importedParents, issues);
        return new ValidationResult(workbook, List.copyOf(validRows), List.copyOf(issues));
    }

    private void validateRequiredEnums(ParsedRow row, List<MeteringImportIssue> issues) {
        if (row.meterRole() == null
                || !Set.of("MAIN", "SUB", "INDEPENDENT", "UNKNOWN").contains(row.meterRole())) {
            issues.add(error(row.rowNumber(), "表计角色", "IMPORT_METER_ROLE_INVALID",
                    "表计角色必须是 MAIN、SUB、INDEPENDENT 或 UNKNOWN"));
        }
        if (row.meterDirection() == null || !Set.of("INBOUND", "OUTBOUND", "BIDIRECTIONAL", "UNKNOWN")
                .contains(row.meterDirection())) {
            issues.add(error(row.rowNumber(), "计量方向", "IMPORT_METER_DIRECTION_INVALID",
                    "计量方向必须是 INBOUND、OUTBOUND、BIDIRECTIONAL 或 UNKNOWN"));
        }
        if (row.allocationStatus() == null || !Set.of("ASSIGNED", "UNASSIGNED", "PENDING_EXPERT")
                .contains(row.allocationStatus())) {
            issues.add(error(row.rowNumber(), "分配状态", "IMPORT_ALLOCATION_STATUS_INVALID",
                    "分配状态必须是 ASSIGNED、UNASSIGNED 或 PENDING_EXPERT"));
        }
        if (row.meterPointCode() == null) {
            issues.add(error(row.rowNumber(), "表计测点编码", "IMPORT_METER_POINT_REQUIRED",
                    "平台标准 V1 每行必须指定表计测点编码"));
        }
        if (("UNKNOWN".equals(row.meterRole()) || "UNKNOWN".equals(row.meterDirection()))
                && !"PENDING_EXPERT".equals(row.allocationStatus())) {
            issues.add(error(row.rowNumber(), "分配状态", "IMPORT_UNKNOWN_REQUIRES_PENDING",
                    "未知表计角色或方向必须配合 PENDING_EXPERT 分配状态"));
        }
    }

    private BoundaryRow validateBoundary(
            String buildingId, ParsedRow row, List<MeteringImportIssue> issues) {
        if (row.boundaryCode() == null) return null;
        BoundaryRow existing = repository.findBoundaryByCode(buildingId, row.boundaryCode()).orElse(null);
        if (existing == null) {
            if (row.boundaryName() == null) {
                issues.add(error(row.rowNumber(), "边界名称", "IMPORT_BOUNDARY_NAME_REQUIRED",
                        "新增计量边界必须填写边界名称"));
            }
            if ("ASSIGNED".equals(row.allocationStatus()) && row.energyType() == null) {
                issues.add(error(row.rowNumber(), "能源类型", "IMPORT_ENERGY_TYPE_REQUIRED",
                        "已分配的新计量边界必须填写能源类型"));
            }
            return null;
        }
        if (!"ACTIVE".equals(existing.status())
                || different(row.boundaryName(), existing.boundaryName())
                || different(row.energyType(), existing.energyType())) {
            issues.add(error(row.rowNumber(), "边界编码", "IMPORT_BOUNDARY_CONFLICT",
                    "边界编码已存在，但状态、名称或能源类型与工作簿不一致"));
        }
        return existing;
    }

    private NodeRow resolveTarget(
            String buildingId, ParsedRow row, List<MeteringImportIssue> issues) {
        if (row.targetType() == null && row.targetCode() == null) return null;
        if (row.targetType() == null
                || !Set.of("SPACE", "SYSTEM", "EQUIPMENT").contains(row.targetType())) {
            issues.add(error(row.rowNumber(), "覆盖对象类型", "IMPORT_TARGET_TYPE_INVALID",
                    "覆盖对象类型必须是 SPACE、SYSTEM 或 EQUIPMENT"));
            return null;
        }
        return resolveNode(buildingId, row.targetType(), row.targetCode(), row,
                "覆盖对象编码", issues);
    }

    private NodeRow resolveNode(
            String buildingId, String type, String code, ParsedRow row,
            String field, List<MeteringImportIssue> issues) {
        if (code == null) return null;
        NodeRow node = repository.findNodeByCode(buildingId, type, code).orElse(null);
        if (node == null) {
            issues.add(error(row.rowNumber(), field, "IMPORT_OBJECT_NOT_FOUND",
                    "编码在当前建筑不存在或不是活动对象"));
        }
        return node;
    }

    private void validateHierarchy(
            String versionId, Map<String, String> importedParents,
            List<MeteringImportIssue> issues) {
        Map<String, String> parents = new LinkedHashMap<>();
        for (MeterStructureRow row : repository.listMeterStructures(versionId)) {
            parents.put(row.meterPointCode(), row.parentMeterPointCode());
        }
        parents.putAll(importedParents);
        for (Map.Entry<String, String> entry : importedParents.entrySet()) {
            if (entry.getValue() != null && !parents.containsKey(entry.getValue())) {
                issues.add(error(0, "上级表计编码", "IMPORT_PARENT_STRUCTURE_MISSING",
                        "上级表计必须已登记或包含在同一工作簿中: " + entry.getValue()));
            }
        }
        Set<String> complete = new HashSet<>();
        for (String start : parents.keySet()) {
            Set<String> path = new HashSet<>();
            String current = start;
            while (current != null && parents.containsKey(current)) {
                if (!path.add(current)) {
                    issues.add(error(0, "上级表计编码", RelationErrors.CYCLE_DETECTED,
                            "表计层级形成直接或间接循环"));
                    return;
                }
                if (complete.contains(current)) break;
                current = parents.get(current);
            }
            complete.addAll(path);
        }
    }

    private static boolean pending(ParsedRow row) {
        return "UNKNOWN".equals(row.meterRole()) || "UNKNOWN".equals(row.meterDirection())
                || "PENDING_EXPERT".equals(row.allocationStatus());
    }

    private static boolean different(String supplied, String existing) {
        return supplied != null && !Objects.equals(supplied, existing);
    }

    private static int errors(List<MeteringImportIssue> issues) {
        return (int) issues.stream().filter(issue -> "ERROR".equals(issue.level())).count();
    }

    private static MeteringImportIssue error(int row, String field, String code, String message) {
        return MeteringImportV1Parser.error(
                MeteringTemplateService.DATA_SHEET, row, field, code, message);
    }

    private static String code(BusinessException exception) {
        return exception.getErrorCode() == null ? RelationErrors.VALIDATION_FAILED
                : exception.getErrorCode();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record StructuralFingerprint(
            String boundaryCode, String role, String parentCode, String direction,
            String confirmation, String reasonCode, String reasonText,
            String evidence, String description) {}

    private record BoundaryFingerprint(String name, String energyType, String evidence) {}
}
