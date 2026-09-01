package com.platform.energy.catalog;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
/** 能源字典只通过本仓储访问其版本表，不向聚合等下游暴露内部表结构。 */
class EnergyCatalogRepository {
    private final JdbcTemplate jdbc;

    public String findItemIdForUpdate(String itemCode) {
        return optionalString("SELECT item_id FROM biz_energy_item WHERE item_code=? FOR UPDATE", itemCode);
    }

    public void insertItem(String itemId, String itemCode, long userId, LocalDateTime now) {
        jdbc.update("INSERT INTO biz_energy_item(item_id,item_code,created_by,created_at) VALUES (?,?,?,?)",
                itemId, itemCode, userId, timestamp(now));
    }

    public int nextItemVersion(String itemId) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM biz_energy_item_version WHERE item_id=?",
                Integer.class, itemId);
    }

    public void insertItemVersion(ItemVersionRow value, List<String> scopes) {
        jdbc.update("""
                INSERT INTO biz_energy_item_version
                (version_id,item_id,version_no,item_name,compatible_category,status,source_type,
                 source_reference,effective_from,effective_to,config_revision,created_by,created_at)
                VALUES (?,?,?,?,?,'PENDING_EXPERT',?,?,?,?,0,?,?)
                """, value.versionId(), value.itemId(), value.versionNo(), value.itemName(),
                value.compatibleCategory(), value.sourceType(), value.sourceReference(),
                timestamp(value.effectiveFrom()), timestamp(value.effectiveTo()), value.createdBy(),
                timestamp(value.createdAt()));
        for (String scope : scopes) {
            jdbc.update("INSERT INTO biz_energy_item_version_scope(version_id,usage_scope) VALUES (?,?)",
                    value.versionId(), scope);
        }
    }

    public List<ItemVersionRow> listItems() {
        return jdbc.query("""
                SELECT i.item_id,i.item_code,v.* FROM biz_energy_item i
                JOIN biz_energy_item_version v ON v.item_id=i.item_id
                ORDER BY i.item_code,v.version_no DESC
                """, EnergyCatalogRepository::itemRow);
    }

    public ItemVersionRow findItemVersion(String versionId) {
        return optional("""
                SELECT i.item_id,i.item_code,v.* FROM biz_energy_item i
                JOIN biz_energy_item_version v ON v.item_id=i.item_id WHERE v.version_id=?
                """, EnergyCatalogRepository::itemRow, versionId);
    }

    public ItemVersionRow findApprovedItem(String itemCode, LocalDateTime at) {
        return optional("""
                SELECT i.item_id,i.item_code,v.* FROM biz_energy_item i
                JOIN biz_energy_item_version v ON v.item_id=i.item_id
                WHERE i.item_code=? AND v.status='APPROVED' AND v.effective_from<=?
                  AND (v.effective_to IS NULL OR v.effective_to>?)
                ORDER BY v.version_no DESC LIMIT 1
                """, EnergyCatalogRepository::itemRow, itemCode, timestamp(at), timestamp(at));
    }

    public ItemVersionRow findLatestApprovedItem(String itemId) {
        return optional("""
                SELECT i.item_id,i.item_code,v.* FROM biz_energy_item i
                JOIN biz_energy_item_version v ON v.item_id=i.item_id
                WHERE i.item_id=? AND v.status='APPROVED'
                ORDER BY v.version_no DESC LIMIT 1
                """, EnergyCatalogRepository::itemRow, itemId);
    }

    public void closeItemVersion(String versionId, LocalDateTime effectiveTo) {
        jdbc.update("UPDATE biz_energy_item_version SET effective_to=? WHERE version_id=?",
                timestamp(effectiveTo), versionId);
    }

    public List<String> itemScopes(String versionId) {
        return jdbc.queryForList("""
                SELECT usage_scope FROM biz_energy_item_version_scope
                WHERE version_id=? ORDER BY usage_scope
                """, String.class, versionId);
    }

    public int approveItem(String versionId, int expectedRevision, long reviewerId,
                           LocalDateTime approvedAt) {
        return jdbc.update("""
                UPDATE biz_energy_item_version
                SET status='APPROVED',approved_by=?,approved_at=?,config_revision=config_revision+1
                WHERE version_id=? AND status='PENDING_EXPERT' AND config_revision=?
                """, reviewerId, timestamp(approvedAt), versionId, expectedRevision);
    }

    public String findUnitIdForUpdate(String unitCode) {
        return optionalString("SELECT unit_id FROM biz_measurement_unit WHERE unit_code=? FOR UPDATE", unitCode);
    }

    public void insertUnit(String unitId, String unitCode, long userId, LocalDateTime now) {
        jdbc.update("INSERT INTO biz_measurement_unit(unit_id,unit_code,created_by,created_at) VALUES (?,?,?,?)",
                unitId, unitCode, userId, timestamp(now));
    }

    public int nextUnitVersion(String unitId) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM biz_measurement_unit_version WHERE unit_id=?",
                Integer.class, unitId);
    }

    public void insertUnitVersion(UnitVersionRow value) {
        jdbc.update("""
                INSERT INTO biz_measurement_unit_version
                (version_id,unit_id,version_no,symbol,unit_name,dimension_code,canonical_unit_code,
                 scale_factor,conversion_type,standard_condition_code,decimal_precision,status,
                 source_type,source_reference,effective_from,effective_to,config_revision,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'PENDING_EXPERT',?,?,?,?,0,?,?)
                """, value.versionId(), value.unitId(), value.versionNo(), value.symbol(), value.unitName(),
                value.dimensionCode(), value.canonicalUnitCode(), value.scaleFactor(), value.conversionType(),
                value.standardConditionCode(), value.precision(), value.sourceType(), value.sourceReference(),
                timestamp(value.effectiveFrom()), timestamp(value.effectiveTo()), value.createdBy(),
                timestamp(value.createdAt()));
    }

    public List<UnitVersionRow> listUnits() {
        return jdbc.query("""
                SELECT u.unit_id,u.unit_code,v.* FROM biz_measurement_unit u
                JOIN biz_measurement_unit_version v ON v.unit_id=u.unit_id
                ORDER BY u.unit_code,v.version_no DESC
                """, EnergyCatalogRepository::unitRow);
    }

    public UnitVersionRow findUnitVersion(String versionId) {
        return optional("""
                SELECT u.unit_id,u.unit_code,v.* FROM biz_measurement_unit u
                JOIN biz_measurement_unit_version v ON v.unit_id=u.unit_id WHERE v.version_id=?
                """, EnergyCatalogRepository::unitRow, versionId);
    }

    public UnitVersionRow findApprovedUnitByCode(String unitCode, LocalDateTime at) {
        return optional("""
                SELECT u.unit_id,u.unit_code,v.* FROM biz_measurement_unit u
                JOIN biz_measurement_unit_version v ON v.unit_id=u.unit_id
                WHERE u.unit_code=? AND v.status='APPROVED' AND v.effective_from<=?
                  AND (v.effective_to IS NULL OR v.effective_to>?)
                ORDER BY v.version_no DESC LIMIT 1
                """, EnergyCatalogRepository::unitRow, unitCode, timestamp(at), timestamp(at));
    }

    public UnitVersionRow findApprovedUnitBySymbol(String symbol, LocalDateTime at) {
        return optional("""
                SELECT u.unit_id,u.unit_code,v.* FROM biz_measurement_unit u
                JOIN biz_measurement_unit_version v ON v.unit_id=u.unit_id
                WHERE v.symbol=? AND v.status='APPROVED' AND v.effective_from<=?
                  AND (v.effective_to IS NULL OR v.effective_to>?)
                ORDER BY v.version_no DESC LIMIT 1
                """, EnergyCatalogRepository::unitRow, symbol, timestamp(at), timestamp(at));
    }

    public UnitVersionRow findLatestApprovedUnit(String unitId) {
        return optional("""
                SELECT u.unit_id,u.unit_code,v.* FROM biz_measurement_unit u
                JOIN biz_measurement_unit_version v ON v.unit_id=u.unit_id
                WHERE u.unit_id=? AND v.status='APPROVED'
                ORDER BY v.version_no DESC LIMIT 1
                """, EnergyCatalogRepository::unitRow, unitId);
    }

    public void closeUnitVersion(String versionId, LocalDateTime effectiveTo) {
        jdbc.update("UPDATE biz_measurement_unit_version SET effective_to=? WHERE version_id=?",
                timestamp(effectiveTo), versionId);
    }

    public int approveUnit(String versionId, int expectedRevision, long reviewerId,
                           LocalDateTime approvedAt) {
        return jdbc.update("""
                UPDATE biz_measurement_unit_version
                SET status='APPROVED',approved_by=?,approved_at=?,config_revision=config_revision+1
                WHERE version_id=? AND status='PENDING_EXPERT' AND config_revision=?
                """, reviewerId, timestamp(approvedAt), versionId, expectedRevision);
    }

    public String findCompatibilityIdForUpdate(String itemId, String unitId, String valueSemantics) {
        return optionalString("""
                SELECT compatibility_id FROM biz_energy_item_unit_compatibility
                WHERE item_id=? AND unit_id=? AND value_semantics=? FOR UPDATE
                """, itemId, unitId, valueSemantics);
    }

    public void insertCompatibility(String id, String itemId, String unitId, String semantics,
                                    long userId, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO biz_energy_item_unit_compatibility
                (compatibility_id,item_id,unit_id,value_semantics,created_by,created_at)
                VALUES (?,?,?,?,?,?)
                """, id, itemId, unitId, semantics, userId, timestamp(now));
    }

    public int nextCompatibilityVersion(String compatibilityId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no),0)+1
                FROM biz_energy_item_unit_compatibility_version WHERE compatibility_id=?
                """, Integer.class, compatibilityId);
    }

    public void insertCompatibilityVersion(CompatibilityVersionRow value) {
        jdbc.update("""
                INSERT INTO biz_energy_item_unit_compatibility_version
                (version_id,compatibility_id,version_no,allowed,conversion_requirement,status,
                 source_type,source_reference,effective_from,effective_to,config_revision,created_by,created_at)
                VALUES (?,?,?,?,?,'PENDING_EXPERT',?,?,?,?,0,?,?)
                """, value.versionId(), value.compatibilityId(), value.versionNo(), value.allowed(),
                value.conversionRequirement(), value.sourceType(), value.sourceReference(),
                timestamp(value.effectiveFrom()), timestamp(value.effectiveTo()), value.createdBy(),
                timestamp(value.createdAt()));
    }

    public List<CompatibilityVersionRow> listCompatibilities() {
        return jdbc.query(compatibilitySelect() + " ORDER BY i.item_code,u.unit_code,c.value_semantics,v.version_no DESC",
                EnergyCatalogRepository::compatibilityRow);
    }

    public CompatibilityVersionRow findCompatibilityVersion(String versionId) {
        return optional(compatibilitySelect() + " WHERE v.version_id=?",
                EnergyCatalogRepository::compatibilityRow, versionId);
    }

    public CompatibilityVersionRow findApprovedCompatibility(String itemId, String unitId,
                                                               String semantics, LocalDateTime at) {
        return optional(compatibilitySelect() + """
                 WHERE c.item_id=? AND c.unit_id=? AND c.value_semantics=?
                   AND v.status='APPROVED' AND v.effective_from<=?
                   AND (v.effective_to IS NULL OR v.effective_to>?)
                 ORDER BY v.version_no DESC LIMIT 1
                """, EnergyCatalogRepository::compatibilityRow, itemId, unitId, semantics,
                timestamp(at), timestamp(at));
    }

    public CompatibilityVersionRow findLatestApprovedCompatibility(String compatibilityId) {
        return optional(compatibilitySelect() + """
                 WHERE c.compatibility_id=? AND v.status='APPROVED'
                 ORDER BY v.version_no DESC LIMIT 1
                """, EnergyCatalogRepository::compatibilityRow, compatibilityId);
    }

    public void closeCompatibilityVersion(String versionId, LocalDateTime effectiveTo) {
        jdbc.update("""
                UPDATE biz_energy_item_unit_compatibility_version
                SET effective_to=? WHERE version_id=?
                """, timestamp(effectiveTo), versionId);
    }

    public int approveCompatibility(String versionId, int expectedRevision, long reviewerId,
                                    LocalDateTime approvedAt) {
        return jdbc.update("""
                UPDATE biz_energy_item_unit_compatibility_version
                SET status='APPROVED',approved_by=?,approved_at=?,config_revision=config_revision+1
                WHERE version_id=? AND status='PENDING_EXPERT' AND config_revision=?
                """, reviewerId, timestamp(approvedAt), versionId, expectedRevision);
    }

    public String findBindingIdForUpdate(String pointId) {
        return optionalString("SELECT binding_id FROM biz_energy_point_item_binding WHERE point_id=? FOR UPDATE",
                pointId);
    }

    public void insertBinding(String id, String buildingId, String pointId, long userId,
                              LocalDateTime now) {
        jdbc.update("""
                INSERT INTO biz_energy_point_item_binding
                (binding_id,building_id,point_id,created_by,created_at) VALUES (?,?,?,?,?)
                """, id, buildingId, pointId, userId, timestamp(now));
    }

    public int nextBindingVersion(String bindingId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(MAX(binding_version),0)+1
                FROM biz_energy_point_item_binding_version WHERE binding_id=?
                """, Integer.class, bindingId);
    }

    public void insertBindingVersion(BindingVersionRow value) {
        jdbc.update("""
                INSERT INTO biz_energy_point_item_binding_version
                (binding_version_id,binding_id,binding_version,energy_item_version_id,effective_from,
                 effective_to,confirmation_status,evidence_reference,config_revision,created_by,created_at)
                VALUES (?,?,?,?,?,?,'PENDING_EXPERT',?,0,?,?)
                """, value.bindingVersionId(), value.bindingId(), value.bindingVersion(),
                value.energyItemVersionId(), timestamp(value.effectiveFrom()), timestamp(value.effectiveTo()),
                value.evidenceReference(), value.createdBy(), timestamp(value.createdAt()));
    }

    public List<BindingVersionRow> listBindings(String buildingId, String pointId) {
        String sql = bindingSelect() + " WHERE b.building_id=?";
        if (pointId == null || pointId.isBlank()) {
            return jdbc.query(sql + " ORDER BY b.point_id,v.binding_version DESC",
                    EnergyCatalogRepository::bindingRow, buildingId);
        }
        return jdbc.query(sql + " AND b.point_id=? ORDER BY v.binding_version DESC",
                EnergyCatalogRepository::bindingRow, buildingId, pointId.trim());
    }

    public BindingVersionRow findBindingVersion(String versionId) {
        return optional(bindingSelect() + " WHERE v.binding_version_id=?",
                EnergyCatalogRepository::bindingRow, versionId);
    }

    public BindingVersionRow findEffectiveBinding(String pointId, LocalDateTime at) {
        return optional(bindingSelect() + """
                 WHERE b.point_id=? AND v.confirmation_status='CONFIRMED'
                   AND v.effective_from<=? AND (v.effective_to IS NULL OR v.effective_to>?)
                 ORDER BY v.binding_version DESC LIMIT 1
                """, EnergyCatalogRepository::bindingRow, pointId, timestamp(at), timestamp(at));
    }

    public BindingVersionRow findLatestConfirmedBinding(String bindingId) {
        return optional(bindingSelect() + """
                 WHERE b.binding_id=? AND v.confirmation_status='CONFIRMED'
                 ORDER BY v.binding_version DESC LIMIT 1
                """, EnergyCatalogRepository::bindingRow, bindingId);
    }

    public void closeBindingVersion(String versionId, LocalDateTime effectiveTo) {
        jdbc.update("UPDATE biz_energy_point_item_binding_version SET effective_to=? WHERE binding_version_id=?",
                timestamp(effectiveTo), versionId);
    }

    public int approveBinding(String versionId, int expectedRevision, long reviewerId,
                              LocalDateTime approvedAt) {
        return jdbc.update("""
                UPDATE biz_energy_point_item_binding_version
                SET confirmation_status='CONFIRMED',approved_by=?,approved_at=?,config_revision=config_revision+1
                WHERE binding_version_id=? AND confirmation_status='PENDING_EXPERT' AND config_revision=?
                """, reviewerId, timestamp(approvedAt), versionId, expectedRevision);
    }

    private static String compatibilitySelect() {
        return """
                SELECT c.compatibility_id,c.value_semantics,i.item_code,u.unit_code,v.*
                FROM biz_energy_item_unit_compatibility c
                JOIN biz_energy_item i ON i.item_id=c.item_id
                JOIN biz_measurement_unit u ON u.unit_id=c.unit_id
                JOIN biz_energy_item_unit_compatibility_version v
                  ON v.compatibility_id=c.compatibility_id
                """;
    }

    private static String bindingSelect() {
        return """
                SELECT b.binding_id,b.building_id,b.point_id,p.point_code,p.unit AS raw_unit,
                       v.*,i.item_code
                FROM biz_energy_point_item_binding b
                JOIN biz_energy_point_item_binding_version v ON v.binding_id=b.binding_id
                JOIN biz_data_point p ON p.point_id=b.point_id
                JOIN biz_energy_item_version iv ON iv.version_id=v.energy_item_version_id
                JOIN biz_energy_item i ON i.item_id=iv.item_id
                """;
    }

    private String optionalString(String sql, Object... args) {
        try {
            return jdbc.queryForObject(sql, String.class, args);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private <T> T optional(String sql, org.springframework.jdbc.core.RowMapper<T> mapper,
                           Object... args) {
        try {
            return jdbc.queryForObject(sql, mapper, args);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private static ItemVersionRow itemRow(ResultSet rs, int rowNum) throws SQLException {
        return new ItemVersionRow(rs.getString("item_id"), rs.getString("item_code"),
                rs.getString("version_id"), rs.getInt("version_no"), rs.getString("item_name"),
                rs.getString("compatible_category"), rs.getString("status"), rs.getString("source_type"),
                rs.getString("source_reference"), time(rs, "effective_from"), time(rs, "effective_to"),
                rs.getInt("config_revision"), nullableLong(rs, "created_by"), time(rs, "created_at"),
                nullableLong(rs, "approved_by"), time(rs, "approved_at"));
    }

    private static UnitVersionRow unitRow(ResultSet rs, int rowNum) throws SQLException {
        return new UnitVersionRow(rs.getString("unit_id"), rs.getString("unit_code"),
                rs.getString("version_id"), rs.getInt("version_no"), rs.getString("symbol"),
                rs.getString("unit_name"), rs.getString("dimension_code"),
                rs.getString("canonical_unit_code"), rs.getBigDecimal("scale_factor"),
                rs.getString("conversion_type"), rs.getString("standard_condition_code"),
                rs.getInt("decimal_precision"), rs.getString("status"), rs.getString("source_type"),
                rs.getString("source_reference"), time(rs, "effective_from"), time(rs, "effective_to"),
                rs.getInt("config_revision"), nullableLong(rs, "created_by"), time(rs, "created_at"),
                nullableLong(rs, "approved_by"), time(rs, "approved_at"));
    }

    private static CompatibilityVersionRow compatibilityRow(ResultSet rs, int rowNum) throws SQLException {
        return new CompatibilityVersionRow(rs.getString("compatibility_id"), rs.getString("version_id"),
                rs.getInt("version_no"), rs.getString("item_code"), rs.getString("unit_code"),
                rs.getString("value_semantics"), rs.getBoolean("allowed"),
                rs.getString("conversion_requirement"), rs.getString("status"),
                rs.getString("source_type"), rs.getString("source_reference"),
                time(rs, "effective_from"), time(rs, "effective_to"), rs.getInt("config_revision"),
                nullableLong(rs, "created_by"), time(rs, "created_at"), nullableLong(rs, "approved_by"),
                time(rs, "approved_at"));
    }

    private static BindingVersionRow bindingRow(ResultSet rs, int rowNum) throws SQLException {
        return new BindingVersionRow(rs.getString("binding_id"), rs.getString("binding_version_id"),
                rs.getInt("binding_version"), rs.getString("building_id"), rs.getString("point_id"),
                rs.getString("point_code"), rs.getString("raw_unit"), rs.getString("item_code"),
                rs.getString("energy_item_version_id"), time(rs, "effective_from"),
                time(rs, "effective_to"), rs.getString("confirmation_status"),
                rs.getString("evidence_reference"), rs.getInt("config_revision"),
                nullableLong(rs, "created_by"), time(rs, "created_at"), nullableLong(rs, "approved_by"),
                time(rs, "approved_at"));
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    public record ItemVersionRow(
            String itemId, String itemCode, String versionId, int versionNo, String itemName,
            String compatibleCategory, String status, String sourceType, String sourceReference,
            LocalDateTime effectiveFrom, LocalDateTime effectiveTo, int configRevision,
            Long createdBy, LocalDateTime createdAt, Long approvedBy, LocalDateTime approvedAt) {
    }

    public record UnitVersionRow(
            String unitId, String unitCode, String versionId, int versionNo, String symbol,
            String unitName, String dimensionCode, String canonicalUnitCode, BigDecimal scaleFactor,
            String conversionType, String standardConditionCode, int precision, String status,
            String sourceType, String sourceReference, LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo, int configRevision, Long createdBy, LocalDateTime createdAt,
            Long approvedBy, LocalDateTime approvedAt) {
    }

    public record CompatibilityVersionRow(
            String compatibilityId, String versionId, int versionNo, String itemCode, String unitCode,
            String valueSemantics, boolean allowed, String conversionRequirement, String status,
            String sourceType, String sourceReference, LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo, int configRevision, Long createdBy, LocalDateTime createdAt,
            Long approvedBy, LocalDateTime approvedAt) {
    }

    public record BindingVersionRow(
            String bindingId, String bindingVersionId, int bindingVersion, String buildingId,
            String pointId, String pointCode, String rawUnit, String itemCode,
            String energyItemVersionId, LocalDateTime effectiveFrom, LocalDateTime effectiveTo,
            String confirmationStatus, String evidenceReference, int configRevision,
            Long createdBy, LocalDateTime createdAt, Long approvedBy, LocalDateTime approvedAt) {
    }
}
