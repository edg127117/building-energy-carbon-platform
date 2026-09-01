package com.platform.relation;

import com.platform.relation.RelationGovernanceRepository.AssignmentRow;
import com.platform.relation.RelationGovernanceRepository.AuditRow;
import com.platform.relation.RelationGovernanceRepository.BoundaryRow;
import com.platform.relation.RelationGovernanceRepository.EffectiveMeteringAssignmentRow;
import com.platform.relation.RelationGovernanceRepository.MeteringAssignmentRow;
import com.platform.relation.RelationGovernanceRepository.MeterStructureRow;
import com.platform.relation.RelationGovernanceRepository.ModelRow;
import com.platform.relation.RelationGovernanceRepository.NodeRow;
import com.platform.relation.RelationGovernanceRepository.ReviewRow;
import com.platform.relation.RelationGovernanceRepository.SemanticRow;
import com.platform.relation.RelationGovernanceRepository.SpaceItem;
import com.platform.relation.RelationGovernanceRepository.VersionRow;
import com.platform.relation.api.RelationContracts.*;
import com.platform.security.FormalRole;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.platform.relation.RelationErrors.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/**
 * 单建筑关系版本的权限、图校验、审核、生效和查询事务边界。
 *
 * <p>关系版本是原子快照。审核后内容冻结；生效事务同时切换版本指针和旧字段兼容投影，
 * 任一步失败都会回滚，旧有效版本继续作为权威事实。</p>
 */
public class RelationGovernanceService {
    private static final Set<String> RELATION_TYPES =
            Set.of("SERVES", "MEASURES", "CONNECTED_TO", "SUPPLIES", "RETURNS");
    private static final Set<String> NODE_TYPES =
            Set.of("SPACE", "SYSTEM", "EQUIPMENT", "POINT", "METERING_BOUNDARY");

    private final RelationGovernanceRepository repository;
    private final BuildingScopeService buildingScopeService;
    private final RelationGovernanceProperties properties;
    private final MeteringDomainValidator meteringValidator;

    public ModelView model(Long userId, Collection<String> roles, String buildingId) {
        requireReader(roles);
        checkBuilding(userId, roles, buildingId);
        ModelRow model = requireModel(buildingId, false);
        if (!hasAny(roles, FormalRole.ENERGY_MANAGER, FormalRole.PLATFORM_ADMIN)) {
            if (model.activeVersionId() == null) {
                throw error(404, NOT_FOUND, "建筑尚无当前有效关系版本");
            }
            return new ModelView(model.modelId(), model.buildingId(), model.scopeType(),
                    model.governanceMode(), model.activeVersionId(), null, model.configRevision());
        }
        return toView(model);
    }

    public List<VersionView> versions(Long userId, Collection<String> roles, String buildingId) {
        requireHistoryReader(roles);
        checkBuilding(userId, roles, buildingId);
        return repository.listVersions(buildingId).stream().map(this::toView).toList();
    }

    public VersionDetailView versionDetail(
            Long userId, Collection<String> roles, String buildingId, String versionId) {
        requireHistoryReader(roles);
        checkBuilding(userId, roles, buildingId);
        ModelRow model = requireModel(buildingId, false);
        VersionRow version = requireVersion(versionId, false);
        requireSameBuilding(buildingId, version.buildingId());
        return new VersionDetailView(metadata(model, version, 0, false), toView(version),
                snapshotCounts(versionId), repository.listMeterStructures(versionId).stream()
                .map(this::toView).toList());
    }

    public VersionDiffView versionDiff(
            Long userId, Collection<String> roles, String buildingId,
            String fromVersionId, String toVersionId, int sampleSize) {
        requireHistoryReader(roles);
        checkBuilding(userId, roles, buildingId);
        requirePage(1, sampleSize);
        ModelRow model = requireModel(buildingId, false);
        VersionRow from = requireVersion(fromVersionId, false);
        VersionRow to = requireVersion(toVersionId, false);
        requireSameBuilding(buildingId, from.buildingId());
        requireSameBuilding(buildingId, to.buildingId());
        List<String> fromItems = snapshotItems(fromVersionId);
        List<String> toItems = snapshotItems(toVersionId);
        List<String> added = subtract(toItems, fromItems);
        List<String> removed = subtract(fromItems, toItems);
        int bounded = Math.min(sampleSize, properties.getMaxPageSize());
        int meterAdded = (int) added.stream().filter(item -> item.startsWith("METER_STRUCTURE:")).count();
        int meterRemoved = (int) removed.stream().filter(item -> item.startsWith("METER_STRUCTURE:")).count();
        return new VersionDiffView(buildingId, from.versionId(), from.versionNo(),
                to.versionId(), to.versionNo(), model.configRevision(), snapshotCounts(fromVersionId),
                snapshotCounts(toVersionId), added.size(), removed.size(),
                added.size() > bounded || removed.size() > bounded,
                added.stream().limit(bounded).toList(), removed.stream().limit(bounded).toList(),
                meterAdded, meterRemoved);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public VersionView initialize(
            Long userId, Collection<String> roles, String buildingId,
            String idempotencyKey, String reason) {
        requireEnergyManager(roles);
        checkBuilding(userId, roles, buildingId);
        requireText(reason, "初始化原因不能为空");
        String key = requireKey(idempotencyKey);
        String requestHash = digest("INITIALIZE|" + userId + "|" + buildingId + "|" + reason.trim());
        AuditRow replay = replay(key, requestHash);
        if (replay != null) return toView(requireVersion(replay.versionId(), false));
        if (!repository.buildingExists(buildingId)) {
            throw error(404, NOT_FOUND, "建筑不存在");
        }
        ModelRow model = repository.findModelByBuilding(buildingId, true).orElse(null);
        if (model == null) {
            repository.insertModel(id(), buildingId, userId);
            model = requireModel(buildingId, true);
        }
        if (!"BUILDING".equals(model.scopeType())) {
            throw error(400, SCOPE_UNSUPPORTED, "首版不支持园区级关系模型");
        }
        if (model.draftVersionId() != null) {
            return toView(requireVersion(model.draftVersionId(), false));
        }
        if (model.activeVersionId() != null) {
            throw error(409, VERSION_CONFLICT, "已有有效版本，请创建普通草稿");
        }
        String versionId = id();
        repository.insertVersion(versionId, model, repository.nextVersionNo(model.modelId()),
                null, null, reason.trim(), userId);
        if (repository.setDraftPointer(model.modelId(), versionId, model.configRevision(), userId) != 1) {
            throw error(409, VERSION_CONFLICT, "建筑草稿已被其他操作创建");
        }
        repository.registerLegacyNodes(buildingId, userId);
        repository.copyLegacySnapshot(versionId, buildingId);
        repository.insertAudit(buildingId, userId, "INITIALIZE", "RELATION_VERSION", versionId,
                versionId, null, null, "DRAFT", reason.trim(), "只复制明确旧外键事实",
                key, requestHash);
        validateInternal(requireVersion(versionId, true));
        return toView(requireVersion(versionId, false));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public VersionView createVersion(
            Long userId, Collection<String> roles, String buildingId,
            String idempotencyKey, CreateVersionRequest request) {
        requireEnergyManager(roles);
        checkBuilding(userId, roles, buildingId);
        String key = requireKey(idempotencyKey);
        String requestHash = digest("CREATE|" + userId + "|" + buildingId + "|" + request.expectedModelRevision()
                + "|" + safe(request.copiedFromVersionId()) + "|" + request.reason().trim());
        AuditRow replay = replay(key, requestHash);
        if (replay != null) return toView(requireVersion(replay.versionId(), false));
        ModelRow model = requireModel(buildingId, true);
        if (model.configRevision() != request.expectedModelRevision() || model.draftVersionId() != null) {
            throw error(409, VERSION_CONFLICT, "模型修订已变化或已有活动草稿");
        }
        String sourceId = request.copiedFromVersionId() == null
                ? model.activeVersionId() : request.copiedFromVersionId();
        if (sourceId == null) {
            throw error(409, VERSION_CONFLICT, "首个版本必须先执行受控初始化");
        }
        VersionRow source = requireVersion(sourceId, false);
        requireSameBuilding(buildingId, source.buildingId());
        if (!Set.of("EFFECTIVE", "SUPERSEDED").contains(source.status())) {
            throw error(409, VERSION_CONFLICT, "只能从有效或已替代历史版本复制草稿");
        }
        String versionId = id();
        repository.insertVersion(versionId, model, repository.nextVersionNo(model.modelId()),
                model.activeVersionId(), request.copiedFromVersionId(), request.reason().trim(), userId);
        if (repository.setDraftPointer(model.modelId(), versionId, request.expectedModelRevision(), userId) != 1) {
            throw error(409, VERSION_CONFLICT, "模型修订已被其他操作更新");
        }
        repository.copyVersionSnapshot(sourceId, versionId, userId);
        String action = request.copiedFromVersionId() == null ? "CREATE_DRAFT" : "CREATE_ROLLBACK_DRAFT";
        repository.insertAudit(buildingId, userId, action, "RELATION_VERSION", versionId,
                versionId, null, source.status(), "DRAFT", request.reason().trim(),
                "复制版本 " + sourceId, key, requestHash);
        return toView(requireVersion(versionId, false));
    }

    @Transactional(rollbackFor = Exception.class)
    public VersionView updateSpaceParent(
            Long userId, Collection<String> roles, String versionId, String spaceId,
            SpaceParentRequest request) {
        VersionRow version = requireEditable(userId, roles, versionId);
        requireObject(version, "SPACE", spaceId);
        if (request.parentSpaceId() != null) requireObject(version, "SPACE", request.parentSpaceId());
        if (Objects.equals(spaceId, request.parentSpaceId())) {
            throw error(409, CYCLE_DETECTED, "空间不能以自身为父级");
        }
        int sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
        if (repository.updateSpaceParent(versionId, spaceId, request.parentSpaceId(), sortOrder,
                request.expectedRevision(), userId) != 1) {
            throw error(409, VERSION_CONFLICT, "草稿修订已变化");
        }
        VersionRow updated = requireVersion(versionId, false);
        validateInternal(updated);
        if (repository.listIssues(versionId).stream().anyMatch(i -> CYCLE_DETECTED.equals(i.code()))) {
            throw error(409, CYCLE_DETECTED, "空间父级变更形成循环");
        }
        auditMutation(version, updated, userId, "UPDATE_SPACE_PARENT", spaceId);
        return toView(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public VersionView updateAssignment(
            Long userId, Collection<String> roles, String versionId, AssetAssignmentRequest request) {
        VersionRow version = requireEditable(userId, roles, versionId);
        String objectType = normalize(request.objectType());
        if (!Set.of("EQUIPMENT", "POINT").contains(objectType)) {
            throw error(400, VALIDATION_FAILED, "归属对象类型不支持");
        }
        requireObject(version, objectType, request.objectId());
        if (request.spaceId() != null) requireObject(version, "SPACE", request.spaceId());
        if (request.systemGroupId() != null) requireObject(version, "SYSTEM", request.systemGroupId());
        if (request.equipmentId() != null) requireObject(version, "EQUIPMENT", request.equipmentId());
        if (repository.updateAssetAssignment(versionId, objectType, request.objectId(),
                request.spaceId(), request.systemGroupId(), request.equipmentId(),
                request.expectedRevision()) != 1) {
            throw error(409, VERSION_CONFLICT, "草稿修订已变化");
        }
        VersionRow updated = requireVersion(versionId, false);
        auditMutation(version, updated, userId, "UPDATE_ASSET_ASSIGNMENT", request.objectId());
        return toView(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public RelationEdgeView addSemanticRelation(
            Long userId, Collection<String> roles, String versionId,
            String idempotencyKey, SemanticRelationRequest request) {
        requireEnergyManager(roles);
        VersionRow version = requireVersion(versionId, true);
        checkBuilding(userId, roles, version.buildingId());
        String key = requireKey(idempotencyKey);
        String requestHash = digest("ADD_RELATION|" + userId + '|' + versionId + '|' + request);
        AuditRow replay = replay(key, requestHash);
        if (replay != null) {
            return repository.listSemanticRelations(versionId).stream()
                    .filter(row -> row.relationItemId().equals(replay.objectId()))
                    .findFirst().map(this::toView)
                    .orElseThrow(() -> error(409, REFERENCE_CONFLICT, "幂等记录对应的语义关系不存在"));
        }
        requireState(version.status(), "DRAFT", "只有草稿版本可以编辑");
        String relationType = normalize(request.relationType());
        String sourceType = normalize(request.sourceType());
        String confirmation = normalize(request.confirmationStatus());
        validateSemanticRequest(request, relationType, sourceType, confirmation);
        NodeRow source = requireNode(version, request.sourceNodeId());
        NodeRow target = requireNode(version, request.targetNodeId());
        if (source.nodeId().equals(target.nodeId())) {
            throw error(400, VALIDATION_FAILED, "语义关系不能自关联");
        }
        String relationItemId = id();
        try {
            if (repository.insertSemanticRelation(relationItemId, version, relationType,
                    source.nodeId(), target.nodeId(), sourceType, trim(request.evidenceReference()),
                    confirmation, trim(request.description()), request.expectedRevision(), userId) != 1) {
                throw error(409, VERSION_CONFLICT, "草稿修订已变化");
            }
        } catch (DataIntegrityViolationException exception) {
            throw error(409, REFERENCE_CONFLICT, "语义关系重复或违反关系约束");
        }
        auditMutation(version, requireVersion(versionId, false), userId,
                "ADD_SEMANTIC_RELATION", relationItemId, key, requestHash);
        return new RelationEdgeView(relationItemId, relationType, source.nodeId(), target.nodeId(),
                confirmation, trim(request.description()));
    }

    @Transactional(rollbackFor = Exception.class)
    public RelationEdgeView updateSemanticRelation(
            Long userId, Collection<String> roles, String versionId,
            String relationItemId, SemanticRelationRequest request) {
        VersionRow version = requireEditable(userId, roles, versionId);
        String relationType = normalize(request.relationType());
        String sourceType = normalize(request.sourceType());
        String confirmation = normalize(request.confirmationStatus());
        validateSemanticRequest(request, relationType, sourceType, confirmation);
        NodeRow source = requireNode(version, request.sourceNodeId());
        NodeRow target = requireNode(version, request.targetNodeId());
        if (source.nodeId().equals(target.nodeId())) {
            throw error(400, VALIDATION_FAILED, "语义关系不能自关联");
        }
        try {
            if (repository.updateSemanticRelation(versionId, relationItemId, relationType,
                    source.nodeId(), target.nodeId(), sourceType, trim(request.evidenceReference()),
                    confirmation, trim(request.description()), request.expectedRevision()) != 1) {
                throw error(409, VERSION_CONFLICT, "草稿修订已变化");
            }
        } catch (DataIntegrityViolationException exception) {
            throw error(409, REFERENCE_CONFLICT, "语义关系重复或违反关系约束");
        }
        auditMutation(version, requireVersion(versionId, false), userId,
                "UPDATE_SEMANTIC_RELATION", relationItemId);
        return new RelationEdgeView(relationItemId, relationType, source.nodeId(), target.nodeId(),
                confirmation, trim(request.description()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteSemanticRelation(
            Long userId, Collection<String> roles, String versionId,
            String relationItemId, long expectedRevision) {
        VersionRow version = requireEditable(userId, roles, versionId);
        if (repository.deleteSemanticRelation(versionId, relationItemId, expectedRevision) != 1) {
            throw error(409, VERSION_CONFLICT, "草稿修订已变化");
        }
        auditMutation(version, requireVersion(versionId, false), userId,
                "DELETE_SEMANTIC_RELATION", relationItemId);
    }

    @Transactional(rollbackFor = Exception.class)
    public MeteringBoundaryView createBoundary(
            Long userId, Collection<String> roles, String versionId,
            String idempotencyKey, MeteringBoundaryRequest request) {
        requireEnergyManager(roles);
        VersionRow version = requireVersion(versionId, true);
        checkBuilding(userId, roles, version.buildingId());
        String key = requireKey(idempotencyKey);
        String requestHash = digest("CREATE_BOUNDARY|" + userId + '|' + versionId + '|' + request);
        AuditRow replay = replay(key, requestHash);
        if (replay != null) return toView(repository.findBoundary(replay.objectId())
                .orElseThrow(() -> error(409, REFERENCE_CONFLICT,
                        "幂等记录对应的计量边界不存在")));
        requireState(version.status(), "DRAFT", "只有草稿版本可以编辑");
        String confirmation = validateBoundaryRequest(request);
        String boundaryId;
        try {
            boundaryId = repository.insertMeteringBoundary(version, request.boundaryCode().trim(),
                    request.boundaryName().trim(), trim(request.energyType()), confirmation,
                    trim(request.evidenceReference()), request.expectedRevision(), userId);
        } catch (DataIntegrityViolationException exception) {
            throw error(409, REFERENCE_CONFLICT, "计量边界编码重复或违反边界约束");
        }
        if (boundaryId == null) throw error(409, VERSION_CONFLICT, "草稿修订已变化");
        auditMutation(version, requireVersion(versionId, false), userId,
                "CREATE_METERING_BOUNDARY", boundaryId, key, requestHash);
        return new MeteringBoundaryView(boundaryId, request.boundaryCode().trim(),
                request.boundaryName().trim(), trim(request.energyType()), confirmation, "ACTIVE");
    }

    @Transactional(rollbackFor = Exception.class)
    public MeteringBoundaryView updateBoundary(
            Long userId, Collection<String> roles, String versionId,
            String boundaryId, MeteringBoundaryRequest request) {
        VersionRow version = requireEditable(userId, roles, versionId);
        requireBoundary(version, boundaryId);
        if (repository.boundaryReferencedByFrozenVersion(boundaryId)) {
            throw error(409, REFERENCE_CONFLICT,
                    "计量边界已被冻结或历史版本引用；请新建边界身份后再调整");
        }
        String confirmation = validateBoundaryRequest(request);
        try {
            if (repository.updateMeteringBoundary(version, boundaryId,
                    request.boundaryCode().trim(), request.boundaryName().trim(),
                    trim(request.energyType()), confirmation, trim(request.evidenceReference()),
                    request.expectedRevision(), userId) != 1) {
                throw error(409, VERSION_CONFLICT, "草稿修订已变化");
            }
        } catch (DataIntegrityViolationException exception) {
            throw error(409, REFERENCE_CONFLICT, "计量边界编码重复或违反边界约束");
        }
        auditMutation(version, requireVersion(versionId, false), userId,
                "UPDATE_METERING_BOUNDARY", boundaryId);
        return new MeteringBoundaryView(boundaryId, request.boundaryCode().trim(),
                request.boundaryName().trim(), trim(request.energyType()), confirmation, "ACTIVE");
    }

    @Transactional(rollbackFor = Exception.class)
    public void retireBoundary(
            Long userId, Collection<String> roles, String versionId,
            String boundaryId, long expectedRevision) {
        VersionRow version = requireEditable(userId, roles, versionId);
        requireBoundary(version, boundaryId);
        if (repository.boundaryReferencedByAnyVersion(boundaryId)) {
            throw error(409, REFERENCE_CONFLICT, "计量边界仍被关系版本引用，不能退役");
        }
        if (repository.retireMeteringBoundary(
                version, boundaryId, expectedRevision, userId) != 1) {
            throw error(409, VERSION_CONFLICT, "草稿修订已变化");
        }
        auditMutation(version, requireVersion(versionId, false), userId,
                "RETIRE_METERING_BOUNDARY", boundaryId);
    }

    @Transactional(rollbackFor = Exception.class)
    public String createMeteringAssignment(
            Long userId, Collection<String> roles, String versionId,
            String idempotencyKey, MeteringAssignmentRequest request) {
        requireEnergyManager(roles);
        VersionRow version = requireVersion(versionId, true);
        checkBuilding(userId, roles, version.buildingId());
        String key = requireKey(idempotencyKey);
        String requestHash = digest("CREATE_METERING_ASSIGNMENT|" + userId
                + '|' + versionId + '|' + request);
        AuditRow replay = replay(key, requestHash);
        if (replay != null) return replay.objectId();
        requireState(version.status(), "DRAFT", "只有草稿版本可以编辑");
        String status = meteringValidator.validateAssignment(version, request);
        String assignmentId = repository.insertMeteringAssignment(version,
                request.meteringBoundaryId(), request.meterPointNodeId(), request.targetNodeId(),
                status, trim(request.reasonCode()), trim(request.reasonText()),
                trim(request.evidenceReference()), request.expectedRevision(), userId);
        if (assignmentId == null) throw error(409, VERSION_CONFLICT, "草稿修订已变化");
        auditMutation(version, requireVersion(versionId, false), userId,
                "CREATE_METERING_ASSIGNMENT", assignmentId, key, requestHash);
        return assignmentId;
    }

    @Transactional(rollbackFor = Exception.class)
    public String updateMeteringAssignment(
            Long userId, Collection<String> roles, String versionId,
            String assignmentId, MeteringAssignmentRequest request) {
        VersionRow version = requireEditable(userId, roles, versionId);
        String status = meteringValidator.validateAssignment(version, request);
        if (repository.updateMeteringAssignment(versionId, assignmentId,
                request.meteringBoundaryId(), request.meterPointNodeId(), request.targetNodeId(),
                status, trim(request.reasonCode()), trim(request.reasonText()),
                trim(request.evidenceReference()), request.expectedRevision()) != 1) {
            throw error(409, VERSION_CONFLICT, "草稿修订已变化");
        }
        auditMutation(version, requireVersion(versionId, false), userId,
                "UPDATE_METERING_ASSIGNMENT", assignmentId);
        return assignmentId;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteMeteringAssignment(
            Long userId, Collection<String> roles, String versionId,
            String assignmentId, long expectedRevision) {
        VersionRow version = requireEditable(userId, roles, versionId);
        if (repository.deleteMeteringAssignment(versionId, assignmentId, expectedRevision) != 1) {
            throw error(409, VERSION_CONFLICT, "草稿修订已变化");
        }
        auditMutation(version, requireVersion(versionId, false), userId,
                "DELETE_METERING_ASSIGNMENT", assignmentId);
    }

    @Transactional(rollbackFor = Exception.class)
    public MeterStructureView createMeterStructure(
            Long userId, Collection<String> roles, String versionId,
            String idempotencyKey, MeterStructureRequest request) {
        requireEnergyManager(roles);
        VersionRow version = requireVersion(versionId, true);
        checkBuilding(userId, roles, version.buildingId());
        String key = requireKey(idempotencyKey);
        String requestHash = digest("CREATE_METER_STRUCTURE|" + userId + '|' + versionId + '|' + request);
        AuditRow replay = replay(key, requestHash);
        if (replay != null) {
            return toView(repository.findMeterStructure(versionId, replay.objectId())
                    .orElseThrow(() -> error(409, REFERENCE_CONFLICT,
                            "幂等记录对应的表计结构不存在")));
        }
        requireState(version.status(), "DRAFT", "只有草稿版本可以编辑");
        var value = meteringValidator.validateStructure(version, request);
        String structureId;
        try {
            structureId = repository.insertMeterStructure(version, value.boundaryId(),
                    value.meterPointNodeId(), value.meterRole(), value.parentMeterPointNodeId(),
                    value.meterDirection(), value.confirmationStatus(), value.reasonCode(),
                    value.reasonText(), value.evidenceReference(), value.description(), "MANUAL",
                    request.expectedRevision(), userId);
        } catch (DataIntegrityViolationException exception) {
            throw error(409, REFERENCE_CONFLICT, "同一版本的表计结构重复或违反层级约束");
        }
        if (structureId == null) throw error(409, VERSION_CONFLICT, "草稿修订已变化");
        rejectMeterCycle(requireVersion(versionId, false));
        auditMutation(version, requireVersion(versionId, false), userId,
                "CREATE_METER_STRUCTURE", structureId, key, requestHash);
        return toView(repository.findMeterStructure(versionId, structureId).orElseThrow());
    }

    @Transactional(rollbackFor = Exception.class)
    public MeterStructureView updateMeterStructure(
            Long userId, Collection<String> roles, String versionId,
            String structureItemId, MeterStructureRequest request) {
        VersionRow version = requireEditable(userId, roles, versionId);
        if (repository.findMeterStructure(versionId, structureItemId).isEmpty()) {
            throw error(404, NOT_FOUND, "表计结构不存在");
        }
        var value = meteringValidator.validateStructure(version, request);
        try {
            if (repository.updateMeterStructure(versionId, structureItemId, value.boundaryId(),
                    value.meterPointNodeId(), value.meterRole(), value.parentMeterPointNodeId(),
                    value.meterDirection(), value.confirmationStatus(), value.reasonCode(),
                    value.reasonText(), value.evidenceReference(), value.description(), "MANUAL",
                    request.expectedRevision(), userId) != 1) {
                throw error(409, VERSION_CONFLICT, "草稿修订已变化");
            }
        } catch (DataIntegrityViolationException exception) {
            throw error(409, REFERENCE_CONFLICT, "同一版本的表计结构重复或违反层级约束");
        }
        rejectMeterCycle(requireVersion(versionId, false));
        auditMutation(version, requireVersion(versionId, false), userId,
                "UPDATE_METER_STRUCTURE", structureItemId);
        return toView(repository.findMeterStructure(versionId, structureItemId).orElseThrow());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteMeterStructure(
            Long userId, Collection<String> roles, String versionId,
            String structureItemId, long expectedRevision) {
        VersionRow version = requireEditable(userId, roles, versionId);
        MeterStructureRow row = repository.findMeterStructure(versionId, structureItemId)
                .orElseThrow(() -> error(404, NOT_FOUND, "表计结构不存在"));
        if (!repository.listMeterChildren(versionId, row.meterPointNodeId()).isEmpty()) {
            throw error(409, REFERENCE_CONFLICT, "表计结构仍有直接下级，不能删除");
        }
        if (repository.deleteMeterStructure(versionId, structureItemId, expectedRevision) != 1) {
            throw error(409, VERSION_CONFLICT, "草稿修订已变化");
        }
        auditMutation(version, requireVersion(versionId, false), userId,
                "DELETE_METER_STRUCTURE", structureItemId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ValidationView validate(
            Long userId, Collection<String> roles, String versionId) {
        requireHistoryReader(roles);
        VersionRow version = requireVersion(versionId, true);
        checkBuilding(userId, roles, version.buildingId());
        return validateInternal(version);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public ReviewView submit(
            Long userId, Collection<String> roles, String versionId, String idempotencyKey,
            RevisionReasonRequest request) {
        requireEnergyManager(roles);
        VersionRow version = requireVersion(versionId, true);
        checkBuilding(userId, roles, version.buildingId());
        String key = requireKey(idempotencyKey);
        String requestHash = digest("SUBMIT|" + userId + "|" + versionId + "|" + request.expectedRevision()
                + "|" + request.reason().trim());
        ReviewRow prior = repository.findReviewByIdempotency(key).orElse(null);
        if (prior != null) {
            requireSameRequest(prior.requestSha256(), requestHash);
            return toView(prior);
        }
        requireState(version.status(), "DRAFT", "当前版本不可提交");
        if (version.configRevision() != request.expectedRevision()) {
            throw error(409, VERSION_CONFLICT, "草稿修订已变化");
        }
        ValidationView validation = validateInternal(version);
        requireNoBlockingIssues(validation);
        String snapshotHash = snapshotHash(versionId);
        if (repository.submitVersion(versionId, request.expectedRevision(), snapshotHash, userId) != 1) {
            throw error(409, VERSION_CONFLICT, "草稿提交发生并发冲突");
        }
        VersionRow submitted = requireVersion(versionId, false);
        String requestId = id();
        repository.insertReview(requestId, submitted, repository.nextReviewNo(versionId),
                submitted.submittedRevision(), snapshotHash, userId, key, requestHash);
        repository.insertAudit(version.buildingId(), userId, "SUBMIT", "RELATION_VERSION", versionId,
                versionId, requestId, "DRAFT", "PENDING_REVIEW", request.reason().trim(),
                "snapshot=" + snapshotHash, null, null);
        return toView(requireReview(requestId, false));
    }

    @Transactional(rollbackFor = Exception.class)
    public VersionView withdraw(
            Long userId, Collection<String> roles, String versionId, RevisionReasonRequest request) {
        requireEnergyManager(roles);
        VersionRow version = requireVersion(versionId, true);
        checkBuilding(userId, roles, version.buildingId());
        if (!Set.of("DRAFT", "PENDING_REVIEW").contains(version.status())) {
            throw error(409, VERSION_CONFLICT, "当前版本不可撤回");
        }
        if (repository.withdrawVersion(versionId, request.expectedRevision()) != 1) {
            throw error(409, VERSION_CONFLICT, "草稿修订已变化");
        }
        repository.withdrawPendingReview(versionId);
        repository.clearDraftPointer(version.modelId(), versionId, userId);
        repository.insertAudit(version.buildingId(), userId, "WITHDRAW", "RELATION_VERSION", versionId,
                versionId, null, version.status(), "WITHDRAWN", request.reason().trim(), null, null, null);
        return toView(requireVersion(versionId, false));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public ReviewView approve(
            Long userId, Collection<String> roles, String requestId,
            String idempotencyKey, ReviewDecisionRequest request) {
        requirePlatformAdmin(roles);
        ReviewRow review = requireReview(requestId, true);
        checkBuilding(userId, roles, review.buildingId());
        String key = requireKey(idempotencyKey);
        String requestHash = digest("APPROVE|" + userId + "|" + requestId + "|" + request.reason().trim());
        AuditRow replay = replay(key, requestHash);
        if (replay != null) return toView(requireReview(requestId, false));
        requireState(review.status(), "PENDING", "审核申请当前不可批准");
        VersionRow version = requireVersion(review.versionId(), true);
        requireState(version.status(), "PENDING_REVIEW", "关系版本当前不可批准");
        boolean selfApproval = Objects.equals(review.submittedBy(), userId)
                || Objects.equals(version.createdBy(), userId);
        if (selfApproval && !properties.isSelfApprovalEnabled()) {
            throw error(403, FORBIDDEN, "禁止创建人或提交人审核自己的版本");
        }
        if (!Objects.equals(version.submittedRevision(), review.submittedRevision())
                || !Objects.equals(version.snapshotSha256(), review.snapshotSha256())
                || !Objects.equals(snapshotHash(version.versionId()), review.snapshotSha256())) {
            throw error(409, REVIEW_CONFLICT, "审核快照与当前版本不一致");
        }
        ValidationView validation = validateInternal(version);
        requireNoBlockingIssues(validation);
        if (repository.approveReview(requestId, userId, request.reason().trim(), selfApproval) != 1
                || repository.markVersionApproved(version.versionId(), userId,
                review.submittedRevision(), review.snapshotSha256()) != 1) {
            throw error(409, REVIEW_CONFLICT, "审核状态发生并发冲突");
        }
        repository.insertAudit(review.buildingId(), userId,
                selfApproval ? "SELF_APPROVAL_DEV_MODE" : "APPROVE",
                "REVIEW_REQUEST", requestId, version.versionId(), requestId,
                "PENDING", "APPROVED", request.reason().trim(),
                selfApproval ? "研发自审例外" : null, key, requestHash);
        return toView(requireReview(requestId, false));
    }

    @Transactional(rollbackFor = Exception.class)
    public ReviewView reject(
            Long userId, Collection<String> roles, String requestId,
            String idempotencyKey, ReviewDecisionRequest request) {
        requirePlatformAdmin(roles);
        ReviewRow review = requireReview(requestId, true);
        checkBuilding(userId, roles, review.buildingId());
        String key = requireKey(idempotencyKey);
        String requestHash = digest("REJECT|" + userId + "|" + requestId + "|" + request.reason().trim());
        AuditRow replay = replay(key, requestHash);
        if (replay != null) return toView(requireReview(requestId, false));
        if (repository.rejectReview(requestId, userId, request.reason().trim()) != 1) {
            throw error(409, REVIEW_CONFLICT, "审核申请当前不可驳回");
        }
        repository.markVersionRejected(review.versionId());
        VersionRow version = requireVersion(review.versionId(), false);
        repository.clearDraftPointer(version.modelId(), version.versionId(), userId);
        repository.insertAudit(review.buildingId(), userId, "REJECT", "REVIEW_REQUEST", requestId,
                version.versionId(), requestId, "PENDING", "REJECTED", request.reason().trim(),
                null, key, requestHash);
        return toView(requireReview(requestId, false));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public VersionView activate(
            Long userId, Collection<String> roles, String versionId,
            String idempotencyKey, ActivationRequest request) {
        requirePlatformAdmin(roles);
        VersionRow version = requireVersion(versionId, true);
        checkBuilding(userId, roles, version.buildingId());
        String key = requireKey(idempotencyKey);
        String requestHash = digest("ACTIVATE|" + userId + "|" + versionId + "|" + request.expectedModelRevision()
                + "|" + request.reason().trim());
        AuditRow replay = replay(key, requestHash);
        if (replay != null) return toView(requireVersion(versionId, false));
        requireState(version.status(), "APPROVED", "关系版本当前不可生效");
        ModelRow model = requireModel(version.buildingId(), true);
        if (model.configRevision() != request.expectedModelRevision()
                || !Objects.equals(model.draftVersionId(), versionId)
                || !Objects.equals(model.activeVersionId(), version.baseVersionId())) {
            throw error(409, VERSION_CONFLICT, "基础有效版本或模型修订已变化");
        }
        ReviewRow review = repository.listReviews(version.buildingId()).stream()
                .filter(value -> value.versionId().equals(versionId) && "APPROVED".equals(value.status()))
                .findFirst().orElseThrow(() -> error(409, REVIEW_CONFLICT, "版本缺少已批准审核"));
        if (!Objects.equals(snapshotHash(versionId), review.snapshotSha256())) {
            throw error(409, REVIEW_CONFLICT, "生效前快照校验失败");
        }
        requireNoBlockingIssues(validateInternal(version));
        repository.activateProjection(version);
        if (repository.activateVersion(model, version, request.expectedModelRevision(), userId) != 1) {
            throw error(409, VERSION_CONFLICT, "生效指针切换发生并发冲突");
        }
        repository.insertAudit(version.buildingId(), userId, "ACTIVATE", "RELATION_VERSION", versionId,
                versionId, review.requestId(), "APPROVED", "EFFECTIVE", request.reason().trim(),
                "立即生效并更新兼容投影", key, requestHash);
        return toView(requireVersion(versionId, false));
    }

    public SpaceTreeView effectiveSpaceTree(
            Long userId, Collection<String> roles, String buildingId) {
        requireReader(roles);
        checkBuilding(userId, roles, buildingId);
        ModelRow model = requireModel(buildingId, false);
        VersionRow version = requireEffective(model);
        return new SpaceTreeView(metadata(model, version, 0, false), buildTree(repository.listSpaceItems(version.versionId())));
    }

    public SpaceTreeView historicalSpaceTree(
            Long userId, Collection<String> roles, String buildingId, String versionId) {
        requireHistoryReader(roles);
        checkBuilding(userId, roles, buildingId);
        ModelRow model = requireModel(buildingId, false);
        VersionRow version = requireVersion(versionId, false);
        requireSameBuilding(buildingId, version.buildingId());
        return new SpaceTreeView(metadata(model, version, 0, false), buildTree(repository.listSpaceItems(versionId)));
    }

    public NodeContextView nodeContext(
            Long userId, Collection<String> roles, String buildingId,
            String nodeType, String objectId, int depth, int page, int size) {
        requireReader(roles);
        checkBuilding(userId, roles, buildingId);
        ModelRow model = requireModel(buildingId, false);
        VersionRow version = requireEffective(model);
        return nodeContext(model, version, nodeType, objectId, depth, page, size);
    }

    public NodeContextView historicalNodeContext(
            Long userId, Collection<String> roles, String buildingId, String versionId,
            String nodeType, String objectId, int depth, int page, int size) {
        requireHistoryReader(roles);
        checkBuilding(userId, roles, buildingId);
        ModelRow model = requireModel(buildingId, false);
        VersionRow version = requireVersion(versionId, false);
        requireSameBuilding(buildingId, version.buildingId());
        return nodeContext(model, version, nodeType, objectId, depth, page, size);
    }

    private NodeContextView nodeContext(
            ModelRow model, VersionRow version, String nodeType,
            String objectId, int depth, int page, int size) {
        requirePage(page, size);
        if (depth < 1 || depth > properties.getMaxContextDepth()) {
            throw error(400, VALIDATION_FAILED, "节点上下文深度超出配置上限");
        }
        String type = normalize(nodeType);
        if (!NODE_TYPES.contains(type)) throw error(400, VALIDATION_FAILED, "节点类型不支持");
        NodeRow node = repository.findNodeByObject(model.buildingId(), type, objectId)
                .orElseThrow(() -> error(404, NOT_FOUND, "关系节点不存在"));
        long total = repository.countContextEdges(version.versionId(), node.nodeId());
        int boundedSize = Math.min(size, properties.getMaxPageSize());
        List<RelationEdgeView> edges = repository.listContextEdges(version.versionId(), node.nodeId(),
                        boundedSize, (page - 1) * boundedSize).stream()
                .map(this::toView).toList();
        boolean truncated = depth > 1 || page * (long) boundedSize < total;
        return new NodeContextView(metadata(model, version, depth, truncated), node.nodeId(),
                node.nodeType(), node.businessObjectId(), page, boundedSize, total, edges);
    }

    public MeteringBoundariesView effectiveBoundaries(
            Long userId, Collection<String> roles, String buildingId, int page, int size) {
        requireReader(roles);
        checkBuilding(userId, roles, buildingId);
        requirePage(page, size);
        ModelRow model = requireModel(buildingId, false);
        VersionRow version = requireEffective(model);
        int boundedSize = Math.min(size, properties.getMaxPageSize());
        long total = repository.countBoundaries(version.versionId(), buildingId);
        List<MeteringBoundaryView> items = repository.listBoundaries(version.versionId(), buildingId, boundedSize,
                (page - 1) * boundedSize).stream().map(this::toView).toList();
        return new MeteringBoundariesView(metadata(model, version, 0,
                page * (long) boundedSize < total), page, boundedSize, total, items);
    }

    public MeteringAssignmentsView effectiveMeteringAssignments(
            Long userId, Collection<String> roles, String buildingId, int page, int size) {
        requireReader(roles);
        checkBuilding(userId, roles, buildingId);
        requirePage(page, size);
        ModelRow model = requireModel(buildingId, false);
        VersionRow version = requireEffective(model);
        int boundedSize = Math.min(size, properties.getMaxPageSize());
        long total = repository.countEffectiveMeteringAssignments(version.versionId(), buildingId);
        List<MeteringAssignmentView> items = repository.listEffectiveMeteringAssignments(
                        version.versionId(), buildingId, boundedSize, (page - 1) * boundedSize)
                .stream().map(this::toView).toList();
        return new MeteringAssignmentsView(metadata(model, version, 0,
                page * (long) boundedSize < total), page, boundedSize, total, items);
    }

    public MeteringAssignmentsView historicalMeteringAssignments(
            Long userId, Collection<String> roles, String buildingId,
            String versionId, int page, int size) {
        requireReader(roles);
        checkBuilding(userId, roles, buildingId);
        requirePage(page, size);
        ModelRow model = requireModel(buildingId, false);
        VersionRow version = requireVersion(versionId, false);
        requireSameBuilding(buildingId, version.buildingId());
        int boundedSize = Math.min(size, properties.getMaxPageSize());
        long total = repository.countEffectiveMeteringAssignments(version.versionId(), buildingId);
        List<MeteringAssignmentView> items = repository.listEffectiveMeteringAssignments(
                        version.versionId(), buildingId, boundedSize, (page - 1) * boundedSize)
                .stream().map(this::toView).toList();
        return new MeteringAssignmentsView(metadata(model, version, 0,
                page * (long) boundedSize < total), page, boundedSize, total, items);
    }

    public MeterStructuresView effectiveMeterStructures(
            Long userId, Collection<String> roles, String buildingId, int page, int size) {
        requireReader(roles);
        checkBuilding(userId, roles, buildingId);
        ModelRow model = requireModel(buildingId, false);
        return meterStructures(model, requireEffective(model), page, size);
    }

    public MeterStructuresView historicalMeterStructures(
            Long userId, Collection<String> roles, String buildingId,
            String versionId, int page, int size) {
        requireHistoryReader(roles);
        checkBuilding(userId, roles, buildingId);
        ModelRow model = requireModel(buildingId, false);
        VersionRow version = requireVersion(versionId, false);
        requireSameBuilding(buildingId, version.buildingId());
        return meterStructures(model, version, page, size);
    }

    public MeterHierarchyView effectiveMeterHierarchy(
            Long userId, Collection<String> roles, String buildingId, String meterPointNodeId) {
        requireReader(roles);
        checkBuilding(userId, roles, buildingId);
        ModelRow model = requireModel(buildingId, false);
        return meterHierarchy(model, requireEffective(model), meterPointNodeId);
    }

    public MeterHierarchyView historicalMeterHierarchy(
            Long userId, Collection<String> roles, String buildingId,
            String versionId, String meterPointNodeId) {
        requireHistoryReader(roles);
        checkBuilding(userId, roles, buildingId);
        ModelRow model = requireModel(buildingId, false);
        VersionRow version = requireVersion(versionId, false);
        requireSameBuilding(buildingId, version.buildingId());
        return meterHierarchy(model, version, meterPointNodeId);
    }

    private MeterStructuresView meterStructures(
            ModelRow model, VersionRow version, int page, int size) {
        requirePage(page, size);
        int boundedSize = Math.min(size, properties.getMaxPageSize());
        long total = repository.countMeterStructures(version.versionId());
        List<MeterStructureView> items = repository.listMeterStructures(version.versionId(),
                boundedSize, (page - 1) * boundedSize).stream().map(this::toView).toList();
        return new MeterStructuresView(metadata(model, version, 0,
                page * (long) boundedSize < total), page, boundedSize, total, items);
    }

    private MeterHierarchyView meterHierarchy(
            ModelRow model, VersionRow version, String meterPointNodeId) {
        MeterStructureRow meter = repository.findMeterStructureByPoint(
                version.versionId(), meterPointNodeId)
                .orElseThrow(() -> error(404, NOT_FOUND, "指定表计不在当前关系版本中"));
        MeterStructureView parent = meter.parentMeterPointNodeId() == null ? null
                : repository.findMeterStructureByPoint(version.versionId(),
                meter.parentMeterPointNodeId()).map(this::toView).orElse(null);
        return new MeterHierarchyView(metadata(model, version, 1, false), toView(meter), parent,
                repository.listMeterChildren(version.versionId(), meter.meterPointNodeId())
                        .stream().map(this::toView).toList());
    }

    public EffectiveIssuesView effectiveIssues(
            Long userId, Collection<String> roles, String buildingId) {
        requireReader(roles);
        checkBuilding(userId, roles, buildingId);
        ModelRow model = requireModel(buildingId, false);
        VersionRow version = requireEffective(model);
        return new EffectiveIssuesView(metadata(model, version, 0, false),
                repository.listIssues(version.versionId()).stream().map(this::toView).toList());
    }

    public List<ReviewView> reviews(
            Long userId, Collection<String> roles, String buildingId) {
        requireHistoryReader(roles);
        checkBuilding(userId, roles, buildingId);
        return repository.listReviews(buildingId).stream().map(this::toView).toList();
    }

    public List<AuditView> audits(
            Long userId, Collection<String> roles, String buildingId) {
        requireHistoryReader(roles);
        checkBuilding(userId, roles, buildingId);
        return repository.listAudits(buildingId).stream().map(this::toView).toList();
    }

    private ValidationView validateInternal(VersionRow version) {
        repository.clearIssues(version.versionId());
        List<SpaceItem> spaces = repository.listSpaceItems(version.versionId());
        if (spaces.size() != repository.activeSpaceCount(version.buildingId())) {
            issue(version, "ERROR", REFERENCE_CONFLICT, "SPACE", null, "空间快照未覆盖全部有效空间");
        }
        Map<String, SpaceItem> spaceById = new HashMap<>();
        for (SpaceItem item : spaces) {
            spaceById.put(item.spaceId(), item);
            if (!Objects.equals(version.buildingId(), item.actualBuildingId())) {
                issue(version, "ERROR", CROSS_BUILDING, "SPACE", item.spaceId(), "空间不存在或不属于版本建筑");
            }
            if (item.parentSpaceId() != null && !spaceById.containsKey(item.parentSpaceId())) {
                // 父级可能排序在后，第二轮再确认。
            }
        }
        for (SpaceItem item : spaces) {
            if (item.parentSpaceId() != null && !spaceById.containsKey(item.parentSpaceId())) {
                issue(version, "ERROR", REFERENCE_CONFLICT, "SPACE", item.spaceId(), "父空间不在当前完整快照中");
            }
        }
        detectSpaceCycles(version, spaceById);

        List<AssignmentRow> assignments = repository.listAssignments(version.versionId());
        long equipmentCount = assignments.stream().filter(v -> "EQUIPMENT".equals(v.objectType())).count();
        long pointCount = assignments.stream().filter(v -> "POINT".equals(v.objectType())).count();
        if (equipmentCount != repository.activeEquipmentCount(version.buildingId())) {
            issue(version, "ERROR", REFERENCE_CONFLICT, "EQUIPMENT", null, "设备归属快照未覆盖全部有效设备");
        }
        if (pointCount != repository.activePointCount(version.buildingId())) {
            issue(version, "ERROR", REFERENCE_CONFLICT, "POINT", null, "测点归属快照未覆盖全部有效测点");
        }
        Map<String, AssignmentRow> equipment = new HashMap<>();
        for (AssignmentRow item : assignments) {
            if (!Objects.equals(version.buildingId(), item.buildingId())
                    || !repository.objectExistsInBuilding(item.objectType(), item.objectId(), version.buildingId())) {
                issue(version, "ERROR", CROSS_BUILDING, item.objectType(), item.objectId(), "归属对象不存在或跨建筑");
                continue;
            }
            if ("EQUIPMENT".equals(item.objectType())) {
                equipment.put(item.objectId(), item);
                if (item.spaceId() == null || item.systemGroupId() == null) {
                    issue(version, "ERROR", VALIDATION_FAILED, "EQUIPMENT", item.objectId(), "设备必须归属一个空间和一个系统");
                } else {
                    checkReference(version, "SPACE", item.spaceId(), "设备空间不属于版本建筑");
                    checkReference(version, "SYSTEM", item.systemGroupId(), "设备系统不属于版本建筑");
                }
            }
        }
        for (AssignmentRow item : assignments) {
            if (!"POINT".equals(item.objectType()) || item.equipmentId() == null) continue;
            AssignmentRow device = equipment.get(item.equipmentId());
            if (device == null || !Objects.equals(device.systemGroupId(), item.systemGroupId())) {
                issue(version, "ERROR", VALIDATION_FAILED, "POINT", item.objectId(), "普通设备测点必须与设备处于同一系统");
            }
        }

        for (SemanticRow relation : repository.listSemanticRelations(version.versionId())) {
            NodeRow source = repository.findNode(relation.sourceNodeId()).orElse(null);
            NodeRow target = repository.findNode(relation.targetNodeId()).orElse(null);
            if (source == null || target == null
                    || !Objects.equals(version.buildingId(), source.buildingId())
                    || !Objects.equals(version.buildingId(), target.buildingId())) {
                issue(version, "ERROR", CROSS_BUILDING, "SEMANTIC_RELATION",
                        relation.relationItemId(), "语义关系端点不存在或跨建筑");
            }
            if ("PENDING_EXPERT".equals(relation.confirmationStatus())) {
                issue(version, "PENDING_EXPERT", PENDING_EXPERT, "SEMANTIC_RELATION",
                        relation.relationItemId(), "语义关系等待能源专家确认");
            }
        }
        List<MeterStructureRow> structures = repository.listMeterStructures(version.versionId());
        Map<String, MeterStructureRow> structureByPoint = new HashMap<>();
        for (MeterStructureRow structure : structures) {
            structureByPoint.put(structure.meterPointNodeId(), structure);
            if (!Objects.equals(version.buildingId(), structure.buildingId())) {
                issue(version, "ERROR", CROSS_BUILDING, "METER_STRUCTURE",
                        structure.structureItemId(), "表计结构不属于当前建筑");
            }
            if ("PENDING_EXPERT".equals(structure.confirmationStatus())) {
                issue(version, "PENDING_EXPERT", PENDING_EXPERT, "METER_STRUCTURE",
                        structure.structureItemId(), "表计角色或计量方向等待能源专家确认");
            }
        }
        for (MeterStructureRow structure : structures) {
            if (structure.parentMeterPointNodeId() != null
                    && !structureByPoint.containsKey(structure.parentMeterPointNodeId())) {
                issue(version, "ERROR", REFERENCE_CONFLICT, "METER_STRUCTURE",
                        structure.structureItemId(), "上级表计未登记在当前关系版本中");
            }
        }
        detectMeterCycles(version, structureByPoint);
        for (MeteringAssignmentRow assignment : repository.listMeteringAssignments(version.versionId())) {
            if ("PENDING_EXPERT".equals(assignment.allocationStatus())) {
                issue(version, "PENDING_EXPERT", PENDING_EXPERT, "METERING_ASSIGNMENT",
                        assignment.assignmentItemId(), "计量分配等待能源专家确认");
            } else if ("INVALID".equals(assignment.allocationStatus())) {
                issue(version, "ERROR", VALIDATION_FAILED, "METERING_ASSIGNMENT",
                        assignment.assignmentItemId(), "计量分配被标记为非法");
            } else if ("UNASSIGNED".equals(assignment.allocationStatus())) {
                issue(version, "WARNING", UNASSIGNED, "METERING_ASSIGNMENT",
                        assignment.assignmentItemId(), "计量项处于未分配状态");
            }
            if (assignment.boundaryId() != null) {
                BoundaryRow boundary = repository.findBoundary(assignment.boundaryId()).orElse(null);
                if (boundary == null) {
                    issue(version, "ERROR", REFERENCE_CONFLICT, "METERING_BOUNDARY",
                            assignment.boundaryId(), "计量边界不存在");
                } else if ("PENDING_EXPERT".equals(boundary.confirmationStatus())) {
                    issue(version, "PENDING_EXPERT", PENDING_EXPERT, "METERING_BOUNDARY",
                            boundary.boundaryId(), "计量边界等待能源专家确认");
                }
            }
        }
        List<ValidationIssueView> views = repository.listIssues(version.versionId()).stream()
                .map(this::toView).toList();
        return new ValidationView(version.buildingId(), version.versionId(), version.configRevision(),
                count(views, "ERROR"), count(views, "PENDING_EXPERT"), count(views, "WARNING"), views);
    }

    private void detectSpaceCycles(VersionRow version, Map<String, SpaceItem> items) {
        Set<String> complete = new HashSet<>();
        for (String start : items.keySet()) {
            if (complete.contains(start)) continue;
            Set<String> path = new HashSet<>();
            String current = start;
            while (current != null && items.containsKey(current)) {
                if (!path.add(current)) {
                    issue(version, "ERROR", CYCLE_DETECTED, "SPACE", current, "空间父级关系形成循环");
                    break;
                }
                if (complete.contains(current)) break;
                current = items.get(current).parentSpaceId();
            }
            complete.addAll(path);
        }
    }

    private void detectMeterCycles(
            VersionRow version, Map<String, MeterStructureRow> structures) {
        Set<String> complete = new HashSet<>();
        for (String start : structures.keySet()) {
            if (complete.contains(start)) continue;
            Set<String> path = new HashSet<>();
            String current = start;
            while (current != null && structures.containsKey(current)) {
                if (!path.add(current)) {
                    issue(version, "ERROR", CYCLE_DETECTED, "METER_STRUCTURE",
                            structures.get(current).structureItemId(), "表计上下级关系形成循环");
                    break;
                }
                if (complete.contains(current)) break;
                current = structures.get(current).parentMeterPointNodeId();
            }
            complete.addAll(path);
        }
    }

    private void rejectMeterCycle(VersionRow version) {
        validateInternal(version);
        if (repository.listIssues(version.versionId()).stream()
                .anyMatch(issue -> CYCLE_DETECTED.equals(issue.code())
                        && "METER_STRUCTURE".equals(issue.objectType()))) {
            throw error(409, CYCLE_DETECTED, "表计上下级关系形成循环");
        }
    }

    private String snapshotHash(String versionId) {
        return digest(String.join("\n", snapshotItems(versionId)));
    }

    private List<String> snapshotItems(String versionId) {
        List<String> items = new ArrayList<>();
        repository.listSpaceItems(versionId).forEach(v -> items.add("SPACE:" + v.spaceId()
                + ":parent=" + safe(v.parentSpaceId()) + ":sort=" + v.sortOrder()));
        repository.listAssignments(versionId).forEach(v -> items.add("ASSET:" + v.objectType()
                + ':' + v.objectId() + ":space=" + safe(v.spaceId())
                + ":system=" + safe(v.systemGroupId()) + ":equipment=" + safe(v.equipmentId())
                + ":source=" + safe(v.sourceType())));
        repository.listSemanticRelations(versionId).forEach(v -> items.add("RELATION:" + v.relationType()
                + ':' + v.sourceNodeId() + ":to=" + v.targetNodeId()
                + ":source=" + v.sourceType()
                + ":confirmation=" + v.confirmationStatus()
                + ":evidence=" + safe(v.evidenceReference())
                + ":description=" + safe(v.description())));
        repository.listMeteringAssignments(versionId).forEach(v -> items.add("METERING:boundary="
                + safe(v.boundaryId())
                + ":point=" + safe(v.meterPointNodeId()) + ":target=" + safe(v.targetNodeId())
                + ":status=" + v.allocationStatus() + ":reason=" + safe(v.reasonCode())
                + ":reasonText=" + safe(v.reasonText())
                + ":evidence=" + safe(v.evidenceReference())));
        repository.listMeterStructures(versionId).forEach(v -> items.add("METER_STRUCTURE:point="
                + v.meterPointNodeId() + ":boundary=" + safe(v.boundaryId())
                + ":role=" + v.meterRole() + ":parent=" + safe(v.parentMeterPointNodeId())
                + ":direction=" + v.meterDirection() + ":confirmation=" + v.confirmationStatus()
                + ":reason=" + safe(v.reasonCode()) + ":reasonText=" + safe(v.reasonText())
                + ":evidence=" + safe(v.evidenceReference())
                + ":description=" + safe(v.description()) + ":source=" + v.sourceType()));
        repository.listBoundariesForSnapshot(versionId).forEach(v -> items.add("BOUNDARY:"
                + v.boundaryId() + ":code=" + v.boundaryCode() + ":name=" + v.boundaryName()
                + ":energy=" + safe(v.energyType()) + ":confirmation=" + v.confirmationStatus()
                + ":status=" + v.status() + ":evidence=" + safe(v.evidenceReference())));
        items.sort(String::compareTo);
        return List.copyOf(items);
    }

    private static List<String> subtract(List<String> source, List<String> baseline) {
        Map<String, Integer> remaining = new HashMap<>();
        baseline.forEach(item -> remaining.merge(item, 1, Integer::sum));
        List<String> result = new ArrayList<>();
        for (String item : source) {
            int count = remaining.getOrDefault(item, 0);
            if (count == 0) result.add(item);
            else remaining.put(item, count - 1);
        }
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    private SnapshotCounts snapshotCounts(String versionId) {
        return new SnapshotCounts(repository.listSpaceItems(versionId).size(),
                repository.listAssignments(versionId).size(),
                repository.listSemanticRelations(versionId).size(),
                repository.listBoundariesForSnapshot(versionId).size(),
                repository.listMeterStructures(versionId).size(),
                repository.listMeteringAssignments(versionId).size(),
                repository.listIssues(versionId).size());
    }

    private List<SpaceNodeView> buildTree(List<SpaceItem> items) {
        Map<String, List<SpaceItem>> children = new LinkedHashMap<>();
        for (SpaceItem item : items) {
            children.computeIfAbsent(item.parentSpaceId(), ignored -> new ArrayList<>()).add(item);
        }
        Comparator<SpaceItem> order = Comparator.comparingInt(SpaceItem::sortOrder)
                .thenComparing(SpaceItem::spaceId);
        children.values().forEach(list -> list.sort(order));
        return buildChildren(null, children, new HashSet<>());
    }

    private List<SpaceNodeView> buildChildren(
            String parentId, Map<String, List<SpaceItem>> children, Set<String> path) {
        List<SpaceNodeView> result = new ArrayList<>();
        for (SpaceItem item : children.getOrDefault(parentId, List.of())) {
            if (!path.add(item.spaceId())) continue;
            result.add(new SpaceNodeView(item.spaceId(), item.spaceName(), item.spaceType(),
                    item.parentSpaceId(), item.sortOrder(),
                    buildChildren(item.spaceId(), children, path)));
            path.remove(item.spaceId());
        }
        return List.copyOf(result);
    }

    private QueryMetadata metadata(ModelRow model, VersionRow version, int depth, boolean truncated) {
        List<ValidationIssueView> issues = repository.listIssues(version.versionId()).stream()
                .map(this::toView).toList();
        int unassigned = (int) repository.listMeteringAssignments(version.versionId()).stream()
                .filter(v -> "UNASSIGNED".equals(v.allocationStatus())).count();
        return new QueryMetadata(model.buildingId(), version.versionId(), version.versionNo(),
                version.effectiveAt(), model.configRevision(), depth, truncated, unassigned,
                count(issues, "PENDING_EXPERT"), count(issues, "ERROR"), count(issues, "WARNING"));
    }

    private static void validateSemanticRequest(
            SemanticRelationRequest request, String relationType,
            String sourceType, String confirmation) {
        if (!RELATION_TYPES.contains(relationType)
                || !Set.of("MANUAL", "IMPORT", "SYSTEM_MIGRATION").contains(sourceType)
                || !Set.of("CONFIRMED", "PENDING_EXPERT").contains(confirmation)) {
            throw error(400, VALIDATION_FAILED, "语义关系类型、来源或确认状态不合法");
        }
        if ("CONFIRMED".equals(confirmation) && trim(request.evidenceReference()) == null) {
            throw error(400, VALIDATION_FAILED, "已确认语义关系必须提供脱敏专业证据引用");
        }
    }

    private static String validateBoundaryRequest(MeteringBoundaryRequest request) {
        String confirmation = normalize(request.confirmationStatus());
        if (!Set.of("CONFIRMED", "PENDING_EXPERT").contains(confirmation)) {
            throw error(400, VALIDATION_FAILED, "计量边界确认状态不合法");
        }
        if ("CONFIRMED".equals(confirmation)
                && (trim(request.energyType()) == null || trim(request.evidenceReference()) == null)) {
            throw error(400, VALIDATION_FAILED, "已确认计量边界必须提供能源类型和脱敏专业证据引用");
        }
        return confirmation;
    }

    private VersionRow requireEditable(Long userId, Collection<String> roles, String versionId) {
        requireEnergyManager(roles);
        VersionRow version = requireVersion(versionId, true);
        checkBuilding(userId, roles, version.buildingId());
        requireState(version.status(), "DRAFT", "只有草稿版本可以编辑");
        return version;
    }

    private NodeRow requireNode(VersionRow version, String nodeId) {
        NodeRow node = repository.findNode(nodeId)
                .orElseThrow(() -> error(404, NOT_FOUND, "关系节点不存在"));
        requireSameBuilding(version.buildingId(), node.buildingId());
        if (!"ACTIVE".equals(node.status())
                || !repository.objectExistsInBuilding(node.nodeType(), node.businessObjectId(), version.buildingId())) {
            throw error(409, REFERENCE_CONFLICT, "关系节点已停用或业务对象不存在");
        }
        return node;
    }

    private void requireObject(VersionRow version, String nodeType, String objectId) {
        if (!repository.objectExistsInBuilding(nodeType, objectId, version.buildingId())) {
            throw error(409, CROSS_BUILDING, "对象不存在或不属于版本建筑");
        }
    }

    private void requireBoundary(VersionRow version, String boundaryId) {
        BoundaryRow boundary = repository.findBoundary(boundaryId)
                .orElseThrow(() -> error(404, NOT_FOUND, "计量边界不存在"));
        if (!repository.objectExistsInBuilding("METERING_BOUNDARY", boundary.boundaryId(), version.buildingId())) {
            throw error(409, CROSS_BUILDING, "计量边界不属于版本建筑");
        }
    }

    private void checkReference(VersionRow version, String type, String id, String message) {
        if (!repository.objectExistsInBuilding(type, id, version.buildingId())) {
            issue(version, "ERROR", CROSS_BUILDING, type, id, message);
        }
    }

    private void issue(
            VersionRow version, String level, String code,
            String objectType, String objectId, String message) {
        repository.insertIssue(version.versionId(), version.buildingId(), level, code,
                objectType, objectId, message);
    }

    private static void requireNoBlockingIssues(ValidationView validation) {
        if (validation.errorCount() > 0) {
            throw error(409, VALIDATION_FAILED, "关系版本存在阻塞错误");
        }
        if (validation.pendingExpertCount() > 0) {
            throw error(409, PENDING_EXPERT, "关系版本仍有待能源专家确认项");
        }
    }

    private void auditMutation(
            VersionRow before, VersionRow after, long userId, String action, String objectId) {
        auditMutation(before, after, userId, action, objectId, null, null);
    }

    private void auditMutation(
            VersionRow before, VersionRow after, long userId, String action, String objectId,
            String idempotencyKey, String requestHash) {
        repository.insertAudit(before.buildingId(), userId, action, "RELATION_ITEM", objectId,
                before.versionId(), null, "revision=" + before.configRevision(),
                "revision=" + after.configRevision(), null, null, idempotencyKey, requestHash);
    }

    private AuditRow replay(String key, String requestHash) {
        AuditRow audit = repository.findAuditByIdempotency(key).orElse(null);
        if (audit != null) requireSameRequest(audit.requestSha256(), requestHash);
        return audit;
    }

    private static void requireSameRequest(String actual, String expected) {
        if (!Objects.equals(actual, expected)) {
            throw error(409, IDEMPOTENCY_REUSED, "幂等键已用于不同请求");
        }
    }

    private ModelRow requireModel(String buildingId, boolean lock) {
        return repository.findModelByBuilding(buildingId, lock)
                .orElseThrow(() -> error(404, NOT_FOUND, "建筑尚未初始化关系模型"));
    }

    private VersionRow requireVersion(String versionId, boolean lock) {
        return repository.findVersion(versionId, lock)
                .orElseThrow(() -> error(404, NOT_FOUND, "关系版本不存在"));
    }

    private ReviewRow requireReview(String requestId, boolean lock) {
        return repository.findReview(requestId, lock)
                .orElseThrow(() -> error(404, NOT_FOUND, "审核申请不存在"));
    }

    private VersionRow requireEffective(ModelRow model) {
        if (model.activeVersionId() == null) {
            throw error(404, NOT_FOUND, "建筑尚无当前有效关系版本");
        }
        VersionRow version = requireVersion(model.activeVersionId(), false);
        requireState(version.status(), "EFFECTIVE", "当前有效版本指针不一致");
        return version;
    }

    private void checkBuilding(Long userId, Collection<String> roles, String buildingId) {
        if (!buildingScopeService.canAccess(userId, roles, buildingId)) {
            throw error(403, FORBIDDEN, "无权访问该建筑");
        }
    }

    private static void requireReader(Collection<String> roles) {
        if (!hasAny(roles, FormalRole.BUILDING_OWNER, FormalRole.ENERGY_MANAGER, FormalRole.PLATFORM_ADMIN)) {
            throw error(403, FORBIDDEN, "当前角色不能读取关系模型");
        }
    }

    private static void requireHistoryReader(Collection<String> roles) {
        if (!hasAny(roles, FormalRole.ENERGY_MANAGER, FormalRole.PLATFORM_ADMIN)) {
            throw error(403, FORBIDDEN, "当前角色不能读取关系历史");
        }
    }

    private static void requireEnergyManager(Collection<String> roles) {
        if (!hasAny(roles, FormalRole.ENERGY_MANAGER)) {
            throw error(403, FORBIDDEN, "只有能源管理员可以编辑和提交关系草稿");
        }
    }

    private static void requirePlatformAdmin(Collection<String> roles) {
        if (!hasAny(roles, FormalRole.PLATFORM_ADMIN)) {
            throw error(403, FORBIDDEN, "只有平台管理员可以审核和生效关系版本");
        }
    }

    private static boolean hasAny(Collection<String> roles, FormalRole... allowed) {
        if (roles == null) return false;
        Set<String> actual = new HashSet<>();
        roles.forEach(role -> actual.add(normalize(role)));
        for (FormalRole role : allowed) if (actual.contains(role.name())) return true;
        return false;
    }

    private static void requireSameBuilding(String expected, String actual) {
        if (!Objects.equals(expected, actual)) {
            throw error(409, CROSS_BUILDING, "首版禁止跨建筑关系");
        }
    }

    private static void requireState(String actual, String expected, String message) {
        if (!expected.equals(actual)) throw error(409, VERSION_CONFLICT, message);
    }

    private void requirePage(int page, int size) {
        if (page < 1 || size < 1 || size > properties.getMaxPageSize()) {
            throw error(400, VALIDATION_FAILED, "分页参数不合法");
        }
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 160) {
            throw error(400, VALIDATION_FAILED, "Idempotency-Key 不能为空且不能超过160字符");
        }
        return key.trim();
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) throw error(400, VALIDATION_FAILED, message);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static int count(List<ValidationIssueView> issues, String level) {
        return (int) issues.stream().filter(issue -> level.equals(issue.level())).count();
    }

    private ModelView toView(ModelRow row) {
        return new ModelView(row.modelId(), row.buildingId(), row.scopeType(), row.governanceMode(),
                row.activeVersionId(), row.draftVersionId(), row.configRevision());
    }

    private VersionView toView(VersionRow row) {
        return new VersionView(row.versionId(), row.modelId(), row.buildingId(), row.versionNo(),
                row.baseVersionId(), row.copiedFromVersionId(), row.status(), row.configRevision(),
                row.submittedRevision(), row.snapshotSha256(), row.changeReason(), row.createdBy(),
                row.submittedBy(), row.approvedBy(), row.activatedBy(), row.createTime(), row.effectiveAt());
    }

    private ReviewView toView(ReviewRow row) {
        return new ReviewView(row.requestId(), row.versionId(), row.buildingId(), row.requestNo(),
                row.status(), row.submittedRevision(), row.snapshotSha256(), row.submittedBy(),
                row.reviewerId(), row.reviewReason(), row.selfApprovalDevMode(),
                row.submittedAt(), row.reviewedAt());
    }

    private ValidationIssueView toView(RelationGovernanceRepository.IssueRow row) {
        return new ValidationIssueView(row.issueId(), row.level(), row.code(), row.objectType(),
                row.objectId(), row.message(), row.detectedAt());
    }

    private RelationEdgeView toView(SemanticRow row) {
        return new RelationEdgeView(row.relationItemId(), row.relationType(), row.sourceNodeId(),
                row.targetNodeId(), row.confirmationStatus(), row.description());
    }

    private MeteringBoundaryView toView(BoundaryRow row) {
        return new MeteringBoundaryView(row.boundaryId(), row.boundaryCode(), row.boundaryName(),
                row.energyType(), row.confirmationStatus(), row.status());
    }

    private MeteringAssignmentView toView(EffectiveMeteringAssignmentRow row) {
        return new MeteringAssignmentView(row.assignmentItemId(), row.allocationStatus(),
                row.reasonCode(), row.reasonText(), row.evidenceReference(), row.boundaryId(),
                row.boundaryCode(), row.boundaryName(), row.energyType(),
                row.boundaryConfirmationStatus(), row.boundaryStatus(), row.meterPointNodeId(),
                row.pointId(), row.pointCode(), row.pointName(), row.meterRole(),
                row.meterDirection(), row.meterConfirmationStatus(), row.targetNodeId(),
                row.targetNodeType(), row.targetObjectId(), row.targetObjectCode(),
                row.targetObjectName());
    }

    private MeterStructureView toView(MeterStructureRow row) {
        return new MeterStructureView(row.structureItemId(), row.boundaryId(),
                row.meterPointNodeId(), row.meterPointCode(), row.meterRole(),
                row.parentMeterPointNodeId(), row.parentMeterPointCode(), row.meterDirection(),
                row.confirmationStatus(), row.reasonCode(), row.reasonText(),
                row.evidenceReference(), row.description(), row.sourceType());
    }

    private AuditView toView(AuditRow row) {
        return new AuditView(row.auditId(), row.buildingId(), row.operatorId(), row.actionType(),
                row.objectType(), row.objectId(), row.versionId(), row.requestId(), row.beforeState(),
                row.afterState(), row.reason(), row.result(), row.summary(), row.operationTime());
    }
}
