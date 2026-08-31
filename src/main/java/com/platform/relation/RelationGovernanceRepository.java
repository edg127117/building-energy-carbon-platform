package com.platform.relation;

import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
@RequiredArgsConstructor
/**
 * 关系治理访问 MySQL 的唯一 JDBC 边界。
 *
 * <p>状态机、角色和图校验由 Service 负责；本仓储提供参数化 SQL、稳定排序和发布事务所需行锁。</p>
 */
public class RelationGovernanceRepository {
    @Qualifier("mysqlJdbcTemplate")
    private final JdbcTemplate jdbc;
    private final AuditGovernanceProperties auditProperties;

    public boolean buildingExists(String buildingId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM building WHERE building_id=? AND del_flag=0",
                Long.class, buildingId);
        return count != null && count > 0;
    }

    public boolean isGoverned(String buildingId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_relation_model
                WHERE building_id=? AND governance_mode='GOVERNED'
                """, Long.class, buildingId);
        return count != null && count > 0;
    }

    public boolean relationModelExists(String buildingId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_relation_model WHERE building_id=?",
                Long.class, buildingId);
        return count != null && count > 0;
    }

    public boolean referencedByLiveVersion(String objectType, String objectId) {
        String normalized = objectType.toUpperCase(java.util.Locale.ROOT);
        String sql = switch (normalized) {
            case "SPACE" -> """
                    SELECT COUNT(*) FROM biz_space_parent_version_item i
                    JOIN biz_relation_version v ON v.version_id=i.version_id
                    WHERE v.status IN ('DRAFT','PENDING_REVIEW','APPROVED','EFFECTIVE')
                      AND (i.space_id=? OR i.parent_space_id=?)
                    """;
            case "SYSTEM" -> """
                    SELECT COUNT(*) FROM biz_asset_assignment_version_item i
                    JOIN biz_relation_version v ON v.version_id=i.version_id
                    WHERE v.status IN ('DRAFT','PENDING_REVIEW','APPROVED','EFFECTIVE')
                      AND i.system_group_id=?
                    """;
            case "EQUIPMENT" -> """
                    SELECT COUNT(*) FROM biz_asset_assignment_version_item i
                    JOIN biz_relation_version v ON v.version_id=i.version_id
                    WHERE v.status IN ('DRAFT','PENDING_REVIEW','APPROVED','EFFECTIVE')
                      AND (i.object_type='EQUIPMENT' AND i.object_id=? OR i.equipment_id=?)
                    """;
            case "POINT" -> """
                    SELECT COUNT(*) FROM biz_asset_assignment_version_item i
                    JOIN biz_relation_version v ON v.version_id=i.version_id
                    WHERE v.status IN ('DRAFT','PENDING_REVIEW','APPROVED','EFFECTIVE')
                      AND i.object_type='POINT' AND i.object_id=?
                    """;
            default -> null;
        };
        if (sql == null) return false;
        Object[] arguments = Set.of("SPACE", "EQUIPMENT").contains(normalized)
                ? new Object[]{objectId, objectId} : new Object[]{objectId};
        Long direct = jdbc.queryForObject(sql, Long.class, arguments);
        if (direct != null && direct > 0) return true;
        Long semantic = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_relation_node n
                JOIN biz_semantic_relation_version_item r
                  ON r.source_node_id=n.node_id OR r.target_node_id=n.node_id
                JOIN biz_relation_version v ON v.version_id=r.version_id
                WHERE n.node_type=? AND n.business_object_id=?
                  AND v.status IN ('DRAFT','PENDING_REVIEW','APPROVED','EFFECTIVE')
                """, Long.class, normalized, objectId);
        return semantic != null && semantic > 0;
    }

    public Optional<ModelRow> findModelByBuilding(String buildingId, boolean lock) {
        return jdbc.query("""
                SELECT model_id,scope_type,scope_id,building_id,governance_mode,
                       active_version_id,draft_version_id,config_revision
                FROM biz_relation_model WHERE building_id=?
                """ + (lock ? " FOR UPDATE" : ""), MODEL_MAPPER, buildingId).stream().findFirst();
    }

    public void insertModel(String modelId, String buildingId, long userId) {
        jdbc.update("""
                INSERT INTO biz_relation_model
                (model_id,scope_type,scope_id,building_id,governance_mode,config_revision,
                 create_by,update_by,create_time,update_time)
                VALUES (?,'BUILDING',?,?,'LEGACY',0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, modelId, buildingId, buildingId, userId, userId);
    }

    public int nextVersionNo(String modelId) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0)+1 FROM biz_relation_version WHERE model_id=?",
                Integer.class, modelId);
        return value == null ? 1 : value;
    }

    public void insertVersion(
            String versionId, ModelRow model, int versionNo, String baseVersionId,
            String copiedFromVersionId, String reason, long userId) {
        jdbc.update("""
                INSERT INTO biz_relation_version
                (version_id,model_id,building_id,version_no,base_version_id,copied_from_version_id,
                 status,config_revision,change_reason,created_by,create_time,update_time)
                VALUES (?,?,?,?,?,?,'DRAFT',0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, versionId, model.modelId(), model.buildingId(), versionNo, baseVersionId,
                copiedFromVersionId, reason, userId);
    }

    public int setDraftPointer(
            String modelId, String draftVersionId, long expectedRevision, long userId) {
        return jdbc.update("""
                UPDATE biz_relation_model SET draft_version_id=?,config_revision=config_revision+1,
                    update_by=?,update_time=CURRENT_TIMESTAMP
                WHERE model_id=? AND config_revision=? AND draft_version_id IS NULL
                """, draftVersionId, userId, modelId, expectedRevision);
    }

    public Optional<VersionRow> findVersion(String versionId, boolean lock) {
        return jdbc.query(VERSION_SELECT + " WHERE version_id=?" + (lock ? " FOR UPDATE" : ""),
                VERSION_MAPPER, versionId).stream().findFirst();
    }

    public List<VersionRow> listVersions(String buildingId) {
        return jdbc.query(VERSION_SELECT + " WHERE building_id=? ORDER BY version_no DESC",
                VERSION_MAPPER, buildingId);
    }

    public void registerLegacyNodes(String buildingId, long userId) {
        registerNodes(buildingId, "SPACE", "SELECT space_id FROM biz_space WHERE building_id=? AND del_flag=0", userId);
        registerNodes(buildingId, "SYSTEM", "SELECT system_group_id FROM biz_system_group WHERE building_id=? AND del_flag=0", userId);
        registerNodes(buildingId, "EQUIPMENT", "SELECT equip_id FROM biz_equipment WHERE building_id=? AND del_flag=0", userId);
        registerNodes(buildingId, "POINT", "SELECT point_id FROM biz_data_point WHERE building_id=? AND del_flag=0", userId);
    }

    private void registerNodes(String buildingId, String nodeType, String sql, long userId) {
        List<String> ids = jdbc.query(sql, (rs, row) -> rs.getString(1), buildingId);
        for (String objectId : ids) {
            Long count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM biz_relation_node
                    WHERE building_id=? AND node_type=? AND business_object_id=?
                    """, Long.class, buildingId, nodeType, objectId);
            if (count == null || count == 0) {
                jdbc.update("""
                        INSERT INTO biz_relation_node
                        (node_id,building_id,node_type,business_object_id,status,
                         create_by,update_by,create_time,update_time)
                        VALUES (?,?,?,?,'ACTIVE',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        """, id(), buildingId, nodeType, objectId, userId, userId);
            }
        }
    }

    public void copyLegacySnapshot(String versionId, String buildingId) {
        jdbc.update("""
                INSERT INTO biz_space_parent_version_item
                (version_id,building_id,space_id,parent_space_id,sort_order)
                SELECT ?,building_id,space_id,parent_space_id,0
                FROM biz_space WHERE building_id=? AND del_flag=0
                """, versionId, buildingId);
        jdbc.update("""
                INSERT INTO biz_asset_assignment_version_item
                (version_id,building_id,object_type,object_id,space_id,system_group_id,equipment_id,source_type)
                SELECT ?,building_id,'EQUIPMENT',equip_id,space_id,system_group_id,NULL,'LEGACY_PROJECTION'
                FROM biz_equipment WHERE building_id=? AND del_flag=0
                """, versionId, buildingId);
        jdbc.update("""
                INSERT INTO biz_asset_assignment_version_item
                (version_id,building_id,object_type,object_id,space_id,system_group_id,equipment_id,source_type)
                SELECT ?,building_id,'POINT',point_id,NULL,system_group_id,equip_id,'LEGACY_PROJECTION'
                FROM biz_data_point WHERE building_id=? AND del_flag=0
                """, versionId, buildingId);
    }

    public void copyVersionSnapshot(String sourceVersionId, String targetVersionId, long userId) {
        jdbc.update("""
                INSERT INTO biz_space_parent_version_item
                (version_id,building_id,space_id,parent_space_id,sort_order)
                SELECT ?,building_id,space_id,parent_space_id,sort_order
                FROM biz_space_parent_version_item WHERE version_id=?
                """, targetVersionId, sourceVersionId);
        jdbc.update("""
                INSERT INTO biz_asset_assignment_version_item
                (version_id,building_id,object_type,object_id,space_id,system_group_id,equipment_id,source_type)
                SELECT ?,building_id,object_type,object_id,space_id,system_group_id,equipment_id,source_type
                FROM biz_asset_assignment_version_item WHERE version_id=?
                """, targetVersionId, sourceVersionId);
        for (SemanticRow row : listSemanticRelations(sourceVersionId)) {
            jdbc.update("""
                    INSERT INTO biz_semantic_relation_version_item
                    (relation_item_id,version_id,building_id,relation_type,source_node_id,target_node_id,
                     source_type,evidence_reference,confirmation_status,description,create_by,create_time)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                    """, id(), targetVersionId, row.buildingId(), row.relationType(),
                    row.sourceNodeId(), row.targetNodeId(), row.sourceType(), row.evidenceReference(),
                    row.confirmationStatus(), row.description(), userId);
        }
        for (MeteringAssignmentRow row : listMeteringAssignments(sourceVersionId)) {
            jdbc.update("""
                    INSERT INTO biz_metering_assignment_version_item
                    (assignment_item_id,version_id,building_id,metering_boundary_id,meter_point_node_id,
                     target_node_id,allocation_status,reason_code,reason_text,evidence_reference,create_by,create_time)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                    """, id(), targetVersionId, row.buildingId(), row.boundaryId(), row.meterPointNodeId(),
                    row.targetNodeId(), row.allocationStatus(), row.reasonCode(), row.reasonText(),
                    row.evidenceReference(), userId);
        }
        for (MeterStructureRow row : listMeterStructures(sourceVersionId)) {
            jdbc.update("""
                    INSERT INTO biz_meter_structure_version_item
                    (structure_item_id,version_id,building_id,metering_boundary_id,meter_point_node_id,
                     meter_role,parent_meter_point_node_id,meter_direction,confirmation_status,
                     reason_code,reason_text,evidence_reference,description,source_type,
                     create_by,update_by,create_time,update_time)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, id(), targetVersionId, row.buildingId(), row.boundaryId(),
                    row.meterPointNodeId(), row.meterRole(), row.parentMeterPointNodeId(),
                    row.meterDirection(), row.confirmationStatus(), row.reasonCode(), row.reasonText(),
                    row.evidenceReference(), row.description(), row.sourceType(), userId, userId);
        }
    }

    public int updateSpaceParent(
            String versionId, String spaceId, String parentSpaceId, int sortOrder,
            long expectedRevision, long userId) {
        int changed = jdbc.update("""
                UPDATE biz_relation_version SET config_revision=config_revision+1,
                    update_time=CURRENT_TIMESTAMP
                WHERE version_id=? AND status='DRAFT' AND config_revision=?
                """, versionId, expectedRevision);
        if (changed == 1) {
            int itemChanged = jdbc.update("""
                    UPDATE biz_space_parent_version_item SET parent_space_id=?,sort_order=?
                    WHERE version_id=? AND space_id=?
                    """, parentSpaceId, sortOrder, versionId, spaceId);
            if (itemChanged != 1) {
                throw RelationErrors.error(404, RelationErrors.NOT_FOUND, "空间不在当前关系快照中");
            }
            clearIssues(versionId);
        }
        return changed;
    }

    public int updateAssetAssignment(
            String versionId, String objectType, String objectId, String spaceId,
            String systemGroupId, String equipmentId, long expectedRevision) {
        int changed = bumpDraft(versionId, expectedRevision);
        if (changed == 1) {
            int itemChanged = jdbc.update("""
                    UPDATE biz_asset_assignment_version_item
                    SET space_id=?,system_group_id=?,equipment_id=?,source_type='MANUAL'
                    WHERE version_id=? AND object_type=? AND object_id=?
                    """, spaceId, systemGroupId, equipmentId, versionId, objectType, objectId);
            if (itemChanged != 1) {
                throw RelationErrors.error(404, RelationErrors.NOT_FOUND, "资产不在当前关系快照中");
            }
            clearIssues(versionId);
        }
        return changed;
    }

    public int insertSemanticRelation(
            String relationItemId, VersionRow version, String relationType,
            String sourceNodeId, String targetNodeId, String sourceType,
            String evidenceReference, String confirmationStatus, String description,
            long expectedRevision, long userId) {
        int changed = bumpDraft(version.versionId(), expectedRevision);
        if (changed == 1) {
            jdbc.update("""
                    INSERT INTO biz_semantic_relation_version_item
                    (relation_item_id,version_id,building_id,relation_type,source_node_id,target_node_id,
                     source_type,evidence_reference,confirmation_status,description,create_by,create_time)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                    """, relationItemId, version.versionId(), version.buildingId(), relationType,
                    sourceNodeId, targetNodeId, sourceType, evidenceReference,
                    confirmationStatus, description, userId);
            clearIssues(version.versionId());
        }
        return changed;
    }

    public int deleteSemanticRelation(
            String versionId, String relationItemId, long expectedRevision) {
        int changed = bumpDraft(versionId, expectedRevision);
        if (changed == 1) {
            int deleted = jdbc.update("""
                    DELETE FROM biz_semantic_relation_version_item
                    WHERE relation_item_id=? AND version_id=?
                    """, relationItemId, versionId);
            if (deleted != 1) {
                throw RelationErrors.error(404, RelationErrors.NOT_FOUND, "语义关系不存在");
            }
            clearIssues(versionId);
        }
        return changed;
    }

    public int updateSemanticRelation(
            String versionId, String relationItemId, String relationType,
            String sourceNodeId, String targetNodeId, String sourceType,
            String evidenceReference, String confirmationStatus, String description,
            long expectedRevision) {
        int changed = bumpDraft(versionId, expectedRevision);
        if (changed == 1) {
            int itemChanged = jdbc.update("""
                    UPDATE biz_semantic_relation_version_item
                    SET relation_type=?,source_node_id=?,target_node_id=?,source_type=?,
                        evidence_reference=?,confirmation_status=?,description=?
                    WHERE relation_item_id=? AND version_id=?
                    """, relationType, sourceNodeId, targetNodeId, sourceType,
                    evidenceReference, confirmationStatus, description, relationItemId, versionId);
            if (itemChanged != 1) {
                throw RelationErrors.error(404, RelationErrors.NOT_FOUND, "语义关系不存在");
            }
            clearIssues(versionId);
        }
        return changed;
    }

    public String insertMeteringBoundary(
            VersionRow version, String code, String name, String energyType,
            String confirmationStatus, String evidence, long expectedRevision, long userId) {
        if (bumpDraft(version.versionId(), expectedRevision) != 1) return null;
        String boundaryId = id();
        jdbc.update("""
                INSERT INTO biz_metering_boundary
                (boundary_id,building_id,boundary_code,boundary_name,energy_type,
                 confirmation_status,evidence_reference,status,create_by,update_by,create_time,update_time)
                VALUES (?,?,?,?,?,?,?,'ACTIVE',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, boundaryId, version.buildingId(), code, name, energyType,
                confirmationStatus, evidence, userId, userId);
        jdbc.update("""
                INSERT INTO biz_relation_node
                (node_id,building_id,node_type,business_object_id,status,
                 create_by,update_by,create_time,update_time)
                VALUES (?,?,'METERING_BOUNDARY',?,'ACTIVE',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, id(), version.buildingId(), boundaryId, userId, userId);
        clearIssues(version.versionId());
        return boundaryId;
    }

    public String insertMeteringBoundaryForImport(
            VersionRow version, String code, String name, String energyType,
            String confirmationStatus, String evidence, long userId) {
        String boundaryId = id();
        jdbc.update("""
                INSERT INTO biz_metering_boundary
                (boundary_id,building_id,boundary_code,boundary_name,energy_type,
                 confirmation_status,evidence_reference,status,create_by,update_by,create_time,update_time)
                VALUES (?,?,?,?,?,?,?,'ACTIVE',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, boundaryId, version.buildingId(), code, name, energyType,
                confirmationStatus, evidence, userId, userId);
        jdbc.update("""
                INSERT INTO biz_relation_node
                (node_id,building_id,node_type,business_object_id,status,
                 create_by,update_by,create_time,update_time)
                VALUES (?,?,'METERING_BOUNDARY',?,'ACTIVE',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, id(), version.buildingId(), boundaryId, userId, userId);
        return boundaryId;
    }

    public String insertMeteringAssignment(
            VersionRow version, String boundaryId, String meterPointNodeId, String targetNodeId,
            String status, String reasonCode, String reasonText, String evidence,
            long expectedRevision, long userId) {
        if (bumpDraft(version.versionId(), expectedRevision) != 1) return null;
        String assignmentId = id();
        jdbc.update("""
                INSERT INTO biz_metering_assignment_version_item
                (assignment_item_id,version_id,building_id,metering_boundary_id,meter_point_node_id,
                 target_node_id,allocation_status,reason_code,reason_text,evidence_reference,create_by,create_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                """, assignmentId, version.versionId(), version.buildingId(), boundaryId,
                meterPointNodeId, targetNodeId, status, reasonCode, reasonText, evidence, userId);
        clearIssues(version.versionId());
        return assignmentId;
    }

    public String insertMeteringAssignmentForImport(
            VersionRow version, String boundaryId, String meterPointNodeId, String targetNodeId,
            String status, String reasonCode, String reasonText, String evidence, long userId) {
        String assignmentId = id();
        jdbc.update("""
                INSERT INTO biz_metering_assignment_version_item
                (assignment_item_id,version_id,building_id,metering_boundary_id,meter_point_node_id,
                 target_node_id,allocation_status,reason_code,reason_text,evidence_reference,create_by,create_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                """, assignmentId, version.versionId(), version.buildingId(), boundaryId,
                meterPointNodeId, targetNodeId, status, reasonCode, reasonText, evidence, userId);
        return assignmentId;
    }

    public boolean meteringAssignmentExists(
            String versionId, String boundaryId, String meterPointNodeId, String targetNodeId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_metering_assignment_version_item
                WHERE version_id=?
                  AND ((metering_boundary_id=? ) OR (metering_boundary_id IS NULL AND ? IS NULL))
                  AND ((meter_point_node_id=? ) OR (meter_point_node_id IS NULL AND ? IS NULL))
                  AND ((target_node_id=? ) OR (target_node_id IS NULL AND ? IS NULL))
                """, Integer.class, versionId, boundaryId, boundaryId,
                meterPointNodeId, meterPointNodeId, targetNodeId, targetNodeId);
        return count != null && count > 0;
    }

    public int updateMeteringAssignment(
            String versionId, String assignmentId, String boundaryId,
            String meterPointNodeId, String targetNodeId, String status,
            String reasonCode, String reasonText, String evidence, long expectedRevision) {
        int changed = bumpDraft(versionId, expectedRevision);
        if (changed == 1) {
            int itemChanged = jdbc.update("""
                    UPDATE biz_metering_assignment_version_item
                    SET metering_boundary_id=?,meter_point_node_id=?,target_node_id=?,
                        allocation_status=?,reason_code=?,reason_text=?,evidence_reference=?
                    WHERE assignment_item_id=? AND version_id=?
                    """, boundaryId, meterPointNodeId, targetNodeId, status,
                    reasonCode, reasonText, evidence, assignmentId, versionId);
            if (itemChanged != 1) {
                throw RelationErrors.error(404, RelationErrors.NOT_FOUND, "计量分配项不存在");
            }
            clearIssues(versionId);
        }
        return changed;
    }

    public int deleteMeteringAssignment(
            String versionId, String assignmentId, long expectedRevision) {
        int changed = bumpDraft(versionId, expectedRevision);
        if (changed == 1) {
            int deleted = jdbc.update("""
                    DELETE FROM biz_metering_assignment_version_item
                    WHERE assignment_item_id=? AND version_id=?
                    """, assignmentId, versionId);
            if (deleted != 1) {
                throw RelationErrors.error(404, RelationErrors.NOT_FOUND, "计量分配项不存在");
            }
            clearIssues(versionId);
        }
        return changed;
    }

    public String insertMeterStructure(
            VersionRow version, String boundaryId, String meterPointNodeId,
            String meterRole, String parentMeterPointNodeId, String meterDirection,
            String confirmationStatus, String reasonCode, String reasonText,
            String evidence, String description, String sourceType,
            long expectedRevision, long userId) {
        if (bumpDraft(version.versionId(), expectedRevision) != 1) return null;
        String structureItemId = id();
        insertMeterStructureItem(structureItemId, version, boundaryId, meterPointNodeId,
                meterRole, parentMeterPointNodeId, meterDirection, confirmationStatus,
                reasonCode, reasonText, evidence, description, sourceType, userId);
        clearIssues(version.versionId());
        return structureItemId;
    }

    public int updateMeterStructure(
            String versionId, String structureItemId, String boundaryId,
            String meterPointNodeId, String meterRole, String parentMeterPointNodeId,
            String meterDirection, String confirmationStatus, String reasonCode,
            String reasonText, String evidence, String description, String sourceType,
            long expectedRevision, long userId) {
        int changed = bumpDraft(versionId, expectedRevision);
        if (changed == 1) {
            int itemChanged = jdbc.update("""
                    UPDATE biz_meter_structure_version_item
                    SET metering_boundary_id=?,meter_point_node_id=?,meter_role=?,
                        parent_meter_point_node_id=?,meter_direction=?,confirmation_status=?,
                        reason_code=?,reason_text=?,evidence_reference=?,description=?,source_type=?,
                        update_by=?,update_time=CURRENT_TIMESTAMP
                    WHERE structure_item_id=? AND version_id=?
                    """, boundaryId, meterPointNodeId, meterRole, parentMeterPointNodeId,
                    meterDirection, confirmationStatus, reasonCode, reasonText, evidence,
                    description, sourceType, userId, structureItemId, versionId);
            if (itemChanged != 1) {
                throw RelationErrors.error(404, RelationErrors.NOT_FOUND, "表计结构不存在");
            }
            clearIssues(versionId);
        }
        return changed;
    }

    public int deleteMeterStructure(String versionId, String structureItemId, long expectedRevision) {
        int changed = bumpDraft(versionId, expectedRevision);
        if (changed == 1) {
            int deleted = jdbc.update("""
                    DELETE FROM biz_meter_structure_version_item
                    WHERE structure_item_id=? AND version_id=?
                    """, structureItemId, versionId);
            if (deleted != 1) {
                throw RelationErrors.error(404, RelationErrors.NOT_FOUND, "表计结构不存在");
            }
            clearIssues(versionId);
        }
        return changed;
    }

    public void insertMeterStructureItem(
            String structureItemId, VersionRow version, String boundaryId,
            String meterPointNodeId, String meterRole, String parentMeterPointNodeId,
            String meterDirection, String confirmationStatus, String reasonCode,
            String reasonText, String evidence, String description, String sourceType, long userId) {
        jdbc.update("""
                INSERT INTO biz_meter_structure_version_item
                (structure_item_id,version_id,building_id,metering_boundary_id,meter_point_node_id,
                 meter_role,parent_meter_point_node_id,meter_direction,confirmation_status,
                 reason_code,reason_text,evidence_reference,description,source_type,
                 create_by,update_by,create_time,update_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, structureItemId, version.versionId(), version.buildingId(), boundaryId,
                meterPointNodeId, meterRole, parentMeterPointNodeId, meterDirection,
                confirmationStatus, reasonCode, reasonText, evidence, description,
                sourceType, userId, userId);
    }

    public void updateMeterStructureItemForImport(
            String versionId, String meterPointNodeId, String boundaryId, String meterRole,
            String parentMeterPointNodeId, String meterDirection, String confirmationStatus,
            String reasonCode, String reasonText, String evidence, String description, long userId) {
        jdbc.update("""
                UPDATE biz_meter_structure_version_item
                SET metering_boundary_id=?,meter_role=?,parent_meter_point_node_id=?,
                    meter_direction=?,confirmation_status=?,reason_code=?,reason_text=?,
                    evidence_reference=?,description=?,source_type='IMPORT',update_by=?,
                    update_time=CURRENT_TIMESTAMP
                WHERE version_id=? AND meter_point_node_id=?
                """, boundaryId, meterRole, parentMeterPointNodeId, meterDirection,
                confirmationStatus, reasonCode, reasonText, evidence, description,
                userId, versionId, meterPointNodeId);
    }

    public int bumpDraftForImport(String versionId, long expectedRevision) {
        int changed = bumpDraft(versionId, expectedRevision);
        if (changed == 1) clearIssues(versionId);
        return changed;
    }

    public boolean boundaryReferencedByFrozenVersion(String boundaryId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_relation_version v
                WHERE v.status IN ('PENDING_REVIEW','APPROVED','EFFECTIVE','SUPERSEDED')
                  AND (EXISTS (SELECT 1 FROM biz_metering_assignment_version_item a
                               WHERE a.version_id=v.version_id AND a.metering_boundary_id=?)
                    OR EXISTS (SELECT 1 FROM biz_meter_structure_version_item s
                               WHERE s.version_id=v.version_id AND s.metering_boundary_id=?))
                """, Integer.class, boundaryId, boundaryId);
        return count != null && count > 0;
    }

    public boolean boundaryReferencedByAnyVersion(String boundaryId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_relation_version v
                WHERE EXISTS (SELECT 1 FROM biz_metering_assignment_version_item a
                              WHERE a.version_id=v.version_id AND a.metering_boundary_id=?)
                   OR EXISTS (SELECT 1 FROM biz_meter_structure_version_item s
                              WHERE s.version_id=v.version_id AND s.metering_boundary_id=?)
                """, Integer.class, boundaryId, boundaryId);
        return count != null && count > 0;
    }

    public int updateMeteringBoundary(
            VersionRow version, String boundaryId, String code, String name,
            String energyType, String confirmationStatus, String evidence,
            long expectedRevision, long userId) {
        int changed = bumpDraft(version.versionId(), expectedRevision);
        if (changed == 1) {
            int itemChanged = jdbc.update("""
                    UPDATE biz_metering_boundary
                    SET boundary_code=?,boundary_name=?,energy_type=?,confirmation_status=?,
                        evidence_reference=?,update_by=?,update_time=CURRENT_TIMESTAMP
                    WHERE boundary_id=? AND building_id=? AND status='ACTIVE'
                    """, code, name, energyType, confirmationStatus, evidence,
                    userId, boundaryId, version.buildingId());
            if (itemChanged != 1) {
                throw RelationErrors.error(404, RelationErrors.NOT_FOUND, "活动计量边界不存在");
            }
            clearIssues(version.versionId());
        }
        return changed;
    }

    public int retireMeteringBoundary(
            VersionRow version, String boundaryId, long expectedRevision, long userId) {
        int changed = bumpDraft(version.versionId(), expectedRevision);
        if (changed == 1) {
            int itemChanged = jdbc.update("""
                    UPDATE biz_metering_boundary
                    SET status='RETIRED',update_by=?,update_time=CURRENT_TIMESTAMP
                    WHERE boundary_id=? AND building_id=? AND status='ACTIVE'
                    """, userId, boundaryId, version.buildingId());
            if (itemChanged != 1) {
                throw RelationErrors.error(404, RelationErrors.NOT_FOUND, "活动计量边界不存在");
            }
            jdbc.update("""
                    UPDATE biz_relation_node SET status='RETIRED',update_by=?,update_time=CURRENT_TIMESTAMP
                    WHERE building_id=? AND node_type='METERING_BOUNDARY' AND business_object_id=?
                    """, userId, version.buildingId(), boundaryId);
            clearIssues(version.versionId());
        }
        return changed;
    }

    private int bumpDraft(String versionId, long expectedRevision) {
        return jdbc.update("""
                UPDATE biz_relation_version SET config_revision=config_revision+1,
                    update_time=CURRENT_TIMESTAMP
                WHERE version_id=? AND status='DRAFT' AND config_revision=?
                """, versionId, expectedRevision);
    }

    public Optional<NodeRow> findNode(String nodeId) {
        return jdbc.query("""
                SELECT node_id,building_id,node_type,business_object_id,status
                FROM biz_relation_node WHERE node_id=?
                """, (rs, row) -> new NodeRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5)), nodeId).stream().findFirst();
    }

    public Optional<NodeRow> findNodeByObject(String buildingId, String nodeType, String objectId) {
        return jdbc.query("""
                SELECT node_id,building_id,node_type,business_object_id,status
                FROM biz_relation_node WHERE building_id=? AND node_type=? AND business_object_id=?
                """, (rs, row) -> new NodeRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5)), buildingId, nodeType, objectId).stream().findFirst();
    }

    public Optional<NodeRow> findNodeByCode(String buildingId, String nodeType, String objectCode) {
        String sql = switch (nodeType) {
            case "POINT" -> """
                    SELECT n.node_id,n.building_id,n.node_type,n.business_object_id,n.status
                    FROM biz_relation_node n JOIN biz_data_point p ON p.point_id=n.business_object_id
                    WHERE n.building_id=? AND n.node_type='POINT' AND p.point_code=? AND p.del_flag=0
                    """;
            case "SPACE" -> """
                    SELECT n.node_id,n.building_id,n.node_type,n.business_object_id,n.status
                    FROM biz_relation_node n JOIN biz_space s ON s.space_id=n.business_object_id
                    WHERE n.building_id=? AND n.node_type='SPACE' AND s.space_code=? AND s.del_flag=0
                    """;
            case "SYSTEM" -> """
                    SELECT n.node_id,n.building_id,n.node_type,n.business_object_id,n.status
                    FROM biz_relation_node n JOIN biz_system_group g ON g.system_group_id=n.business_object_id
                    WHERE n.building_id=? AND n.node_type='SYSTEM' AND g.system_group_code=? AND g.del_flag=0
                    """;
            case "EQUIPMENT" -> """
                    SELECT n.node_id,n.building_id,n.node_type,n.business_object_id,n.status
                    FROM biz_relation_node n JOIN biz_equipment e ON e.equip_id=n.business_object_id
                    WHERE n.building_id=? AND n.node_type='EQUIPMENT' AND e.equip_code=? AND e.del_flag=0
                    """;
            default -> throw RelationErrors.error(400, RelationErrors.VALIDATION_FAILED,
                    "覆盖对象类型不支持");
        };
        return jdbc.query(sql, (rs, row) -> new NodeRow(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5)), buildingId, objectCode)
                .stream().findFirst();
    }

    public List<SpaceItem> listSpaceItems(String versionId) {
        return jdbc.query("""
                SELECT i.space_id,i.parent_space_id,i.sort_order,s.space_name,s.space_type,s.building_id
                FROM biz_space_parent_version_item i
                LEFT JOIN biz_space s ON s.space_id=i.space_id AND s.del_flag=0
                WHERE i.version_id=? ORDER BY i.sort_order,i.space_id
                """, (rs, row) -> new SpaceItem(rs.getString(1), rs.getString(2), rs.getInt(3),
                rs.getString(4), rs.getString(5), rs.getString(6)), versionId);
    }

    public List<AssignmentRow> listAssignments(String versionId) {
        return jdbc.query("""
                SELECT object_type,object_id,space_id,system_group_id,equipment_id,building_id,source_type
                FROM biz_asset_assignment_version_item WHERE version_id=?
                ORDER BY object_type,object_id
                """, (rs, row) -> new AssignmentRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)), versionId);
    }

    public List<SemanticRow> listSemanticRelations(String versionId) {
        return jdbc.query("""
                SELECT relation_item_id,relation_type,source_node_id,target_node_id,
                       source_type,evidence_reference,confirmation_status,description,building_id
                FROM biz_semantic_relation_version_item WHERE version_id=?
                ORDER BY relation_type,source_node_id,target_node_id
                """, (rs, row) -> new SemanticRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9)), versionId);
    }

    public List<MeteringAssignmentRow> listMeteringAssignments(String versionId) {
        return jdbc.query("""
                SELECT assignment_item_id,metering_boundary_id,meter_point_node_id,target_node_id,
                       allocation_status,reason_code,reason_text,evidence_reference,building_id
                FROM biz_metering_assignment_version_item WHERE version_id=?
                ORDER BY assignment_item_id
                """, (rs, row) -> new MeteringAssignmentRow(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9)), versionId);
    }

    public List<MeterStructureRow> listMeterStructures(String versionId) {
        return jdbc.query("""
                SELECT s.structure_item_id,s.metering_boundary_id,s.meter_point_node_id,
                       p.point_code,s.meter_role,s.parent_meter_point_node_id,pp.point_code,
                       s.meter_direction,s.confirmation_status,s.reason_code,s.reason_text,
                       s.evidence_reference,s.description,s.source_type,s.building_id
                FROM biz_meter_structure_version_item s
                JOIN biz_relation_node n ON n.node_id=s.meter_point_node_id
                JOIN biz_data_point p ON p.point_id=n.business_object_id
                LEFT JOIN biz_relation_node pn ON pn.node_id=s.parent_meter_point_node_id
                LEFT JOIN biz_data_point pp ON pp.point_id=pn.business_object_id
                WHERE s.version_id=? ORDER BY p.point_code,s.structure_item_id
                """, METER_STRUCTURE_MAPPER, versionId);
    }

    public Optional<MeterStructureRow> findMeterStructure(
            String versionId, String structureItemId) {
        return listMeterStructures(versionId).stream()
                .filter(row -> row.structureItemId().equals(structureItemId)).findFirst();
    }

    public Optional<MeterStructureRow> findMeterStructureByPoint(
            String versionId, String meterPointNodeId) {
        return listMeterStructures(versionId).stream()
                .filter(row -> row.meterPointNodeId().equals(meterPointNodeId)).findFirst();
    }

    public List<MeterStructureRow> listMeterStructures(
            String versionId, int limit, int offset) {
        return jdbc.query("""
                SELECT s.structure_item_id,s.metering_boundary_id,s.meter_point_node_id,
                       p.point_code,s.meter_role,s.parent_meter_point_node_id,pp.point_code,
                       s.meter_direction,s.confirmation_status,s.reason_code,s.reason_text,
                       s.evidence_reference,s.description,s.source_type,s.building_id
                FROM biz_meter_structure_version_item s
                JOIN biz_relation_node n ON n.node_id=s.meter_point_node_id
                JOIN biz_data_point p ON p.point_id=n.business_object_id
                LEFT JOIN biz_relation_node pn ON pn.node_id=s.parent_meter_point_node_id
                LEFT JOIN biz_data_point pp ON pp.point_id=pn.business_object_id
                WHERE s.version_id=? ORDER BY p.point_code,s.structure_item_id LIMIT ? OFFSET ?
                """, METER_STRUCTURE_MAPPER, versionId, limit, offset);
    }

    public long countMeterStructures(String versionId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_meter_structure_version_item WHERE version_id=?",
                Long.class, versionId);
        return count == null ? 0 : count;
    }

    public List<MeterStructureRow> listMeterChildren(String versionId, String parentPointNodeId) {
        return listMeterStructures(versionId).stream()
                .filter(row -> parentPointNodeId.equals(row.parentMeterPointNodeId())).toList();
    }

    public int activeSpaceCount(String buildingId) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_space WHERE building_id=? AND del_flag=0",
                Integer.class, buildingId);
        return value == null ? 0 : value;
    }

    public int activeEquipmentCount(String buildingId) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_equipment WHERE building_id=? AND del_flag=0",
                Integer.class, buildingId);
        return value == null ? 0 : value;
    }

    public int activePointCount(String buildingId) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_data_point WHERE building_id=? AND del_flag=0",
                Integer.class, buildingId);
        return value == null ? 0 : value;
    }

    public Optional<BoundaryRow> findBoundary(String boundaryId) {
        return jdbc.query("""
                SELECT boundary_id,boundary_code,boundary_name,energy_type,confirmation_status,status,
                       evidence_reference
                FROM biz_metering_boundary WHERE boundary_id=?
                """, (rs, row) -> new BoundaryRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)), boundaryId)
                .stream().findFirst();
    }

    public Optional<BoundaryRow> findBoundaryByCode(String buildingId, String boundaryCode) {
        return jdbc.query("""
                SELECT boundary_id,boundary_code,boundary_name,energy_type,
                       confirmation_status,status,evidence_reference
                FROM biz_metering_boundary WHERE building_id=? AND boundary_code=?
                """, (rs, row) -> new BoundaryRow(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7)), buildingId, boundaryCode).stream().findFirst();
    }

    public boolean objectExistsInBuilding(String nodeType, String objectId, String buildingId) {
        String sql = switch (nodeType) {
            case "SPACE" -> "SELECT COUNT(*) FROM biz_space WHERE space_id=? AND building_id=? AND del_flag=0";
            case "SYSTEM" -> "SELECT COUNT(*) FROM biz_system_group WHERE system_group_id=? AND building_id=? AND del_flag=0";
            case "EQUIPMENT" -> "SELECT COUNT(*) FROM biz_equipment WHERE equip_id=? AND building_id=? AND del_flag=0";
            case "POINT" -> "SELECT COUNT(*) FROM biz_data_point WHERE point_id=? AND building_id=? AND del_flag=0";
            case "METERING_BOUNDARY" -> "SELECT COUNT(*) FROM biz_metering_boundary WHERE boundary_id=? AND building_id=? AND status='ACTIVE'";
            default -> null;
        };
        if (sql == null) return false;
        Long count = jdbc.queryForObject(sql, Long.class, objectId, buildingId);
        return count != null && count > 0;
    }

    public void clearIssues(String versionId) {
        jdbc.update("DELETE FROM biz_relation_validation_issue WHERE version_id=?", versionId);
    }

    public void insertIssue(
            String versionId, String buildingId, String level, String code,
            String objectType, String objectId, String message) {
        jdbc.update("""
                INSERT INTO biz_relation_validation_issue
                (issue_id,version_id,building_id,issue_level,issue_code,object_type,object_id,message,detected_at)
                VALUES (?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                """, id(), versionId, buildingId, level, code, objectType, objectId, message);
    }

    public List<IssueRow> listIssues(String versionId) {
        return jdbc.query("""
                SELECT issue_id,issue_level,issue_code,object_type,object_id,message,detected_at
                FROM biz_relation_validation_issue WHERE version_id=?
                ORDER BY issue_level,issue_code,object_type,object_id
                """, (rs, row) -> new IssueRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), time(rs.getTimestamp(7))), versionId);
    }

    public int submitVersion(
            String versionId, long expectedRevision, String hash, long userId) {
        return jdbc.update("""
                UPDATE biz_relation_version
                SET status='PENDING_REVIEW',submitted_revision=config_revision,snapshot_sha256=?,
                    submitted_by=?,submitted_at=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP
                WHERE version_id=? AND status='DRAFT' AND config_revision=?
                """, hash, userId, versionId, expectedRevision);
    }

    public int nextReviewNo(String versionId) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(request_no),0)+1 FROM biz_relation_review_request WHERE version_id=?",
                Integer.class, versionId);
        return value == null ? 1 : value;
    }

    public void insertReview(
            String requestId, VersionRow version, int requestNo, long submittedRevision,
            String snapshotHash, long userId, String idempotencyKey, String requestHash) {
        jdbc.update("""
                INSERT INTO biz_relation_review_request
                (request_id,version_id,building_id,request_no,submitted_revision,snapshot_sha256,
                 status,submitted_by,self_approval_dev_mode,idempotency_key,request_sha256,submitted_at)
                VALUES (?,?,?,?,?,?,'PENDING',?,0,?,?,CURRENT_TIMESTAMP)
                """, requestId, version.versionId(), version.buildingId(), requestNo,
                submittedRevision, snapshotHash, userId, idempotencyKey, requestHash);
    }

    public Optional<ReviewRow> findReview(String requestId, boolean lock) {
        return jdbc.query(REVIEW_SELECT + " WHERE request_id=?" + (lock ? " FOR UPDATE" : ""),
                REVIEW_MAPPER, requestId).stream().findFirst();
    }

    public Optional<ReviewRow> findReviewByIdempotency(String key) {
        return jdbc.query(REVIEW_SELECT + " WHERE idempotency_key=?", REVIEW_MAPPER, key)
                .stream().findFirst();
    }

    public List<ReviewRow> listReviews(String buildingId) {
        return jdbc.query(REVIEW_SELECT + " WHERE building_id=? ORDER BY submitted_at DESC",
                REVIEW_MAPPER, buildingId);
    }

    public int approveReview(String requestId, long reviewerId, String reason, boolean selfApproval) {
        return jdbc.update("""
                UPDATE biz_relation_review_request
                SET status='APPROVED',reviewer_id=?,review_reason=?,self_approval_dev_mode=?,
                    reviewed_at=CURRENT_TIMESTAMP
                WHERE request_id=? AND status='PENDING'
                """, reviewerId, reason, selfApproval ? 1 : 0, requestId);
    }

    public int markVersionApproved(String versionId, long approvedBy, long submittedRevision, String hash) {
        return jdbc.update("""
                UPDATE biz_relation_version SET status='APPROVED',approved_by=?,approved_at=CURRENT_TIMESTAMP,
                    update_time=CURRENT_TIMESTAMP
                WHERE version_id=? AND status='PENDING_REVIEW'
                  AND submitted_revision=? AND snapshot_sha256=?
                """, approvedBy, versionId, submittedRevision, hash);
    }

    public int rejectReview(String requestId, long reviewerId, String reason) {
        return jdbc.update("""
                UPDATE biz_relation_review_request
                SET status='REJECTED',reviewer_id=?,review_reason=?,reviewed_at=CURRENT_TIMESTAMP
                WHERE request_id=? AND status='PENDING'
                """, reviewerId, reason, requestId);
    }

    public void markVersionRejected(String versionId) {
        jdbc.update("""
                UPDATE biz_relation_version SET status='REJECTED',update_time=CURRENT_TIMESTAMP
                WHERE version_id=? AND status='PENDING_REVIEW'
                """, versionId);
    }

    public int withdrawVersion(String versionId, long expectedRevision) {
        return jdbc.update("""
                UPDATE biz_relation_version SET status='WITHDRAWN',update_time=CURRENT_TIMESTAMP
                WHERE version_id=? AND status IN ('DRAFT','PENDING_REVIEW') AND config_revision=?
                """, versionId, expectedRevision);
    }

    public void withdrawPendingReview(String versionId) {
        jdbc.update("""
                UPDATE biz_relation_review_request SET status='WITHDRAWN',withdrawn_at=CURRENT_TIMESTAMP
                WHERE version_id=? AND status='PENDING'
                """, versionId);
    }

    public void clearDraftPointer(String modelId, String versionId, long userId) {
        jdbc.update("""
                UPDATE biz_relation_model SET draft_version_id=NULL,config_revision=config_revision+1,
                    update_by=?,update_time=CURRENT_TIMESTAMP
                WHERE model_id=? AND draft_version_id=?
                """, userId, modelId, versionId);
    }

    public void activateProjection(VersionRow version) {
        jdbc.update("""
                UPDATE biz_space s SET parent_space_id=(
                    SELECT i.parent_space_id FROM biz_space_parent_version_item i
                    WHERE i.version_id=? AND i.space_id=s.space_id
                ),update_time=CURRENT_TIMESTAMP
                WHERE s.building_id=? AND EXISTS (
                    SELECT 1 FROM biz_space_parent_version_item i
                    WHERE i.version_id=? AND i.space_id=s.space_id
                )
                """, version.versionId(), version.buildingId(), version.versionId());
        jdbc.update("""
                UPDATE biz_equipment e SET
                  space_id=(SELECT i.space_id FROM biz_asset_assignment_version_item i
                    WHERE i.version_id=? AND i.object_type='EQUIPMENT' AND i.object_id=e.equip_id),
                  system_group_id=(SELECT i.system_group_id FROM biz_asset_assignment_version_item i
                    WHERE i.version_id=? AND i.object_type='EQUIPMENT' AND i.object_id=e.equip_id),
                  update_time=CURRENT_TIMESTAMP
                WHERE e.building_id=? AND EXISTS (
                  SELECT 1 FROM biz_asset_assignment_version_item i
                  WHERE i.version_id=? AND i.object_type='EQUIPMENT' AND i.object_id=e.equip_id)
                """, version.versionId(), version.versionId(), version.buildingId(), version.versionId());
        jdbc.update("""
                UPDATE biz_data_point p SET
                  equip_id=(SELECT i.equipment_id FROM biz_asset_assignment_version_item i
                    WHERE i.version_id=? AND i.object_type='POINT' AND i.object_id=p.point_id),
                  system_group_id=(SELECT i.system_group_id FROM biz_asset_assignment_version_item i
                    WHERE i.version_id=? AND i.object_type='POINT' AND i.object_id=p.point_id),
                  update_time=CURRENT_TIMESTAMP
                WHERE p.building_id=? AND EXISTS (
                  SELECT 1 FROM biz_asset_assignment_version_item i
                  WHERE i.version_id=? AND i.object_type='POINT' AND i.object_id=p.point_id)
                """, version.versionId(), version.versionId(), version.buildingId(), version.versionId());
    }

    public int activateVersion(
            ModelRow model, VersionRow version, long expectedModelRevision, long userId) {
        if (model.activeVersionId() != null) {
            jdbc.update("""
                    UPDATE biz_relation_version SET status='SUPERSEDED',superseded_at=CURRENT_TIMESTAMP,
                        update_time=CURRENT_TIMESTAMP
                    WHERE version_id=? AND status='EFFECTIVE'
                    """, model.activeVersionId());
        }
        int versionChanged = jdbc.update("""
                UPDATE biz_relation_version SET status='EFFECTIVE',activated_by=?,effective_at=CURRENT_TIMESTAMP,
                    update_time=CURRENT_TIMESTAMP WHERE version_id=? AND status='APPROVED'
                """, userId, version.versionId());
        int modelChanged = jdbc.update("""
                UPDATE biz_relation_model
                SET governance_mode='GOVERNED',active_version_id=?,draft_version_id=NULL,
                    config_revision=config_revision+1,update_by=?,update_time=CURRENT_TIMESTAMP
                WHERE model_id=? AND config_revision=? AND draft_version_id=?
                """, version.versionId(), userId, model.modelId(), expectedModelRevision, version.versionId());
        return versionChanged == 1 && modelChanged == 1 ? 1 : 0;
    }

    public void insertAudit(
            String buildingId, long operatorId, String action, String objectType, String objectId,
            String versionId, String requestId, String beforeState, String afterState,
            String reason, String summary, String idempotencyKey, String requestHash) {
        jdbc.update("""
                INSERT INTO biz_relation_audit_log
                (audit_id,building_id,actor_type,operator_id,action_type,object_type,object_id,version_id,
                 request_id,before_state,after_state,reason,result,trace_id,environment_mode,
                 self_approval_dev_mode,summary,idempotency_key,request_sha256,operation_time)
                VALUES (?,?, 'USER',?,?,?,?,?,?,?,?,?,'SUCCESS',?,?,?,?,?,?,CURRENT_TIMESTAMP)
                """, id(), buildingId, operatorId, action, objectType, objectId, versionId,
                requestId, beforeState, afterState, reason, TraceContext.current(),
                auditProperties.getEnvironmentMode().name(), "SELF_APPROVAL_DEV_MODE".equals(action),
                summary, idempotencyKey, requestHash);
    }

    public Optional<AuditRow> findAuditByIdempotency(String key) {
        return jdbc.query(AUDIT_SELECT + " WHERE idempotency_key=?", AUDIT_MAPPER, key)
                .stream().findFirst();
    }

    public List<AuditRow> listAudits(String buildingId) {
        return jdbc.query(AUDIT_SELECT + " WHERE building_id=? ORDER BY operation_time DESC",
                AUDIT_MAPPER, buildingId);
    }

    public List<SemanticRow> listContextEdges(String versionId, String nodeId, int limit, int offset) {
        return jdbc.query("""
                SELECT relation_item_id,relation_type,source_node_id,target_node_id,
                       source_type,evidence_reference,confirmation_status,description,building_id
                FROM biz_semantic_relation_version_item
                WHERE version_id=? AND (source_node_id=? OR target_node_id=?)
                ORDER BY relation_type,relation_item_id LIMIT ? OFFSET ?
                """, (rs, row) -> new SemanticRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9)), versionId, nodeId, nodeId, limit, offset);
    }

    public long countContextEdges(String versionId, String nodeId) {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_semantic_relation_version_item
                WHERE version_id=? AND (source_node_id=? OR target_node_id=?)
                """, Long.class, versionId, nodeId, nodeId);
        return value == null ? 0 : value;
    }

    public List<BoundaryRow> listBoundaries(String versionId, String buildingId, int limit, int offset) {
        return jdbc.query("""
                SELECT DISTINCT b.boundary_id,b.boundary_code,b.boundary_name,b.energy_type,
                       b.confirmation_status,b.status,b.evidence_reference
                FROM biz_metering_boundary b
                WHERE b.building_id=? AND (
                  EXISTS (SELECT 1 FROM biz_metering_assignment_version_item a
                          WHERE a.metering_boundary_id=b.boundary_id AND a.version_id=?)
                  OR EXISTS (SELECT 1 FROM biz_meter_structure_version_item s
                             WHERE s.metering_boundary_id=b.boundary_id AND s.version_id=?))
                ORDER BY b.boundary_code LIMIT ? OFFSET ?
                """, (rs, row) -> new BoundaryRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)),
                buildingId, versionId, versionId, limit, offset);
    }

    public long countBoundaries(String versionId, String buildingId) {
        Long value = jdbc.queryForObject(
                """
                SELECT COUNT(DISTINCT b.boundary_id) FROM biz_metering_boundary b
                WHERE b.building_id=? AND (
                  EXISTS (SELECT 1 FROM biz_metering_assignment_version_item a
                          WHERE a.metering_boundary_id=b.boundary_id AND a.version_id=?)
                  OR EXISTS (SELECT 1 FROM biz_meter_structure_version_item s
                             WHERE s.metering_boundary_id=b.boundary_id AND s.version_id=?))
                """, Long.class, buildingId, versionId, versionId);
        return value == null ? 0 : value;
    }

    public List<BoundaryRow> listBoundariesForSnapshot(String versionId) {
        return jdbc.query("""
                SELECT DISTINCT b.boundary_id,b.boundary_code,b.boundary_name,b.energy_type,
                       b.confirmation_status,b.status,b.evidence_reference
                FROM biz_metering_boundary b
                WHERE EXISTS (SELECT 1 FROM biz_metering_assignment_version_item a
                              WHERE a.metering_boundary_id=b.boundary_id AND a.version_id=?)
                   OR EXISTS (SELECT 1 FROM biz_meter_structure_version_item s
                              WHERE s.metering_boundary_id=b.boundary_id AND s.version_id=?)
                ORDER BY b.boundary_code,b.boundary_id
                """, (rs, row) -> new BoundaryRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)), versionId, versionId);
    }

    private static String id() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private static LocalDateTime time(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static final String VERSION_SELECT = """
            SELECT version_id,model_id,building_id,version_no,base_version_id,copied_from_version_id,
                   status,config_revision,submitted_revision,snapshot_sha256,change_reason,created_by,
                   submitted_by,approved_by,activated_by,create_time,effective_at
            FROM biz_relation_version
            """;

    private static final org.springframework.jdbc.core.RowMapper<ModelRow> MODEL_MAPPER = (rs, row) ->
            new ModelRow(rs.getString("model_id"), rs.getString("scope_type"), rs.getString("scope_id"),
                    rs.getString("building_id"), rs.getString("governance_mode"),
                    rs.getString("active_version_id"), rs.getString("draft_version_id"),
                    rs.getLong("config_revision"));

    private static final org.springframework.jdbc.core.RowMapper<VersionRow> VERSION_MAPPER = (rs, row) ->
            new VersionRow(rs.getString("version_id"), rs.getString("model_id"),
                    rs.getString("building_id"), rs.getInt("version_no"),
                    rs.getString("base_version_id"), rs.getString("copied_from_version_id"),
                    rs.getString("status"), rs.getLong("config_revision"),
                    nullableLong(rs, "submitted_revision"), rs.getString("snapshot_sha256"),
                    rs.getString("change_reason"), nullableLong(rs, "created_by"),
                    nullableLong(rs, "submitted_by"), nullableLong(rs, "approved_by"),
                    nullableLong(rs, "activated_by"), time(rs.getTimestamp("create_time")),
                    time(rs.getTimestamp("effective_at")));

    private static final String REVIEW_SELECT = """
            SELECT request_id,version_id,building_id,request_no,status,submitted_revision,
                   snapshot_sha256,submitted_by,reviewer_id,review_reason,self_approval_dev_mode,
                   idempotency_key,request_sha256,submitted_at,reviewed_at
            FROM biz_relation_review_request
            """;

    private static final org.springframework.jdbc.core.RowMapper<ReviewRow> REVIEW_MAPPER = (rs, row) ->
            new ReviewRow(rs.getString("request_id"), rs.getString("version_id"),
                    rs.getString("building_id"), rs.getInt("request_no"), rs.getString("status"),
                    rs.getLong("submitted_revision"), rs.getString("snapshot_sha256"),
                    nullableLong(rs, "submitted_by"), nullableLong(rs, "reviewer_id"),
                    rs.getString("review_reason"), rs.getBoolean("self_approval_dev_mode"),
                    rs.getString("idempotency_key"), rs.getString("request_sha256"),
                    time(rs.getTimestamp("submitted_at")), time(rs.getTimestamp("reviewed_at")));

    private static final String AUDIT_SELECT = """
            SELECT audit_id,building_id,operator_id,action_type,object_type,object_id,version_id,
                   request_id,before_state,after_state,reason,result,summary,idempotency_key,
                   request_sha256,operation_time FROM biz_relation_audit_log
            """;

    private static final org.springframework.jdbc.core.RowMapper<AuditRow> AUDIT_MAPPER = (rs, row) ->
            new AuditRow(rs.getString("audit_id"), rs.getString("building_id"),
                    nullableLong(rs, "operator_id"), rs.getString("action_type"),
                    rs.getString("object_type"), rs.getString("object_id"),
                    rs.getString("version_id"), rs.getString("request_id"),
                    rs.getString("before_state"), rs.getString("after_state"),
                    rs.getString("reason"), rs.getString("result"), rs.getString("summary"),
                    rs.getString("idempotency_key"), rs.getString("request_sha256"),
                    time(rs.getTimestamp("operation_time")));

    private static final org.springframework.jdbc.core.RowMapper<MeterStructureRow> METER_STRUCTURE_MAPPER =
            (rs, row) -> new MeterStructureRow(rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                    rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                    rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14),
                    rs.getString(15));

    public record ModelRow(
            String modelId, String scopeType, String scopeId, String buildingId,
            String governanceMode, String activeVersionId, String draftVersionId,
            long configRevision) {}

    public record VersionRow(
            String versionId, String modelId, String buildingId, int versionNo,
            String baseVersionId, String copiedFromVersionId, String status,
            long configRevision, Long submittedRevision, String snapshotSha256,
            String changeReason, Long createdBy, Long submittedBy, Long approvedBy,
            Long activatedBy, LocalDateTime createTime, LocalDateTime effectiveAt) {}

    public record ReviewRow(
            String requestId, String versionId, String buildingId, int requestNo,
            String status, long submittedRevision, String snapshotSha256,
            Long submittedBy, Long reviewerId, String reviewReason,
            boolean selfApprovalDevMode, String idempotencyKey, String requestSha256,
            LocalDateTime submittedAt, LocalDateTime reviewedAt) {}

    public record NodeRow(
            String nodeId, String buildingId, String nodeType, String businessObjectId, String status) {}

    public record SpaceItem(
            String spaceId, String parentSpaceId, int sortOrder,
            String spaceName, String spaceType, String actualBuildingId) {}

    public record AssignmentRow(
            String objectType, String objectId, String spaceId, String systemGroupId,
            String equipmentId, String buildingId, String sourceType) {}

    public record SemanticRow(
            String relationItemId, String relationType, String sourceNodeId,
            String targetNodeId, String sourceType, String evidenceReference,
            String confirmationStatus, String description, String buildingId) {}

    public record MeteringAssignmentRow(
            String assignmentItemId, String boundaryId, String meterPointNodeId,
            String targetNodeId, String allocationStatus, String reasonCode,
            String reasonText, String evidenceReference, String buildingId) {}

    public record MeterStructureRow(
            String structureItemId, String boundaryId, String meterPointNodeId,
            String meterPointCode, String meterRole, String parentMeterPointNodeId,
            String parentMeterPointCode, String meterDirection, String confirmationStatus,
            String reasonCode, String reasonText, String evidenceReference,
            String description, String sourceType, String buildingId) {}

    public record IssueRow(
            String issueId, String level, String code, String objectType,
            String objectId, String message, LocalDateTime detectedAt) {}

    public record BoundaryRow(
            String boundaryId, String boundaryCode, String boundaryName,
            String energyType, String confirmationStatus, String status,
            String evidenceReference) {}

    public record AuditRow(
            String auditId, String buildingId, Long operatorId, String actionType,
            String objectType, String objectId, String versionId, String requestId,
            String beforeState, String afterState, String reason, String result,
            String summary, String idempotencyKey, String requestSha256,
            LocalDateTime operationTime) {}
}
