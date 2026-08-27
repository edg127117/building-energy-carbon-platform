package com.platform.iot.deviceparameter;

import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.TraceContext;
import com.platform.iot.deviceparameter.DeviceParameterModels.Applicability;
import com.platform.iot.deviceparameter.DeviceParameterModels.Candidate;
import com.platform.iot.deviceparameter.DeviceParameterModels.ChangeType;
import com.platform.iot.deviceparameter.DeviceParameterModels.Definition;
import com.platform.iot.deviceparameter.DeviceParameterModels.EquipmentIdentity;
import com.platform.iot.deviceparameter.DeviceParameterModels.ParameterVersion;
import com.platform.iot.deviceparameter.DeviceParameterModels.PublishType;
import com.platform.iot.deviceparameter.DeviceParameterModels.RecalculationStatus;
import com.platform.iot.deviceparameter.DeviceParameterModels.SourceType;
import com.platform.iot.deviceparameter.DeviceParameterModels.TimelineRevision;
import com.platform.iot.deviceparameter.DeviceParameterModels.TimelineSegment;
import com.platform.iot.deviceparameter.DeviceParameterModels.ValidationStatus;
import com.platform.iot.deviceparameter.DeviceParameterModels.VersionStatus;
import com.platform.iot.deviceparameter.DeviceParameterModels.VersionValue;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
/**
 * 设备参数治理访问 MySQL 的唯一 JDBC 边界。
 *
 * <p>调用方负责角色、建筑范围和状态机，本仓储只执行参数化 SQL。发布路径通过
 * {@code FOR UPDATE} 锁定参数集、版本、审核和时间线指针，避免并发审批产生部分时间线。</p>
 */
public class DeviceParameterJdbcRepository {
    @Qualifier("mysqlJdbcTemplate")
    private final JdbcTemplate jdbc;
    private final AuditGovernanceProperties auditProperties;

    public Optional<EquipmentIdentity> findEquipment(String equipmentId) {
        return jdbc.query("""
                SELECT equip_id,equip_code,type_code,building_id,product_id
                FROM biz_equipment WHERE equip_id=? AND del_flag=0
                """, EQUIPMENT_MAPPER, equipmentId).stream().findFirst();
    }

    /** 发布事务统一使用数据库时钟，避免应用节点时钟差造成双时间线顺序漂移。 */
    public LocalDateTime currentTimestamp() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (value == null) {
            throw new IllegalStateException("数据库未返回当前时间");
        }
        return value.toLocalDateTime();
    }

    public Optional<EquipmentIdentity> findEquipmentByCode(String buildingId, String equipmentCode) {
        return jdbc.query("""
                SELECT equip_id,equip_code,type_code,building_id,product_id
                FROM biz_equipment WHERE building_id=? AND equip_code=? AND del_flag=0
                """, EQUIPMENT_MAPPER, buildingId, equipmentCode).stream().findFirst();
    }

    public Optional<EquipmentIdentity> findEquipmentByExternalIdentity(
            String identityType, String identityValue) {
        return jdbc.query("""
                SELECT e.equip_id,e.equip_code,e.type_code,e.building_id,e.product_id
                FROM biz_device_identity i
                JOIN biz_equipment e ON e.equip_id=i.equip_id AND e.building_id=i.building_id
                WHERE i.identity_type=? AND i.identity_value=? AND i.status=1 AND e.del_flag=0
                """, EQUIPMENT_MAPPER, identityType, identityValue).stream().findFirst();
    }

    public Optional<String> findExpectedProfileCode(
            String identityType, String identityValue) {
        return jdbc.query("""
                SELECT expected_profile_code FROM biz_device_identity
                WHERE identity_type=? AND identity_value=? AND status=1
                """, (rs, row) -> rs.getString(1), identityType, identityValue)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public boolean unitExists(String unitCode, String quantityKind, boolean enabledOnly) {
        String sql = "SELECT COUNT(*) FROM biz_device_parameter_unit WHERE unit_code=? AND quantity_kind=?"
                + (enabledOnly ? " AND status='ENABLED'" : "");
        Long count = jdbc.queryForObject(sql, Long.class, unitCode, quantityKind);
        return count != null && count > 0;
    }

    public void insertUnit(
            String unitCode, String quantityKind, String symbol, String evidence,
            long userId, String status) {
        jdbc.update("""
                INSERT INTO biz_device_parameter_unit
                (unit_code,quantity_kind,unit_symbol,status,evidence_reference,config_revision,
                 create_by,update_by,create_time,update_time)
                VALUES (?,?,?,?,?,0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, unitCode, quantityKind, symbol, status, evidence, userId, userId);
    }

    public int updateUnit(
            String unitCode, String quantityKind, String symbol, String evidence,
            String status, int expectedRevision, long userId) {
        return jdbc.update("""
                UPDATE biz_device_parameter_unit
                SET quantity_kind=?,unit_symbol=?,evidence_reference=?,status=?,
                    config_revision=config_revision+1,update_by=?,update_time=CURRENT_TIMESTAMP
                WHERE unit_code=? AND config_revision=?
                """, quantityKind, symbol, evidence, status, userId, unitCode, expectedRevision);
    }

    public void insertDefinition(Definition value, long userId) {
        jdbc.update("""
                INSERT INTO biz_device_parameter_definition
                (definition_id,parameter_code,parameter_name,business_definition,quantity_kind,
                 value_type,standard_unit,storage_scale,display_scale,evidence_reference,status,
                 config_revision,create_by,update_by,create_time,update_time)
                VALUES (?,?,?,?,?,'DECIMAL',?,?,?,?,?,0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, value.definitionId(), value.parameterCode(), value.parameterName(),
                value.businessDefinition(), value.quantityKind(), value.standardUnit(),
                value.storageScale(), value.displayScale(), value.evidenceReference(),
                value.status(), userId, userId);
    }

    public int updateDefinition(Definition value, int expectedRevision, long userId) {
        return jdbc.update("""
                UPDATE biz_device_parameter_definition
                SET parameter_name=?,business_definition=?,storage_scale=?,display_scale=?,
                    evidence_reference=?,status=?,config_revision=config_revision+1,
                    update_by=?,update_time=CURRENT_TIMESTAMP
                WHERE definition_id=? AND config_revision=?
                """, value.parameterName(), value.businessDefinition(), value.storageScale(),
                value.displayScale(), value.evidenceReference(), value.status(), userId,
                value.definitionId(), expectedRevision);
    }

    public Optional<Definition> findDefinition(String definitionId) {
        return jdbc.query(DEFINITION_SELECT + " WHERE definition_id=?", DEFINITION_MAPPER,
                definitionId).stream().findFirst();
    }

    public Optional<Definition> findDefinitionByCode(String parameterCode) {
        return jdbc.query(DEFINITION_SELECT + " WHERE parameter_code=?", DEFINITION_MAPPER,
                parameterCode).stream().findFirst();
    }

    public List<Definition> listDefinitions() {
        return jdbc.query(DEFINITION_SELECT + " ORDER BY parameter_code", DEFINITION_MAPPER);
    }

    public boolean definitionReferenced(String definitionId) {
        Long count = jdbc.queryForObject("""
                SELECT
                  (SELECT COUNT(*) FROM biz_device_parameter_applicability WHERE definition_id=?) +
                  (SELECT COUNT(*) FROM biz_device_parameter_candidate WHERE definition_id=?) +
                  (SELECT COUNT(*) FROM biz_device_parameter_version_value WHERE definition_id=?) +
                  (SELECT COUNT(*) FROM biz_device_parameter_mapping_version WHERE definition_id=?)
                """, Long.class, definitionId, definitionId, definitionId, definitionId);
        return count != null && count > 0;
    }

    public void insertApplicability(Applicability value, long userId) {
        jdbc.update("""
                INSERT INTO biz_device_parameter_applicability
                (applicability_id,equipment_type_code,definition_id,required_flag,formula_readable,
                 hard_min,hard_max,warning_min,warning_max,comparison_tolerance,evidence_reference,
                 status,config_revision,create_by,update_by,create_time,update_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, value.applicabilityId(), value.equipmentTypeCode(), value.definitionId(),
                flag(value.required()), flag(value.formulaReadable()), value.hardMin(), value.hardMax(),
                value.warningMin(), value.warningMax(), value.comparisonTolerance(),
                value.evidenceReference(), value.status(), userId, userId);
    }

    public int updateApplicability(Applicability value, int expectedRevision, long userId) {
        return jdbc.update("""
                UPDATE biz_device_parameter_applicability
                SET required_flag=?,formula_readable=?,hard_min=?,hard_max=?,warning_min=?,warning_max=?,
                    comparison_tolerance=?,evidence_reference=?,status=?,config_revision=config_revision+1,
                    update_by=?,update_time=CURRENT_TIMESTAMP
                WHERE applicability_id=? AND config_revision=?
                """, flag(value.required()), flag(value.formulaReadable()), value.hardMin(), value.hardMax(),
                value.warningMin(), value.warningMax(), value.comparisonTolerance(),
                value.evidenceReference(), value.status(), userId, value.applicabilityId(), expectedRevision);
    }

    public Optional<Applicability> findApplicability(String equipmentType, String definitionId) {
        return jdbc.query(APPLICABILITY_SELECT
                        + " WHERE equipment_type_code=? AND definition_id=?",
                APPLICABILITY_MAPPER, equipmentType, definitionId).stream().findFirst();
    }

    public List<Applicability> listApplicabilities(String equipmentType, boolean enabledOnly) {
        return jdbc.query(APPLICABILITY_SELECT + " WHERE equipment_type_code=?"
                        + (enabledOnly ? " AND status='ENABLED'" : "")
                        + " ORDER BY definition_id",
                APPLICABILITY_MAPPER, equipmentType);
    }

    public Optional<MappingVersion> findMappingVersion(String mappingVersionId) {
        return jdbc.query(MAPPING_VERSION_SELECT + " WHERE v.mapping_version_id=?",
                MAPPING_VERSION_MAPPER, mappingVersionId).stream().findFirst();
    }

    public List<MappingVersion> listMappingVersions(String mappingId) {
        return jdbc.query(MAPPING_VERSION_SELECT
                        + " WHERE v.mapping_id=? ORDER BY v.version_no DESC",
                MAPPING_VERSION_MAPPER, mappingId);
    }

    public Optional<ProductIdentity> findProduct(String productId) {
        return jdbc.query("""
                SELECT product_id,equipment_type_code,status FROM biz_device_product WHERE product_id=?
                """, (rs, row) -> new ProductIdentity(rs.getString("product_id"),
                rs.getString("equipment_type_code"), rs.getString("status")), productId)
                .stream().findFirst();
    }

    public Optional<TemplateHead> findTemplateHead(String productId, boolean lock) {
        String sql = """
                SELECT template_id,product_id,current_active_revision_id,config_revision
                FROM biz_product_parameter_template WHERE product_id=?
                """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, row) -> new TemplateHead(rs.getString("template_id"),
                rs.getString("product_id"), rs.getString("current_active_revision_id"),
                rs.getInt("config_revision")), productId).stream().findFirst();
    }

    public void insertTemplateHead(String templateId, String productId, long userId) {
        jdbc.update("""
                INSERT INTO biz_product_parameter_template
                (template_id,product_id,config_revision,create_by,create_time,update_time)
                VALUES (?,?,0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, templateId, productId, userId);
    }

    public int nextTemplateRevisionNo(String templateId) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision_no),0)+1 FROM biz_product_parameter_template_revision
                WHERE template_id=?
                """, Integer.class, templateId);
        return value == null ? 1 : value;
    }

    public void insertTemplateRevision(TemplateRevision revision, List<TemplateValue> values) {
        jdbc.update("""
                INSERT INTO biz_product_parameter_template_revision
                (template_revision_id,template_id,revision_no,status,change_reason,
                 evidence_reference,created_by,create_time)
                VALUES (?,?,?,'DRAFT',?,?,?,CURRENT_TIMESTAMP)
                """, revision.revisionId(), revision.templateId(), revision.revisionNo(),
                revision.changeReason(), revision.evidenceReference(), revision.createdBy());
        jdbc.batchUpdate("""
                INSERT INTO biz_product_parameter_template_value
                (template_value_id,template_revision_id,definition_id,raw_value,raw_unit,
                 normalized_value,source_reference,sort_order)
                VALUES (?,?,?,?,?,?,?,?)
                """, values, values.size(), (ps, value) -> {
            ps.setString(1, ids());
            ps.setString(2, revision.revisionId());
            ps.setString(3, value.definitionId());
            ps.setBigDecimal(4, value.rawValue());
            ps.setString(5, value.rawUnit());
            ps.setBigDecimal(6, value.normalizedValue());
            ps.setString(7, value.sourceReference());
            ps.setInt(8, value.sortOrder());
        });
    }

    public Optional<TemplateRevision> findTemplateRevision(String revisionId) {
        return jdbc.query("""
                SELECT r.template_revision_id,r.template_id,r.revision_no,r.status,
                       r.change_reason,r.evidence_reference,r.created_by,t.product_id
                FROM biz_product_parameter_template_revision r
                JOIN biz_product_parameter_template t ON t.template_id=r.template_id
                WHERE r.template_revision_id=?
                """, TEMPLATE_REVISION_MAPPER, revisionId).stream().findFirst();
    }

    public Optional<TemplateRevision> findActiveTemplateRevision(String productId) {
        return jdbc.query("""
                SELECT r.template_revision_id,r.template_id,r.revision_no,r.status,
                       r.change_reason,r.evidence_reference,r.created_by,t.product_id
                FROM biz_product_parameter_template t
                JOIN biz_product_parameter_template_revision r
                  ON r.template_revision_id=t.current_active_revision_id
                WHERE t.product_id=? AND r.status='ACTIVE'
                """, TEMPLATE_REVISION_MAPPER, productId).stream().findFirst();
    }

    public List<TemplateValue> listTemplateValues(String revisionId) {
        return jdbc.query("""
                SELECT definition_id,raw_value,raw_unit,normalized_value,source_reference,sort_order
                FROM biz_product_parameter_template_value
                WHERE template_revision_id=? ORDER BY sort_order,definition_id
                """, TEMPLATE_VALUE_MAPPER, revisionId);
    }

    public int publishTemplateRevision(
            String templateId, String revisionId, int expectedRevision, long userId) {
        jdbc.update("""
                UPDATE biz_product_parameter_template_revision SET status='RETIRED'
                WHERE template_id=? AND status='ACTIVE'
                """, templateId);
        int revisionChanged = jdbc.update("""
                UPDATE biz_product_parameter_template_revision
                SET status='ACTIVE',published_by=?,published_at=CURRENT_TIMESTAMP
                WHERE template_revision_id=? AND template_id=? AND status='DRAFT'
                """, userId, revisionId, templateId);
        int headChanged = jdbc.update("""
                UPDATE biz_product_parameter_template
                SET current_active_revision_id=?,config_revision=config_revision+1,
                    update_time=CURRENT_TIMESTAMP
                WHERE template_id=? AND config_revision=?
                """, revisionId, templateId, expectedRevision);
        return revisionChanged == 1 && headChanged == 1 ? 1 : 0;
    }

    public Optional<MappingVersion> findActiveMapping(String profileCode, String sourcePath) {
        return jdbc.query(MAPPING_VERSION_SELECT + """
                 WHERE m.profile_code=? AND m.source_path=? AND v.status='ACTIVE'
                """, MAPPING_VERSION_MAPPER, profileCode, sourcePath).stream().findFirst();
    }

    public Optional<MappingHead> findMappingHead(String profileCode, String sourcePath, boolean lock) {
        String sql = """
                SELECT mapping_id,profile_code,source_path,current_active_version_id,config_revision
                FROM biz_device_parameter_mapping WHERE profile_code=? AND source_path=?
                """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, MAPPING_HEAD_MAPPER, profileCode, sourcePath).stream().findFirst();
    }

    public void insertMappingHead(
            String mappingId, String profileCode, String sourcePath, long userId) {
        jdbc.update("""
                INSERT INTO biz_device_parameter_mapping
                (mapping_id,profile_code,source_path,config_revision,create_by,create_time,update_time)
                VALUES (?,?,?,0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, mappingId, profileCode, sourcePath, userId);
    }

    public int nextMappingVersionNo(String mappingId) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0)+1 FROM biz_device_parameter_mapping_version WHERE mapping_id=?",
                Integer.class, mappingId);
        return value == null ? 1 : value;
    }

    public void insertMappingVersion(MappingVersion value) {
        jdbc.update("""
                INSERT INTO biz_device_parameter_mapping_version
                (mapping_version_id,mapping_id,version_no,status,profile_version,definition_id,
                 source_unit,scale_value,offset_value,required_flag,base_version_id,copied_from_version_id,
                 change_reason,evidence_reference,created_by,create_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                """, value.mappingVersionId(), value.mappingId(), value.versionNo(), value.status(),
                value.profileVersion(), value.definitionId(), value.sourceUnit(), value.scale(),
                value.offset(), flag(value.required()), value.baseVersionId(), value.copiedFromVersionId(),
                value.changeReason(), value.evidenceReference(), value.createdBy());
    }

    public int publishMappingVersion(
            String mappingId, String mappingVersionId, int expectedRevision, long userId) {
        jdbc.update("""
                UPDATE biz_device_parameter_mapping_version
                SET status='RETIRED' WHERE mapping_id=? AND status='ACTIVE'
                """, mappingId);
        int versionUpdated = jdbc.update("""
                UPDATE biz_device_parameter_mapping_version
                SET status='ACTIVE',published_by=?,published_at=CURRENT_TIMESTAMP
                WHERE mapping_version_id=? AND mapping_id=? AND status='DRAFT'
                """, userId, mappingVersionId, mappingId);
        int headUpdated = jdbc.update("""
                UPDATE biz_device_parameter_mapping
                SET current_active_version_id=?,config_revision=config_revision+1,update_time=CURRENT_TIMESTAMP
                WHERE mapping_id=? AND config_revision=?
                """, mappingVersionId, mappingId, expectedRevision);
        return versionUpdated == 1 && headUpdated == 1 ? 1 : 0;
    }

    public Optional<Candidate> findIdempotentCandidate(
            String equipmentId, SourceType sourceType, String sourceParameterKey,
            String sourceVersion, String payloadHash) {
        return jdbc.query(CANDIDATE_SELECT + """
                 WHERE c.equip_id=? AND c.source_type=? AND c.source_parameter_key=?
                   AND ((c.source_version IS NULL AND ? IS NULL) OR c.source_version=?)
                   AND c.payload_hash=?
                """, CANDIDATE_MAPPER, equipmentId, sourceType.name(), sourceParameterKey,
                sourceVersion, sourceVersion, payloadHash).stream().findFirst();
    }

    public void insertCandidate(Candidate value, String sourceSlotKey, Long createdBy) {
        jdbc.update("""
                INSERT INTO biz_device_parameter_candidate
                (candidate_id,building_id,equip_id,definition_id,source_type,source_reference,
                 source_version,source_slot_key,source_parameter_key,raw_value,raw_unit,
                 normalized_value,standard_unit,mapping_version_id,observed_at,validation_status,
                 validation_reason,warning_flag,payload_hash,current_flag,supersedes_candidate_id,
                 first_seen_at,last_seen_at,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, value.candidateId(), value.buildingId(), value.equipmentId(),
                value.definitionId(), value.sourceType().name(), value.sourceReference(),
                value.sourceVersion(), sourceSlotKey, value.sourceParameterKey(), value.rawValue(),
                value.rawUnit(), value.normalizedValue(), value.standardUnit(), value.mappingVersionId(),
                timestamp(value.observedAt()), value.validationStatus().name(), value.validationReason(),
                flag(value.warning()), value.payloadHash(), flag(value.current()), null,
                timestamp(value.firstSeenAt()), timestamp(value.lastSeenAt()), createdBy);
    }

    public int replaceCurrentCandidate(String sourceSlotKey, String newCandidateId) {
        return jdbc.update("""
                UPDATE biz_device_parameter_candidate
                SET current_flag=0
                WHERE source_slot_key=? AND current_flag=1 AND candidate_id<>?
                """, sourceSlotKey, newCandidateId);
    }

    public void touchCandidate(String candidateId, LocalDateTime lastSeenAt) {
        jdbc.update("UPDATE biz_device_parameter_candidate SET last_seen_at=? WHERE candidate_id=?",
                timestamp(lastSeenAt), candidateId);
    }

    public Optional<Candidate> findCandidate(String candidateId) {
        return jdbc.query(CANDIDATE_SELECT + " WHERE c.candidate_id=?", CANDIDATE_MAPPER,
                candidateId).stream().findFirst();
    }

    public List<Candidate> listCandidates(String equipmentId, boolean currentOnly) {
        return jdbc.query(CANDIDATE_SELECT + " WHERE c.equip_id=?"
                        + (currentOnly ? " AND c.current_flag=1" : "")
                        + " ORDER BY c.last_seen_at DESC,c.candidate_id",
                CANDIDATE_MAPPER, equipmentId);
    }

    public List<Candidate> listCurrentReadyCandidates(String equipmentId, String definitionId) {
        return jdbc.query(CANDIDATE_SELECT + """
                 WHERE c.equip_id=? AND c.definition_id=? AND c.current_flag=1
                   AND c.validation_status='READY'
                 ORDER BY c.source_type,c.candidate_id
                """, CANDIDATE_MAPPER, equipmentId, definitionId);
    }

    public void obsoleteConflicts(String equipmentId, String definitionId) {
        jdbc.update("""
                UPDATE biz_device_parameter_conflict
                SET status='OBSOLETE',obsolete_at=CURRENT_TIMESTAMP
                WHERE equip_id=? AND definition_id=? AND status IN ('OPEN','RESOLVED')
                """, equipmentId, definitionId);
    }

    public void insertConflict(
            String conflictId, String buildingId, String equipmentId, String definitionId,
            String status, List<String> candidateIds) {
        jdbc.update("""
                INSERT INTO biz_device_parameter_conflict
                (conflict_id,building_id,equip_id,definition_id,status,config_revision,create_time)
                VALUES (?,?,?,?,?,0,CURRENT_TIMESTAMP)
                """, conflictId, buildingId, equipmentId, definitionId, status);
        jdbc.batchUpdate("""
                INSERT INTO biz_device_parameter_conflict_member (conflict_id,candidate_id) VALUES (?,?)
                """, candidateIds, candidateIds.size(), (ps, candidateId) -> {
            ps.setString(1, conflictId);
            ps.setString(2, candidateId);
        });
    }

    public Optional<ConflictHead> findCurrentConflict(
            String equipmentId, String definitionId, boolean lock) {
        String sql = """
                SELECT conflict_id,building_id,equip_id,definition_id,status,config_revision,
                       selected_candidate_id,resolution_reason
                FROM biz_device_parameter_conflict
                WHERE equip_id=? AND definition_id=? AND status IN ('OPEN','RESOLVED')
                ORDER BY create_time DESC LIMIT 1
                """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, CONFLICT_HEAD_MAPPER, equipmentId, definitionId).stream().findFirst();
    }

    public List<ConflictHead> listCurrentConflicts(String equipmentId) {
        return jdbc.query("""
                SELECT conflict_id,building_id,equip_id,definition_id,status,config_revision,
                       selected_candidate_id,resolution_reason
                FROM biz_device_parameter_conflict
                WHERE equip_id=? AND status IN ('OPEN','RESOLVED')
                ORDER BY create_time DESC
                """, CONFLICT_HEAD_MAPPER, equipmentId);
    }

    public Optional<ConflictHead> listCurrentConflictsForId(String conflictId) {
        return jdbc.query("""
                SELECT conflict_id,building_id,equip_id,definition_id,status,config_revision,
                       selected_candidate_id,resolution_reason
                FROM biz_device_parameter_conflict
                WHERE conflict_id=? AND status IN ('OPEN','RESOLVED')
                """, CONFLICT_HEAD_MAPPER, conflictId).stream().findFirst();
    }

    public List<String> listConflictMemberIds(String conflictId) {
        return jdbc.query("SELECT candidate_id FROM biz_device_parameter_conflict_member WHERE conflict_id=?",
                (rs, row) -> rs.getString(1), conflictId);
    }

    public int resolveConflict(
            String conflictId, String candidateId, String reason, int expectedRevision, long userId) {
        return jdbc.update("""
                UPDATE biz_device_parameter_conflict
                SET status='RESOLVED',selected_candidate_id=?,resolution_reason=?,resolved_by=?,
                    resolved_at=CURRENT_TIMESTAMP,config_revision=config_revision+1
                WHERE conflict_id=? AND status='OPEN' AND config_revision=?
                """, candidateId, reason, userId, conflictId, expectedRevision);
    }

    public Optional<ParameterSetHead> findParameterSet(String equipmentId, boolean lock) {
        String sql = """
                SELECT parameter_set_id,building_id,equip_id,current_timeline_revision_id,config_revision
                FROM biz_device_parameter_set WHERE equip_id=?
                """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, PARAMETER_SET_MAPPER, equipmentId).stream().findFirst();
    }

    public Optional<ParameterSetHead> findParameterSetById(String parameterSetId, boolean lock) {
        String sql = """
                SELECT parameter_set_id,building_id,equip_id,current_timeline_revision_id,config_revision
                FROM biz_device_parameter_set WHERE parameter_set_id=?
                """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, PARAMETER_SET_MAPPER, parameterSetId).stream().findFirst();
    }

    public void insertParameterSet(String setId, EquipmentIdentity equipment) {
        jdbc.update("""
                INSERT INTO biz_device_parameter_set
                (parameter_set_id,building_id,equip_id,config_revision,create_time,update_time)
                VALUES (?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, setId, equipment.buildingId(), equipment.equipmentId());
    }

    public int nextVersionNo(String parameterSetId) {
        Integer result = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no),0)+1 FROM biz_device_parameter_version
                WHERE parameter_set_id=?
                """, Integer.class, parameterSetId);
        return result == null ? 1 : result;
    }

    public Optional<ParameterVersion> findOpenVersion(String parameterSetId, boolean lock) {
        String sql = VERSION_SELECT + """
                 WHERE v.parameter_set_id=? AND v.open_marker=1
                """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, VERSION_MAPPER, parameterSetId).stream().findFirst()
                .map(this::withValues);
    }

    public Optional<ParameterVersion> findVersion(String versionId, boolean lock) {
        String sql = VERSION_SELECT + " WHERE v.version_id=?" + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, VERSION_MAPPER, versionId).stream().findFirst()
                .map(this::withValues);
    }

    public List<ParameterVersion> listVersions(String parameterSetId) {
        return jdbc.query(VERSION_SELECT + " WHERE v.parameter_set_id=? ORDER BY v.version_no DESC",
                VERSION_MAPPER, parameterSetId).stream().map(this::withValues).toList();
    }

    public void insertVersion(ParameterVersion value) {
        jdbc.update("""
                INSERT INTO biz_device_parameter_version
                (version_id,parameter_set_id,version_no,status,open_marker,config_revision,
                 submitted_revision,base_version_id,base_timeline_revision_id,copied_from_version_id,
                 change_type,requested_effective_from,requested_effective_to,change_reason,
                 evidence_reference,owner_user_id,rollback_initiator_id,create_time,update_time)
                VALUES (?,?,?,?,1,0,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, value.versionId(), value.parameterSetId(), value.versionNo(), value.status().name(),
                value.submittedRevision(), value.baseVersionId(), value.baseTimelineRevisionId(),
                value.copiedFromVersionId(), value.changeType().name(),
                timestamp(value.requestedEffectiveFrom()), timestamp(value.requestedEffectiveTo()),
                value.changeReason(), value.evidenceReference(), value.ownerUserId(),
                value.rollbackInitiatorId());
    }

    public int replaceDraftValues(String versionId, int expectedRevision, List<VersionValue> values) {
        int changed = jdbc.update("""
                UPDATE biz_device_parameter_version
                SET config_revision=config_revision+1,update_time=CURRENT_TIMESTAMP
                WHERE version_id=? AND status='DRAFT' AND config_revision=?
                """, versionId, expectedRevision);
        if (changed != 1) {
            return 0;
        }
        jdbc.update("DELETE FROM biz_device_parameter_version_value WHERE version_id=?", versionId);
        insertVersionValues(versionId, values);
        return 1;
    }

    public void insertVersionValues(String versionId, List<VersionValue> values) {
        jdbc.batchUpdate("""
                INSERT INTO biz_device_parameter_version_value
                (version_value_id,version_id,definition_id,value_status,normalized_value,standard_unit,
                 selected_candidate_id,source_type,source_reference,source_version,observed_at,
                 missing_reason,warning_reason)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, values, values.size(), (ps, value) -> {
            ps.setString(1, ids());
            ps.setString(2, versionId);
            ps.setString(3, value.definitionId());
            ps.setString(4, value.valueStatus());
            ps.setBigDecimal(5, value.value());
            ps.setString(6, value.standardUnit());
            ps.setString(7, value.candidateId());
            ps.setString(8, value.sourceType() == null ? null : value.sourceType().name());
            ps.setString(9, value.sourceReference());
            ps.setString(10, value.sourceVersion());
            ps.setTimestamp(11, timestamp(value.observedAt()));
            ps.setString(12, value.missingReason());
            ps.setString(13, value.warningReason());
        });
    }

    public int markVersionPending(String versionId, int expectedRevision) {
        return jdbc.update("""
                UPDATE biz_device_parameter_version
                SET status='PENDING_REVIEW',submitted_revision=config_revision,update_time=CURRENT_TIMESTAMP
                WHERE version_id=? AND status='DRAFT' AND config_revision=?
                """, versionId, expectedRevision);
    }

    public int markVersionPublished(String versionId, LocalDateTime publishedAt) {
        return jdbc.update("""
                UPDATE biz_device_parameter_version
                SET status='PUBLISHED',open_marker=NULL,published_at=?,update_time=CURRENT_TIMESTAMP
                WHERE version_id=? AND status='PENDING_REVIEW'
                """, timestamp(publishedAt), versionId);
    }

    public int markVersionRejected(String versionId) {
        return jdbc.update("""
                UPDATE biz_device_parameter_version
                SET status='REJECTED',open_marker=NULL,update_time=CURRENT_TIMESTAMP
                WHERE version_id=? AND status='PENDING_REVIEW'
                """, versionId);
    }

    public int nextReviewNo(String versionId) {
        Integer result = jdbc.queryForObject("""
                SELECT COALESCE(MAX(request_no),0)+1 FROM biz_device_parameter_review_request
                WHERE version_id=?
                """, Integer.class, versionId);
        return result == null ? 1 : result;
    }

    public void insertReview(ReviewHead value, String snapshotJson) {
        jdbc.update("""
                INSERT INTO biz_device_parameter_review_request
                (request_id,building_id,version_id,request_no,status,submitted_revision,
                 snapshot_json,snapshot_sha256,submitted_by,submitted_at,idempotency_key,request_sha256,
                 create_time,update_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, value.requestId(), value.buildingId(), value.versionId(), value.requestNo(),
                value.status(), value.submittedRevision(), snapshotJson, value.snapshotSha256(),
                value.submittedBy(), timestamp(value.submittedAt()), value.idempotencyKey(),
                value.requestSha256());
    }

    public Optional<ReviewHead> findReview(String requestId, boolean lock) {
        String sql = REVIEW_SELECT + " WHERE request_id=?" + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, REVIEW_MAPPER, requestId).stream().findFirst();
    }

    public Optional<ReviewHead> findReviewByIdempotency(String idempotencyKey) {
        return jdbc.query(REVIEW_SELECT + " WHERE idempotency_key=?", REVIEW_MAPPER,
                idempotencyKey).stream().findFirst();
    }

    public List<ReviewHead> listReviews(String buildingId) {
        return jdbc.query(REVIEW_SELECT + " WHERE building_id=? ORDER BY submitted_at DESC",
                REVIEW_MAPPER, buildingId);
    }

    public int approveReview(
            String requestId, long reviewerId, String comment, LocalDateTime at,
            String idempotencyKey, String requestHash) {
        return jdbc.update("""
                UPDATE biz_device_parameter_review_request
                SET status='APPROVED',reviewer_id=?,review_comment=?,reviewed_at=?,
                    decision_idempotency_key=?,decision_request_sha256=?,update_time=CURRENT_TIMESTAMP
                WHERE request_id=? AND status='PENDING'
                """, reviewerId, comment, timestamp(at), idempotencyKey, requestHash, requestId);
    }

    public int rejectReview(String requestId, long reviewerId, String comment, LocalDateTime at) {
        return jdbc.update("""
                UPDATE biz_device_parameter_review_request
                SET status='REJECTED',reviewer_id=?,review_comment=?,reviewed_at=?,update_time=CURRENT_TIMESTAMP
                WHERE request_id=? AND status='PENDING'
                """, reviewerId, comment, timestamp(at), requestId);
    }

    public int withdrawReview(String requestId, long userId, LocalDateTime at) {
        return jdbc.update("""
                UPDATE biz_device_parameter_review_request
                SET status='WITHDRAWN',withdrawn_by=?,withdrawn_at=?,update_time=CURRENT_TIMESTAMP
                WHERE request_id=? AND status='PENDING' AND submitted_by=?
                """, userId, timestamp(at), requestId, userId);
    }

    public int reopenVersionAfterWithdraw(String versionId, int submittedRevision) {
        return jdbc.update("""
                UPDATE biz_device_parameter_version
                SET status='DRAFT',submitted_revision=NULL,update_time=CURRENT_TIMESTAMP
                WHERE version_id=? AND status='PENDING_REVIEW' AND submitted_revision=?
                """, versionId, submittedRevision);
    }

    public Optional<TimelineRevision> findCurrentTimeline(String parameterSetId, boolean lock) {
        String sql = TIMELINE_SELECT + """
                 WHERE r.timeline_revision_id=(
                   SELECT current_timeline_revision_id FROM biz_device_parameter_set
                   WHERE parameter_set_id=?
                 )
                """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, TIMELINE_MAPPER, parameterSetId).stream().findFirst()
                .map(this::withSegments);
    }

    public Optional<TimelineRevision> findTimelineAt(
            String parameterSetId, LocalDateTime knowledgeAt) {
        return jdbc.query(TIMELINE_SELECT + """
                 WHERE r.parameter_set_id=? AND r.published_at<=?
                 ORDER BY r.published_at DESC,r.revision_no DESC LIMIT 1
                """, TIMELINE_MAPPER, parameterSetId, timestamp(knowledgeAt)).stream()
                .findFirst().map(this::withSegments);
    }

    public int nextTimelineRevisionNo(String parameterSetId) {
        Integer result = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision_no),0)+1 FROM biz_device_parameter_timeline_revision
                WHERE parameter_set_id=?
                """, Integer.class, parameterSetId);
        return result == null ? 1 : result;
    }

    public void insertTimeline(TimelineRevision revision, String impactJson) {
        jdbc.update("""
                INSERT INTO biz_device_parameter_timeline_revision
                (timeline_revision_id,parameter_set_id,revision_no,published_at,published_by,
                 review_request_id,publish_type,retroactive_reason,evidence_reference,
                 recalculation_status,impact_snapshot_json)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, revision.timelineRevisionId(), revision.parameterSetId(), revision.revisionNo(),
                timestamp(revision.publishedAt()), revision.publishedBy(), revision.reviewRequestId(),
                revision.publishType().name(), revision.retroactiveReason(), revision.evidenceReference(),
                revision.recalculationStatus().name(), impactJson);
        jdbc.batchUpdate("""
                INSERT INTO biz_device_parameter_timeline_segment
                (timeline_segment_id,timeline_revision_id,business_effective_from,
                 business_effective_to,version_id) VALUES (?,?,?,?,?)
                """, revision.segments(), revision.segments().size(), (ps, segment) -> {
            ps.setString(1, ids());
            ps.setString(2, revision.timelineRevisionId());
            ps.setTimestamp(3, timestamp(segment.businessEffectiveFrom()));
            ps.setTimestamp(4, timestamp(segment.businessEffectiveTo()));
            ps.setString(5, segment.versionId());
        });
    }

    public int updateCurrentTimeline(
            String parameterSetId, String timelineRevisionId, int expectedRevision) {
        return jdbc.update("""
                UPDATE biz_device_parameter_set
                SET current_timeline_revision_id=?,config_revision=config_revision+1,
                    update_time=CURRENT_TIMESTAMP
                WHERE parameter_set_id=? AND config_revision=?
                """, timelineRevisionId, parameterSetId, expectedRevision);
    }

    public void insertRecalculationJob(
            String jobId, String idempotencyKey, String timelineRevisionId, String buildingId,
            String equipmentId, String indicatorIdsJson, LocalDateTime from, LocalDateTime to) {
        jdbc.update("""
                INSERT INTO biz_device_parameter_recalc_job
                (job_id,idempotency_key,timeline_revision_id,building_id,equip_id,indicator_ids_json,
                 from_minute,to_minute,status,cursor_minute,retry_count,create_time,update_time)
                VALUES (?,?,?,?,?,?,?,?,'WAITING',?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, jobId, idempotencyKey, timelineRevisionId, buildingId, equipmentId,
                indicatorIdsJson, timestamp(from), timestamp(to), timestamp(from));
    }

    public Optional<ImportBatch> findImportBatchByHash(String buildingId, String fileSha256) {
        return jdbc.query(IMPORT_BATCH_SELECT + " WHERE building_id=? AND file_sha256=?",
                IMPORT_BATCH_MAPPER, buildingId, fileSha256).stream().findFirst();
    }

    public Optional<ImportBatch> findImportBatch(String batchId, boolean lock) {
        String sql = IMPORT_BATCH_SELECT + " WHERE import_batch_id=?" + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, IMPORT_BATCH_MAPPER, batchId).stream().findFirst();
    }

    public void insertImportBatch(ImportBatch batch) {
        jdbc.update("""
                INSERT INTO biz_device_parameter_import_batch
                (import_batch_id,building_id,safe_file_name,file_sha256,status,row_count,
                 valid_row_count,error_count,error_summary,operator_id,create_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                """, batch.batchId(), batch.buildingId(), batch.safeFileName(), batch.fileSha256(),
                batch.status(), batch.rowCount(), batch.validRowCount(), batch.errorCount(),
                batch.errorSummary(), batch.operatorId());
    }

    public void insertImportRows(String batchId, List<ImportRow> rows) {
        jdbc.batchUpdate("""
                INSERT INTO biz_device_parameter_import_row
                (import_row_id,import_batch_id,row_no,equipment_code,parameter_code,raw_value,
                 raw_unit,observed_at,source_reference,validation_status,error_field,error_code)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, rows, rows.size(), (ps, row) -> {
            ps.setString(1, ids());
            ps.setString(2, batchId);
            ps.setInt(3, row.rowNo());
            ps.setString(4, row.equipmentCode());
            ps.setString(5, row.parameterCode());
            ps.setString(6, row.rawValue());
            ps.setString(7, row.rawUnit());
            ps.setTimestamp(8, timestamp(row.observedAt()));
            ps.setString(9, row.sourceReference());
            ps.setString(10, row.status());
            ps.setString(11, row.errorField());
            ps.setString(12, row.errorCode());
        });
    }

    public List<ImportRow> listImportRows(String batchId) {
        return jdbc.query("""
                SELECT row_no,equipment_code,parameter_code,raw_value,raw_unit,observed_at,
                       source_reference,validation_status,error_field,error_code
                FROM biz_device_parameter_import_row WHERE import_batch_id=? ORDER BY row_no
                """, IMPORT_ROW_MAPPER, batchId);
    }

    public int markImportConfirmed(String batchId, long userId) {
        return jdbc.update("""
                UPDATE biz_device_parameter_import_batch
                SET status='IMPORTED',confirmed_by=?,confirmed_at=CURRENT_TIMESTAMP
                WHERE import_batch_id=? AND status='VALIDATED'
                """, userId, batchId);
    }

    public Optional<RecalculationJob> findRecalculationJob(String jobId) {
        return jdbc.query(RECALC_SELECT + " WHERE job_id=?", RECALC_MAPPER, jobId)
                .stream().findFirst();
    }

    public List<RecalculationJob> listRecalculationJobs(
            String buildingId, String status, int offset, int limit) {
        if (status == null) {
            return jdbc.query(RECALC_SELECT + """
                     WHERE building_id=? ORDER BY update_time DESC,job_id DESC LIMIT ? OFFSET ?
                    """, RECALC_MAPPER, buildingId, limit, offset);
        }
        return jdbc.query(RECALC_SELECT + """
                 WHERE building_id=? AND status=?
                 ORDER BY update_time DESC,job_id DESC LIMIT ? OFFSET ?
                """, RECALC_MAPPER, buildingId, status, limit, offset);
    }

    public long countRecalculationJobs(String buildingId, String status) {
        String sql = "SELECT COUNT(*) FROM biz_device_parameter_recalc_job WHERE building_id=?"
                + (status == null ? "" : " AND status=?");
        Long count = status == null
                ? jdbc.queryForObject(sql, Long.class, buildingId)
                : jdbc.queryForObject(sql, Long.class, buildingId, status);
        return count == null ? 0 : count;
    }

    public List<RecalculationJob> listClaimableRecalculationJobs(int limit) {
        return jdbc.query(RECALC_SELECT + """
                 WHERE status IN ('WAITING','FAILED') ORDER BY update_time,job_id LIMIT ?
                """, RECALC_MAPPER, limit);
    }

    public int claimRecalculationJob(String jobId) {
        return jdbc.update("""
                UPDATE biz_device_parameter_recalc_job
                SET status='RUNNING',update_time=CURRENT_TIMESTAMP
                WHERE job_id=? AND status IN ('WAITING','FAILED')
                """, jobId);
    }

    public int advanceRecalculationJob(
            String jobId, LocalDateTime expectedCursor, LocalDateTime nextCursor, boolean complete) {
        return jdbc.update("""
                UPDATE biz_device_parameter_recalc_job
                SET cursor_minute=?,status=?,finished_at=?,last_error=NULL,update_time=CURRENT_TIMESTAMP
                WHERE job_id=? AND status='RUNNING' AND cursor_minute=?
                """, timestamp(nextCursor), complete ? "SUCCEEDED" : "RUNNING",
                complete ? Timestamp.valueOf(LocalDateTime.now()) : null, jobId,
                timestamp(expectedCursor));
    }

    public void failRecalculationJob(String jobId, String safeError) {
        jdbc.update("""
                UPDATE biz_device_parameter_recalc_job
                SET status='FAILED',retry_count=retry_count+1,last_error=?,update_time=CURRENT_TIMESTAMP
                WHERE job_id=? AND status='RUNNING'
                """, safeError, jobId);
    }

    public int resumeFailedRecalculationJob(String jobId) {
        return jdbc.update("""
                UPDATE biz_device_parameter_recalc_job
                SET status='WAITING',last_error=NULL,update_time=CURRENT_TIMESTAMP
                WHERE job_id=? AND status='FAILED'
                """, jobId);
    }

    public void updateTimelineRecalculationStatus(
            String timelineRevisionId, RecalculationStatus status) {
        jdbc.update("""
                UPDATE biz_device_parameter_timeline_revision SET recalculation_status=?
                WHERE timeline_revision_id=?
                """, status.name(), timelineRevisionId);
    }

    public void audit(
            String auditId, String buildingId, String actorType, Long operatorId,
            String actionType, String objectType, String objectId, String versionId,
            String beforeSummary, String afterSummary, String result, String reasonCode,
            String idempotencyKey, String requestSha256) {
        jdbc.update("""
                INSERT INTO biz_device_parameter_audit_log
                (audit_id,building_id,actor_type,operator_id,action_type,object_type,object_id,
                 version_id,review_request_id,before_summary,after_summary,result,reason_code,trace_id,
                 environment_mode,self_approval_dev_mode,idempotency_key,request_sha256,operation_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                """, auditId, buildingId, actorType, operatorId, actionType, objectType, objectId,
                versionId, "REVIEW_REQUEST".equals(objectType) ? objectId : null,
                beforeSummary, afterSummary, result, reasonCode, TraceContext.current(),
                auditProperties.getEnvironmentMode().name(), "SELF_APPROVAL_DEV_MODE".equals(actionType),
                idempotencyKey, requestSha256);
    }

    public boolean auditExistsByIdempotency(String idempotencyKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_device_parameter_audit_log WHERE idempotency_key=?",
                Integer.class, idempotencyKey);
        return count != null && count > 0;
    }

    public List<AuditEntry> listAudits(String buildingId, int offset, int limit) {
        String scope = buildingId == null ? "building_id IS NULL" : "building_id=?";
        String sql = AUDIT_SELECT + " WHERE " + scope
                + " ORDER BY operation_time DESC,audit_id DESC LIMIT ? OFFSET ?";
        return buildingId == null
                ? jdbc.query(sql, AUDIT_MAPPER, limit, offset)
                : jdbc.query(sql, AUDIT_MAPPER, buildingId, limit, offset);
    }

    public long countAudits(String buildingId) {
        String sql = "SELECT COUNT(*) FROM biz_device_parameter_audit_log WHERE "
                + (buildingId == null ? "building_id IS NULL" : "building_id=?");
        Long count = buildingId == null ? jdbc.queryForObject(sql, Long.class)
                : jdbc.queryForObject(sql, Long.class, buildingId);
        return count == null ? 0 : count;
    }

    public void updateLegacyProjection(
            String equipmentId, BigDecimal ratedCapacity, BigDecimal ratedPower, BigDecimal designCop) {
        jdbc.update("""
                UPDATE biz_equipment SET rated_capacity=?,rated_power=?,design_cop=?,update_time=CURRENT_TIMESTAMP
                WHERE equip_id=?
                """, ratedCapacity, ratedPower, designCop, equipmentId);
    }

    public void stageLegacyValue(
            String stagingId, String batchId, EquipmentIdentity equipment,
            String field, BigDecimal rawValue) {
        jdbc.update("""
                INSERT INTO biz_device_parameter_legacy_staging
                (staging_id,migration_batch_id,building_id,equip_id,legacy_field,raw_value,
                 mapping_status,captured_at)
                VALUES (?,?,?,?,?,?,'PENDING_MAPPING',CURRENT_TIMESTAMP)
                """, stagingId, batchId, equipment.buildingId(), equipment.equipmentId(), field, rawValue);
    }

    public List<LegacyStagedValue> listLegacyStaging(String batchId) {
        return jdbc.query("""
                SELECT s.staging_id,s.migration_batch_id,s.legacy_field,s.raw_value,
                       s.mapping_status,s.definition_id,s.candidate_id,s.version_id,
                       e.equip_id,e.equip_code,e.type_code,e.building_id,e.product_id
                FROM biz_device_parameter_legacy_staging s
                JOIN biz_equipment e ON e.equip_id=s.equip_id AND e.del_flag=0
                WHERE s.migration_batch_id=?
                ORDER BY e.equip_id,s.legacy_field
                """, (rs, row) -> new LegacyStagedValue(
                rs.getString("staging_id"), rs.getString("migration_batch_id"),
                new EquipmentIdentity(rs.getString("equip_id"), rs.getString("equip_code"),
                        rs.getString("type_code"), rs.getString("building_id"),
                        rs.getString("product_id")), rs.getString("legacy_field"),
                rs.getBigDecimal("raw_value"), rs.getString("mapping_status"),
                rs.getString("definition_id"), rs.getString("candidate_id"),
                rs.getString("version_id")), batchId);
    }

    public void markLegacyStagingCandidate(
            String stagingId, LegacyMapping mapping, String candidateId, String status) {
        jdbc.update("""
                UPDATE biz_device_parameter_legacy_staging
                SET raw_unit_evidence=?,mapping_status=?,definition_id=?,candidate_id=?,
                    evidence_reference=?
                WHERE staging_id=?
                """, mapping.sourceUnit(), status, mapping.definitionId(), candidateId,
                mapping.evidenceReference(), stagingId);
    }

    public void markLegacyStagingNotApplicable(String stagingId, LegacyMapping mapping) {
        jdbc.update("""
                UPDATE biz_device_parameter_legacy_staging
                SET mapping_status='MIGRATED',evidence_reference=? WHERE staging_id=?
                """, mapping.evidenceReference(), stagingId);
    }

    public void markLegacyStagingVersion(String batchId, String equipmentId, String versionId) {
        jdbc.update("""
                UPDATE biz_device_parameter_legacy_staging
                SET version_id=?,mapping_status=CASE
                    WHEN candidate_id IS NULL THEN mapping_status ELSE 'READY_FOR_REVIEW' END
                WHERE migration_batch_id=? AND equip_id=?
                """, versionId, batchId, equipmentId);
    }

    public Optional<LegacyMapping> findLegacyMapping(
            String equipmentTypeCode, String legacyField) {
        return jdbc.query(LEGACY_MAPPING_SELECT
                        + " WHERE equipment_type_code=? AND legacy_field=?",
                LEGACY_MAPPING_MAPPER, equipmentTypeCode, legacyField).stream().findFirst();
    }

    public List<LegacyMapping> listEnabledLegacyMappings(String equipmentTypeCode) {
        return jdbc.query(LEGACY_MAPPING_SELECT
                        + " WHERE equipment_type_code=? AND status='ENABLED' ORDER BY legacy_field",
                LEGACY_MAPPING_MAPPER, equipmentTypeCode);
    }

    public void insertLegacyMapping(LegacyMapping value, long userId) {
        jdbc.update("""
                INSERT INTO biz_device_parameter_legacy_mapping
                (legacy_mapping_id,equipment_type_code,legacy_field,mapping_mode,definition_id,
                 source_unit,scale_value,offset_value,evidence_reference,status,config_revision,
                 create_by,update_by,create_time,update_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, value.mappingId(), value.equipmentTypeCode(), value.legacyField(),
                value.mappingMode(), value.definitionId(), value.sourceUnit(), value.scale(),
                value.offset(), value.evidenceReference(), value.status(), userId, userId);
    }

    public int updateLegacyMapping(LegacyMapping value, int expectedRevision, long userId) {
        return jdbc.update("""
                UPDATE biz_device_parameter_legacy_mapping
                SET mapping_mode=?,definition_id=?,source_unit=?,scale_value=?,offset_value=?,
                    evidence_reference=?,status=?,
                    config_revision=config_revision+1,update_by=?,update_time=CURRENT_TIMESTAMP
                WHERE legacy_mapping_id=? AND config_revision=?
                """, value.mappingMode(), value.definitionId(), value.sourceUnit(), value.scale(),
                value.offset(), value.evidenceReference(), value.status(), userId,
                value.mappingId(), expectedRevision);
    }

    public List<LegacyValue> listUnstagedLegacyValues(String batchId) {
        return jdbc.query("""
                SELECT e.equip_id,e.equip_code,e.type_code,e.building_id,e.product_id,
                       e.rated_capacity,e.rated_power,e.design_cop
                FROM biz_equipment e WHERE e.del_flag=0 AND (
                  e.rated_capacity IS NOT NULL OR e.rated_power IS NOT NULL OR e.design_cop IS NOT NULL)
                  AND NOT EXISTS (
                    SELECT 1 FROM biz_device_parameter_legacy_staging s
                    WHERE s.migration_batch_id=? AND s.equip_id=e.equip_id)
                """, (rs, row) -> new LegacyValue(
                new EquipmentIdentity(rs.getString("equip_id"), rs.getString("equip_code"),
                        rs.getString("type_code"), rs.getString("building_id"),
                        rs.getString("product_id")),
                rs.getBigDecimal("rated_capacity"), rs.getBigDecimal("rated_power"),
                rs.getBigDecimal("design_cop")), batchId);
    }

    public long countLegacyStaging(String batchId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_device_parameter_legacy_staging WHERE migration_batch_id=?
                """, Long.class, batchId);
        return count == null ? 0 : count;
    }

    private ParameterVersion withValues(ParameterVersion version) {
        List<VersionValue> values = jdbc.query("""
                SELECT vv.definition_id,d.parameter_code,vv.value_status,vv.normalized_value,
                       vv.standard_unit,vv.selected_candidate_id,vv.source_type,vv.source_reference,
                       vv.source_version,vv.observed_at,vv.missing_reason,vv.warning_reason
                FROM biz_device_parameter_version_value vv
                JOIN biz_device_parameter_definition d ON d.definition_id=vv.definition_id
                WHERE vv.version_id=? ORDER BY d.parameter_code
                """, VERSION_VALUE_MAPPER, version.versionId());
        return new ParameterVersion(version.versionId(), version.parameterSetId(), version.versionNo(),
                version.status(), version.configRevision(), version.submittedRevision(),
                version.baseVersionId(), version.baseTimelineRevisionId(), version.copiedFromVersionId(),
                version.changeType(), version.requestedEffectiveFrom(), version.requestedEffectiveTo(),
                version.changeReason(), version.evidenceReference(), version.ownerUserId(),
                version.rollbackInitiatorId(), version.publishedAt(), values);
    }

    private TimelineRevision withSegments(TimelineRevision revision) {
        List<TimelineSegment> segments = jdbc.query("""
                SELECT business_effective_from,business_effective_to,version_id
                FROM biz_device_parameter_timeline_segment
                WHERE timeline_revision_id=? ORDER BY business_effective_from
                """, (rs, row) -> new TimelineSegment(
                local(rs, "business_effective_from"), local(rs, "business_effective_to"),
                rs.getString("version_id")), revision.timelineRevisionId());
        return new TimelineRevision(revision.timelineRevisionId(), revision.parameterSetId(),
                revision.revisionNo(), revision.publishedAt(), revision.publishedBy(),
                revision.reviewRequestId(), revision.publishType(), revision.retroactiveReason(),
                revision.evidenceReference(), revision.recalculationStatus(), segments);
    }

    public record MappingHead(
            String mappingId, String profileCode, String sourcePath,
            String currentActiveVersionId, int configRevision) {
    }

    public record ProductIdentity(String productId, String equipmentTypeCode, String status) {
    }

    public record TemplateHead(
            String templateId, String productId, String currentActiveRevisionId, int configRevision) {
    }

    public record TemplateRevision(
            String revisionId, String templateId, String productId, int revisionNo,
            String status, String changeReason, String evidenceReference, long createdBy) {
    }

    public record TemplateValue(
            String definitionId, BigDecimal rawValue, String rawUnit,
            BigDecimal normalizedValue, String sourceReference, int sortOrder) {
    }

    public record MappingVersion(
            String mappingVersionId, String mappingId, int versionNo, String status,
            String profileCode, String profileVersion, String sourcePath, String definitionId,
            String sourceUnit, BigDecimal scale, BigDecimal offset, boolean required,
            String baseVersionId, String copiedFromVersionId, String changeReason,
            String evidenceReference, Long createdBy) {
    }

    public record ConflictHead(
            String conflictId, String buildingId, String equipmentId, String definitionId,
            String status, int configRevision, String selectedCandidateId, String resolutionReason) {
    }

    public record ParameterSetHead(
            String parameterSetId, String buildingId, String equipmentId,
            String currentTimelineRevisionId, int configRevision) {
    }

    public record ReviewHead(
            String requestId, String buildingId, String versionId, int requestNo,
            String status, int submittedRevision, String snapshotSha256,
            long submittedBy, LocalDateTime submittedAt, Long reviewerId,
            String reviewComment, LocalDateTime reviewedAt, String idempotencyKey,
            String requestSha256, String decisionIdempotencyKey, String decisionRequestSha256) {
    }

    public record RecalculationJob(
            String jobId, String timelineRevisionId, String buildingId, String equipmentId,
            String indicatorIdsJson, LocalDateTime fromMinute, LocalDateTime toMinute,
            String status, LocalDateTime cursorMinute, int retryCount, String lastError) {
    }

    public record AuditEntry(
            String auditId, String buildingId, String actorType, String actionType,
            String objectType, String objectId, String versionId, String result,
            String reasonCode, LocalDateTime operationTime) {
    }

    public record LegacyValue(
            EquipmentIdentity equipment, BigDecimal ratedCapacity,
            BigDecimal ratedPower, BigDecimal designCop) {
    }

    public record ImportBatch(
            String batchId, String buildingId, String safeFileName, String fileSha256,
            String status, int rowCount, int validRowCount, int errorCount,
            String errorSummary, long operatorId, Long confirmedBy,
            LocalDateTime createTime, LocalDateTime confirmedAt) {
    }

    public record ImportRow(
            int rowNo, String equipmentCode, String parameterCode, String rawValue,
            String rawUnit, LocalDateTime observedAt, String sourceReference,
            String status, String errorField, String errorCode) {
    }

    public record LegacyMapping(
            String mappingId, String equipmentTypeCode, String legacyField,
            String mappingMode, String definitionId, String sourceUnit,
            BigDecimal scale, BigDecimal offset, String evidenceReference,
            String status, int configRevision) {
    }

    public record LegacyStagedValue(
            String stagingId, String migrationBatchId, EquipmentIdentity equipment,
            String legacyField, BigDecimal rawValue, String mappingStatus,
            String definitionId, String candidateId, String versionId) {
    }

    private static final String DEFINITION_SELECT = """
            SELECT definition_id,parameter_code,parameter_name,business_definition,quantity_kind,
                   standard_unit,storage_scale,display_scale,evidence_reference,status,config_revision
            FROM biz_device_parameter_definition
            """;
    private static final String APPLICABILITY_SELECT = """
            SELECT applicability_id,equipment_type_code,definition_id,required_flag,formula_readable,
                   hard_min,hard_max,warning_min,warning_max,comparison_tolerance,
                   evidence_reference,status,config_revision
            FROM biz_device_parameter_applicability
            """;
    private static final String MAPPING_VERSION_SELECT = """
            SELECT v.mapping_version_id,v.mapping_id,v.version_no,v.status,m.profile_code,
                   v.profile_version,m.source_path,v.definition_id,v.source_unit,v.scale_value,
                   v.offset_value,v.required_flag,v.base_version_id,v.copied_from_version_id,
                   v.change_reason,v.evidence_reference,v.created_by
            FROM biz_device_parameter_mapping_version v
            JOIN biz_device_parameter_mapping m ON m.mapping_id=v.mapping_id
            """;
    private static final String CANDIDATE_SELECT = """
            SELECT c.candidate_id,c.building_id,c.equip_id,c.definition_id,d.parameter_code,
                   c.source_type,c.source_reference,c.source_version,c.source_parameter_key,
                   c.raw_value,c.raw_unit,c.normalized_value,c.standard_unit,c.mapping_version_id,
                   c.observed_at,c.validation_status,c.validation_reason,c.warning_flag,c.current_flag,
                   c.payload_hash,c.first_seen_at,c.last_seen_at
            FROM biz_device_parameter_candidate c
            LEFT JOIN biz_device_parameter_definition d ON d.definition_id=c.definition_id
            """;
    private static final String VERSION_SELECT = """
            SELECT v.version_id,v.parameter_set_id,v.version_no,v.status,v.config_revision,
                   v.submitted_revision,v.base_version_id,v.base_timeline_revision_id,
                   v.copied_from_version_id,v.change_type,v.requested_effective_from,
                   v.requested_effective_to,v.change_reason,v.evidence_reference,v.owner_user_id,
                   v.rollback_initiator_id,v.published_at
            FROM biz_device_parameter_version v
            """;
    private static final String REVIEW_SELECT = """
            SELECT request_id,building_id,version_id,request_no,status,submitted_revision,
                   snapshot_sha256,submitted_by,submitted_at,reviewer_id,review_comment,
                   reviewed_at,idempotency_key,request_sha256,
                   decision_idempotency_key,decision_request_sha256
            FROM biz_device_parameter_review_request
            """;
    private static final String TIMELINE_SELECT = """
            SELECT r.timeline_revision_id,r.parameter_set_id,r.revision_no,r.published_at,
                   r.published_by,r.review_request_id,r.publish_type,r.retroactive_reason,
                   r.evidence_reference,r.recalculation_status
            FROM biz_device_parameter_timeline_revision r
            """;
    private static final String RECALC_SELECT = """
            SELECT job_id,timeline_revision_id,building_id,equip_id,indicator_ids_json,
                   from_minute,to_minute,status,cursor_minute,retry_count,last_error
            FROM biz_device_parameter_recalc_job
            """;
    private static final String AUDIT_SELECT = """
            SELECT audit_id,building_id,actor_type,action_type,object_type,object_id,
                   version_id,result,reason_code,operation_time
            FROM biz_device_parameter_audit_log
            """;
    private static final String IMPORT_BATCH_SELECT = """
            SELECT import_batch_id,building_id,safe_file_name,file_sha256,status,row_count,
                   valid_row_count,error_count,error_summary,operator_id,confirmed_by,
                   create_time,confirmed_at
            FROM biz_device_parameter_import_batch
            """;
    private static final String LEGACY_MAPPING_SELECT = """
            SELECT legacy_mapping_id,equipment_type_code,legacy_field,mapping_mode,definition_id,
                   source_unit,scale_value,offset_value,evidence_reference,status,config_revision
            FROM biz_device_parameter_legacy_mapping
            """;

    private static final RowMapper<EquipmentIdentity> EQUIPMENT_MAPPER = (rs, row) ->
            new EquipmentIdentity(rs.getString("equip_id"), rs.getString("equip_code"),
                    rs.getString("type_code"), rs.getString("building_id"),
                    rs.getString("product_id"));
    private static final RowMapper<Definition> DEFINITION_MAPPER = (rs, row) ->
            new Definition(rs.getString("definition_id"), rs.getString("parameter_code"),
                    rs.getString("parameter_name"), rs.getString("business_definition"),
                    rs.getString("quantity_kind"), rs.getString("standard_unit"),
                    rs.getInt("storage_scale"), rs.getInt("display_scale"),
                    rs.getString("evidence_reference"), rs.getString("status"),
                    rs.getInt("config_revision"));
    private static final RowMapper<Applicability> APPLICABILITY_MAPPER = (rs, row) ->
            new Applicability(rs.getString("applicability_id"), rs.getString("equipment_type_code"),
                    rs.getString("definition_id"), rs.getInt("required_flag") == 1,
                    rs.getInt("formula_readable") == 1, rs.getBigDecimal("hard_min"),
                    rs.getBigDecimal("hard_max"), rs.getBigDecimal("warning_min"),
                    rs.getBigDecimal("warning_max"), rs.getBigDecimal("comparison_tolerance"),
                    rs.getString("evidence_reference"), rs.getString("status"),
                    rs.getInt("config_revision"));
    private static final RowMapper<MappingHead> MAPPING_HEAD_MAPPER = (rs, row) ->
            new MappingHead(rs.getString("mapping_id"), rs.getString("profile_code"),
                    rs.getString("source_path"), rs.getString("current_active_version_id"),
                    rs.getInt("config_revision"));
    private static final RowMapper<MappingVersion> MAPPING_VERSION_MAPPER = (rs, row) ->
            new MappingVersion(rs.getString("mapping_version_id"), rs.getString("mapping_id"),
                    rs.getInt("version_no"), rs.getString("status"), rs.getString("profile_code"),
                    rs.getString("profile_version"), rs.getString("source_path"),
                    rs.getString("definition_id"), rs.getString("source_unit"),
                    rs.getBigDecimal("scale_value"), rs.getBigDecimal("offset_value"),
                    rs.getInt("required_flag") == 1, rs.getString("base_version_id"),
                    rs.getString("copied_from_version_id"), rs.getString("change_reason"),
                    rs.getString("evidence_reference"), nullableLong(rs, "created_by"));
    private static final RowMapper<TemplateRevision> TEMPLATE_REVISION_MAPPER = (rs, row) ->
            new TemplateRevision(rs.getString("template_revision_id"), rs.getString("template_id"),
                    rs.getString("product_id"), rs.getInt("revision_no"), rs.getString("status"),
                    rs.getString("change_reason"), rs.getString("evidence_reference"),
                    rs.getLong("created_by"));
    private static final RowMapper<TemplateValue> TEMPLATE_VALUE_MAPPER = (rs, row) ->
            new TemplateValue(rs.getString("definition_id"), rs.getBigDecimal("raw_value"),
                    rs.getString("raw_unit"), rs.getBigDecimal("normalized_value"),
                    rs.getString("source_reference"), rs.getInt("sort_order"));
    private static final RowMapper<Candidate> CANDIDATE_MAPPER = (rs, row) ->
            new Candidate(rs.getString("candidate_id"), rs.getString("building_id"),
                    rs.getString("equip_id"), rs.getString("definition_id"),
                    rs.getString("parameter_code"), SourceType.valueOf(rs.getString("source_type")),
                    rs.getString("source_reference"), rs.getString("source_version"),
                    rs.getString("source_parameter_key"), rs.getString("raw_value"),
                    rs.getString("raw_unit"), rs.getBigDecimal("normalized_value"),
                    rs.getString("standard_unit"), rs.getString("mapping_version_id"),
                    local(rs, "observed_at"), ValidationStatus.valueOf(rs.getString("validation_status")),
                    rs.getString("validation_reason"), rs.getInt("warning_flag") == 1,
                    rs.getInt("current_flag") == 1, rs.getString("payload_hash"),
                    local(rs, "first_seen_at"), local(rs, "last_seen_at"));
    private static final RowMapper<ConflictHead> CONFLICT_HEAD_MAPPER = (rs, row) ->
            new ConflictHead(rs.getString("conflict_id"), rs.getString("building_id"),
                    rs.getString("equip_id"), rs.getString("definition_id"), rs.getString("status"),
                    rs.getInt("config_revision"), rs.getString("selected_candidate_id"),
                    rs.getString("resolution_reason"));
    private static final RowMapper<ParameterSetHead> PARAMETER_SET_MAPPER = (rs, row) ->
            new ParameterSetHead(rs.getString("parameter_set_id"), rs.getString("building_id"),
                    rs.getString("equip_id"), rs.getString("current_timeline_revision_id"),
                    rs.getInt("config_revision"));
    private static final RowMapper<ParameterVersion> VERSION_MAPPER = (rs, row) ->
            new ParameterVersion(rs.getString("version_id"), rs.getString("parameter_set_id"),
                    rs.getInt("version_no"), VersionStatus.valueOf(rs.getString("status")),
                    rs.getInt("config_revision"), nullableInteger(rs, "submitted_revision"),
                    rs.getString("base_version_id"), rs.getString("base_timeline_revision_id"),
                    rs.getString("copied_from_version_id"), ChangeType.valueOf(rs.getString("change_type")),
                    local(rs, "requested_effective_from"), local(rs, "requested_effective_to"),
                    rs.getString("change_reason"), rs.getString("evidence_reference"),
                    nullableLong(rs, "owner_user_id"), nullableLong(rs, "rollback_initiator_id"),
                    local(rs, "published_at"), List.of());
    private static final RowMapper<VersionValue> VERSION_VALUE_MAPPER = (rs, row) ->
            new VersionValue(rs.getString("definition_id"), rs.getString("parameter_code"),
                    rs.getString("value_status"), rs.getBigDecimal("normalized_value"),
                    rs.getString("standard_unit"), rs.getString("selected_candidate_id"),
                    rs.getString("source_type") == null ? null : SourceType.valueOf(rs.getString("source_type")),
                    rs.getString("source_reference"), rs.getString("source_version"),
                    local(rs, "observed_at"), rs.getString("missing_reason"),
                    rs.getString("warning_reason"));
    private static final RowMapper<ReviewHead> REVIEW_MAPPER = (rs, row) ->
            new ReviewHead(rs.getString("request_id"), rs.getString("building_id"),
                    rs.getString("version_id"), rs.getInt("request_no"), rs.getString("status"),
                    rs.getInt("submitted_revision"), rs.getString("snapshot_sha256"),
                    rs.getLong("submitted_by"), local(rs, "submitted_at"),
                    nullableLong(rs, "reviewer_id"), rs.getString("review_comment"),
                    local(rs, "reviewed_at"), rs.getString("idempotency_key"),
                    rs.getString("request_sha256"), rs.getString("decision_idempotency_key"),
                    rs.getString("decision_request_sha256"));
    private static final RowMapper<TimelineRevision> TIMELINE_MAPPER = (rs, row) ->
            new TimelineRevision(rs.getString("timeline_revision_id"),
                    rs.getString("parameter_set_id"), rs.getInt("revision_no"),
                    local(rs, "published_at"), nullableLong(rs, "published_by"),
                    rs.getString("review_request_id"), PublishType.valueOf(rs.getString("publish_type")),
                    rs.getString("retroactive_reason"), rs.getString("evidence_reference"),
                    RecalculationStatus.valueOf(rs.getString("recalculation_status")), List.of());
    private static final RowMapper<RecalculationJob> RECALC_MAPPER = (rs, row) ->
            new RecalculationJob(rs.getString("job_id"), rs.getString("timeline_revision_id"),
                    rs.getString("building_id"), rs.getString("equip_id"),
                    rs.getString("indicator_ids_json"), local(rs, "from_minute"),
                    local(rs, "to_minute"), rs.getString("status"),
                    local(rs, "cursor_minute"), rs.getInt("retry_count"),
                    rs.getString("last_error"));
    private static final RowMapper<AuditEntry> AUDIT_MAPPER = (rs, row) ->
            new AuditEntry(rs.getString("audit_id"), rs.getString("building_id"),
                    rs.getString("actor_type"), rs.getString("action_type"),
                    rs.getString("object_type"), rs.getString("object_id"),
                    rs.getString("version_id"), rs.getString("result"),
                    rs.getString("reason_code"), local(rs, "operation_time"));
    private static final RowMapper<ImportBatch> IMPORT_BATCH_MAPPER = (rs, row) ->
            new ImportBatch(rs.getString("import_batch_id"), rs.getString("building_id"),
                    rs.getString("safe_file_name"), rs.getString("file_sha256"),
                    rs.getString("status"), rs.getInt("row_count"),
                    rs.getInt("valid_row_count"), rs.getInt("error_count"),
                    rs.getString("error_summary"), rs.getLong("operator_id"),
                    nullableLong(rs, "confirmed_by"), local(rs, "create_time"),
                    local(rs, "confirmed_at"));
    private static final RowMapper<ImportRow> IMPORT_ROW_MAPPER = (rs, row) ->
            new ImportRow(rs.getInt("row_no"), rs.getString("equipment_code"),
                    rs.getString("parameter_code"), rs.getString("raw_value"),
                    rs.getString("raw_unit"), local(rs, "observed_at"),
                    rs.getString("source_reference"), rs.getString("validation_status"),
                    rs.getString("error_field"), rs.getString("error_code"));
    private static final RowMapper<LegacyMapping> LEGACY_MAPPING_MAPPER = (rs, row) ->
            new LegacyMapping(rs.getString("legacy_mapping_id"),
                    rs.getString("equipment_type_code"), rs.getString("legacy_field"),
                    rs.getString("mapping_mode"), rs.getString("definition_id"),
                    rs.getString("source_unit"), rs.getBigDecimal("scale_value"),
                    rs.getBigDecimal("offset_value"), rs.getString("evidence_reference"),
                    rs.getString("status"), rs.getInt("config_revision"));

    private static int flag(boolean value) {
        return value ? 1 : 0;
    }

    private static String ids() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime local(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
