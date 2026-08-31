package com.platform.relation;

import com.platform.relation.RelationGovernanceRepository.BoundaryRow;
import com.platform.relation.RelationGovernanceRepository.NodeRow;
import com.platform.relation.RelationGovernanceRepository.VersionRow;
import com.platform.relation.api.RelationContracts.MeterStructureRequest;
import com.platform.relation.api.RelationContracts.MeteringAssignmentRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

import static com.platform.relation.RelationErrors.*;

@Component
@RequiredArgsConstructor
/** 表计结构与覆盖分配共用的领域校验，避免手工接口和 Excel 形成两套规则。 */
public class MeteringDomainValidator {
    private static final Set<String> ROLES = Set.of("MAIN", "SUB", "INDEPENDENT", "UNKNOWN");
    private static final Set<String> DIRECTIONS =
            Set.of("INBOUND", "OUTBOUND", "BIDIRECTIONAL", "UNKNOWN");
    private static final Set<String> CONFIRMATIONS = Set.of("CONFIRMED", "PENDING_EXPERT");
    private static final Set<String> ALLOCATION_STATES =
            Set.of("ASSIGNED", "UNASSIGNED", "PENDING_EXPERT", "INVALID");

    private final RelationGovernanceRepository repository;

    public NormalizedMeterStructure validateStructure(
            VersionRow version, MeterStructureRequest request) {
        String role = normalize(request.meterRole());
        String direction = normalize(request.meterDirection());
        String confirmation = normalize(request.confirmationStatus());
        if (!ROLES.contains(role) || !DIRECTIONS.contains(direction)
                || !CONFIRMATIONS.contains(confirmation)) {
            throw error(400, VALIDATION_FAILED, "表计角色、方向或确认状态不合法");
        }
        String parentId = trim(request.parentMeterPointNodeId());
        if ("SUB".equals(role) && parentId == null) {
            throw error(400, VALIDATION_FAILED, "分表必须指定上级表计");
        }
        if (Set.of("MAIN", "INDEPENDENT").contains(role) && parentId != null) {
            throw error(400, VALIDATION_FAILED, "总表和独立表不能指定上级表计");
        }
        if (("UNKNOWN".equals(role) || "UNKNOWN".equals(direction))
                && !"PENDING_EXPERT".equals(confirmation)) {
            throw error(400, PENDING_EXPERT, "未知表计角色或方向必须标记为待专家确认");
        }
        if ("PENDING_EXPERT".equals(confirmation)
                && trim(request.reasonCode()) == null
                && trim(request.reasonText()) == null
                && trim(request.evidenceReference()) == null) {
            throw error(400, PENDING_EXPERT, "待专家确认的表计结构必须填写原因或证据说明");
        }
        if ("CONFIRMED".equals(confirmation) && trim(request.evidenceReference()) == null) {
            throw error(400, VALIDATION_FAILED, "已确认表计结构必须提供脱敏专业证据引用");
        }
        NodeRow meter = requirePoint(version, request.meterPointNodeId(), "表计测点");
        NodeRow parent = parentId == null ? null : requirePoint(version, parentId, "上级表计");
        if (parent != null && meter.nodeId().equals(parent.nodeId())) {
            throw error(409, CYCLE_DETECTED, "表计不能将自身作为上级表");
        }
        String boundaryId = trim(request.meteringBoundaryId());
        if (boundaryId != null) requireBoundary(version, boundaryId);
        return new NormalizedMeterStructure(boundaryId, meter.nodeId(), role,
                parent == null ? null : parent.nodeId(), direction, confirmation,
                trim(request.reasonCode()), trim(request.reasonText()),
                trim(request.evidenceReference()), trim(request.description()));
    }

    public String validateAssignment(VersionRow version, MeteringAssignmentRequest request) {
        String status = validateAssignmentShape(request);
        if (trim(request.meteringBoundaryId()) != null) {
            requireBoundary(version, request.meteringBoundaryId());
        }
        if (trim(request.meterPointNodeId()) != null) {
            requirePoint(version, request.meterPointNodeId(), "计量点");
        }
        if (trim(request.targetNodeId()) != null) requireNode(version, request.targetNodeId());
        return status;
    }

    public String validateAssignmentShape(MeteringAssignmentRequest request) {
        String status = normalize(request.allocationStatus());
        if (!ALLOCATION_STATES.contains(status)) {
            throw error(400, VALIDATION_FAILED, "计量分配状态不合法");
        }
        if ("ASSIGNED".equals(status)
                && (trim(request.meteringBoundaryId()) == null
                || trim(request.meterPointNodeId()) == null
                || trim(request.targetNodeId()) == null)) {
            throw error(400, VALIDATION_FAILED, "已分配项必须同时指定边界、计量点和覆盖对象");
        }
        if ("ASSIGNED".equals(status) && trim(request.evidenceReference()) == null) {
            throw error(400, VALIDATION_FAILED, "已分配计量项必须提供脱敏专业证据引用");
        }
        if (!"ASSIGNED".equals(status) && trim(request.reasonCode()) == null) {
            throw error(400, VALIDATION_FAILED, "未分配、待确认或非法项必须说明原因码");
        }
        return status;
    }

    private NodeRow requirePoint(VersionRow version, String nodeId, String label) {
        NodeRow node = requireNode(version, nodeId);
        if (!"POINT".equals(node.nodeType())) {
            throw error(400, VALIDATION_FAILED, label + "节点必须是 POINT");
        }
        return node;
    }

    private NodeRow requireNode(VersionRow version, String nodeId) {
        NodeRow node = repository.findNode(nodeId)
                .orElseThrow(() -> error(404, NOT_FOUND, "关系节点不存在"));
        if (!version.buildingId().equals(node.buildingId())) {
            throw error(409, CROSS_BUILDING, "关系节点不属于当前建筑");
        }
        if (!"ACTIVE".equals(node.status())
                || !repository.objectExistsInBuilding(
                node.nodeType(), node.businessObjectId(), version.buildingId())) {
            throw error(409, REFERENCE_CONFLICT, "关系节点对应业务对象不存在或已停用");
        }
        return node;
    }

    private void requireBoundary(VersionRow version, String boundaryId) {
        BoundaryRow boundary = repository.findBoundary(boundaryId)
                .orElseThrow(() -> error(404, NOT_FOUND, "计量边界不存在"));
        if (!repository.objectExistsInBuilding("METERING_BOUNDARY",
                boundary.boundaryId(), version.buildingId()) || !"ACTIVE".equals(boundary.status())) {
            throw error(409, CROSS_BUILDING, "计量边界不属于当前建筑或已退役");
        }
    }

    private static String normalize(String value) {
        String text = trim(value);
        return text == null ? "" : text.toUpperCase(Locale.ROOT);
    }

    private static String trim(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    public record NormalizedMeterStructure(
            String boundaryId, String meterPointNodeId, String meterRole,
            String parentMeterPointNodeId, String meterDirection,
            String confirmationStatus, String reasonCode, String reasonText,
            String evidenceReference, String description) {}
}
