package com.platform.carbon;

import com.platform.carbon.CarbonModels.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
/** 保存碳规则稳定身份和不可覆盖版本；状态迁移只修改审核与生效元数据。 */
class CarbonRuleRepository {
    private final JdbcTemplate jdbc;

    CarbonRuleRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    String findSourceIdForUpdate(String sourceCode) {
        return optionalString("SELECT source_id FROM biz_carbon_factor_source WHERE source_code=? FOR UPDATE",
                sourceCode);
    }

    void insertSourceIdentity(String sourceId, String sourceCode, long userId, LocalDateTime now) {
        jdbc.update("INSERT INTO biz_carbon_factor_source(source_id,source_code,created_by,created_at) VALUES (?,?,?,?)",
                sourceId, sourceCode, userId, timestamp(now));
    }

    int nextSourceVersion(String sourceId) {
        return next("biz_carbon_factor_source_version", "source_id", sourceId);
    }

    void insertSourceVersion(FactorSourceVersion value) {
        jdbc.update("""
                INSERT INTO biz_carbon_factor_source_version
                (source_version_id,source_id,version_no,source_name,publisher,document_reference,
                 publication_year,published_on,applicability_note,evidence_reference,usage_nature,
                 created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, value.sourceVersionId(), value.sourceId(), value.versionNo(), value.sourceName(),
                value.publisher(), value.documentReference(), value.publicationYear(),
                date(value.publishedOn()), value.applicabilityNote(), value.evidenceReference(),
                value.usageNature().name(), value.createdBy(), timestamp(value.createdAt()));
    }

    FactorSourceVersion findSourceVersion(String sourceVersionId) {
        return one("""
                SELECT s.source_id,s.source_code,v.* FROM biz_carbon_factor_source s
                JOIN biz_carbon_factor_source_version v ON v.source_id=s.source_id
                WHERE v.source_version_id=?
                """, CarbonRuleRepository::source, sourceVersionId);
    }

    List<FactorSourceVersion> listSourceVersions(int limit) {
        return jdbc.query("""
                SELECT s.source_id,s.source_code,v.* FROM biz_carbon_factor_source s
                JOIN biz_carbon_factor_source_version v ON v.source_id=s.source_id
                ORDER BY v.created_at DESC LIMIT ?
                """, CarbonRuleRepository::source, limit);
    }

    String findFactorIdForUpdate(String factorCode) {
        return optionalString("SELECT factor_id FROM biz_carbon_factor WHERE factor_code=? FOR UPDATE",
                factorCode);
    }

    FactorIdentity findFactorIdentity(String factorId) {
        return one("SELECT * FROM biz_carbon_factor WHERE factor_id=?",
                CarbonRuleRepository::factorIdentity, factorId);
    }

    void insertFactorIdentity(FactorIdentity value) {
        jdbc.update("""
                INSERT INTO biz_carbon_factor
                (factor_id,factor_code,scope_type,energy_item_code,factor_category,result_basis,
                 gas_code,gas_coverage,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)
                """, value.factorId(), value.factorCode(), value.scopeType().name(),
                value.energyItemCode(), value.category().name(), value.resultBasis(),
                value.gasCode(), value.gasCoverage(), value.createdBy(), timestamp(value.createdAt()));
    }

    int nextFactorVersion(String factorId) {
        return next("biz_carbon_factor_version", "factor_id", factorId);
    }

    void insertFactorVersion(FactorVersion value) {
        jdbc.update("""
                INSERT INTO biz_carbon_factor_version
                (factor_version_id,factor_id,version_no,source_version_id,applicability_level,
                 building_id,region_code,input_unit_code,standard_condition_code,usage_nature,status,
                 effective_from,effective_to,formula_version_id,rounding_policy_version_id,
                 config_revision,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING_REVIEW',?,?,?,?,0,?,?)
                """, value.factorVersionId(), value.factorId(), value.versionNo(),
                value.sourceVersionId(), value.applicabilityLevel().name(), value.buildingId(),
                value.regionCode(), value.inputUnitCode(), value.standardConditionCode(),
                value.usageNature().name(), timestamp(value.effectiveFrom()),
                timestamp(value.effectiveTo()), value.formulaVersionId(),
                value.roundingPolicyVersionId(), value.createdBy(), timestamp(value.createdAt()));
    }

    void insertComponents(String factorVersionId, List<FactorComponent> values) {
        jdbc.batchUpdate("""
                INSERT INTO biz_carbon_factor_component
                (component_id,factor_version_id,component_type,component_value,component_unit,
                 source_version_id,evidence_reference) VALUES (?,?,?,?,?,?,?)
                """, values, values.size(), (statement, value) -> {
                    statement.setString(1, value.componentId());
                    statement.setString(2, factorVersionId);
                    statement.setString(3, value.type().name());
                    statement.setBigDecimal(4, value.value());
                    statement.setString(5, value.unit());
                    statement.setString(6, value.sourceVersionId());
                    statement.setString(7, value.evidenceReference());
                });
    }

    FactorVersion findFactorVersion(String factorVersionId) {
        FactorVersion value = one(factorSelect() + " WHERE v.factor_version_id=?",
                this::factor, factorVersionId);
        return withComponents(value);
    }

    List<FactorVersion> listFactorVersions(int limit) {
        return jdbc.query(factorSelect() + " ORDER BY v.created_at DESC LIMIT ?",
                this::factor, limit).stream().map(this::withComponents).toList();
    }

    List<FactorVersion> findCandidateFactors(String energyItemCode,
                                             LocalDateTime start, LocalDateTime end) {
        return jdbc.query(factorSelect() + """
                 WHERE f.energy_item_code=? AND v.status='ACTIVE'
                   AND v.effective_from<=? AND (v.effective_to IS NULL OR v.effective_to>=?)
                """, this::factor, energyItemCode, timestamp(start), timestamp(end)).stream()
                .map(this::withComponents).toList();
    }

    List<GwpVersion> findActiveGwp(String gasCode, UsageNature nature,
                                   LocalDateTime start, LocalDateTime end) {
        return jdbc.query("""
                SELECT gwp_version_id,gas_code,gwp_value,source_reference,usage_nature,
                       effective_from,effective_to
                FROM biz_carbon_gwp_version
                WHERE gas_code=? AND status='ACTIVE'
                  AND (?='DEVELOPMENT_REFERENCE' OR usage_nature='FORMAL')
                  AND effective_from<=? AND (effective_to IS NULL OR effective_to>=?)
                """, (rs, row) -> new GwpVersion(rs.getString("gwp_version_id"),
                rs.getString("gas_code"), rs.getBigDecimal("gwp_value"),
                rs.getString("source_reference"),
                UsageNature.valueOf(rs.getString("usage_nature")),
                local(rs, "effective_from"), local(rs, "effective_to")),
                gasCode, nature.name(), timestamp(start), timestamp(end));
    }

    int reviewFactor(String versionId, int revision, long reviewerId, LocalDateTime now,
                     String comment, boolean approved) {
        return jdbc.update("""
                UPDATE biz_carbon_factor_version
                SET status=?,reviewed_by=?,reviewed_at=?,review_comment=?,config_revision=config_revision+1
                WHERE factor_version_id=? AND status='PENDING_REVIEW' AND config_revision=?
                """, approved ? "APPROVED" : "REJECTED", reviewerId, timestamp(now), comment,
                versionId, revision);
    }

    int activateFactor(String versionId, int revision, long actorId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE biz_carbon_factor_version
                SET status='ACTIVE',activated_by=?,activated_at=?,config_revision=config_revision+1
                WHERE factor_version_id=? AND status='APPROVED' AND config_revision=?
                """, actorId, timestamp(now), versionId, revision);
    }

    int disableFactor(String versionId, int revision, long actorId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE biz_carbon_factor_version
                SET status='DISABLED',disabled_by=?,disabled_at=?,config_revision=config_revision+1
                WHERE factor_version_id=? AND status='ACTIVE' AND config_revision=?
                """, actorId, timestamp(now), versionId, revision);
    }

    void disableOverlappingFactors(FactorVersion value, long actorId, LocalDateTime now) {
        jdbc.update("""
                UPDATE biz_carbon_factor_version SET status='DISABLED',disabled_by=?,disabled_at=?,
                    config_revision=config_revision+1
                WHERE factor_id=? AND factor_version_id<>? AND status='ACTIVE'
                  AND applicability_level=? AND COALESCE(building_id,'')=COALESCE(?, '')
                  AND COALESCE(region_code,'')=COALESCE(?, '') AND usage_nature=?
                  AND effective_from < COALESCE(?, '9999-12-31')
                  AND COALESCE(effective_to,'9999-12-31') > ?
                """, actorId, timestamp(now), value.factorId(), value.factorVersionId(),
                value.applicabilityLevel().name(), value.buildingId(), value.regionCode(),
                value.usageNature().name(), timestamp(value.effectiveTo()),
                timestamp(value.effectiveFrom()));
    }

    boolean hasOverlappingActiveFactor(FactorVersion value) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_carbon_factor_version
                WHERE factor_id=? AND factor_version_id<>? AND status='ACTIVE'
                  AND applicability_level=? AND COALESCE(building_id,'')=COALESCE(?, '')
                  AND COALESCE(region_code,'')=COALESCE(?, '') AND usage_nature=?
                  AND effective_from < COALESCE(?, '9999-12-31')
                  AND COALESCE(effective_to,'9999-12-31') > ?
                """, Integer.class, value.factorId(), value.factorVersionId(),
                value.applicabilityLevel().name(), value.buildingId(), value.regionCode(),
                value.usageNature().name(), timestamp(value.effectiveTo()),
                timestamp(value.effectiveFrom()));
        return count != null && count > 0;
    }

    String findDenominatorIdForUpdate(String buildingId, DenominatorType type) {
        return optionalString("""
                SELECT denominator_id FROM biz_carbon_denominator
                WHERE building_id=? AND denominator_type=? FOR UPDATE
                """, buildingId, type.name());
    }

    void insertDenominatorIdentity(String id, String buildingId, DenominatorType type,
                                   long userId, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO biz_carbon_denominator
                (denominator_id,building_id,denominator_type,created_by,created_at)
                VALUES (?,?,?,?,?)
                """, id, buildingId, type.name(), userId, timestamp(now));
    }

    int nextDenominatorVersion(String denominatorId) {
        return next("biz_carbon_denominator_version", "denominator_id", denominatorId);
    }

    void insertDenominatorVersion(DenominatorVersion value) {
        jdbc.update("""
                INSERT INTO biz_carbon_denominator_version
                (denominator_version_id,denominator_id,version_no,denominator_value,unit_code,
                 source_reference,evidence_reference,usage_nature,status,effective_from,effective_to,
                 config_revision,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,'PENDING_REVIEW',?,?,0,?,?)
                """, value.denominatorVersionId(), value.denominatorId(), value.versionNo(),
                value.value(), value.unitCode(), value.sourceReference(), value.evidenceReference(),
                value.usageNature().name(), date(value.effectiveFrom()), date(value.effectiveTo()),
                value.createdBy(), timestamp(value.createdAt()));
    }

    DenominatorVersion findDenominatorVersion(String versionId) {
        return one(denominatorSelect() + " WHERE v.denominator_version_id=?",
                CarbonRuleRepository::denominator, versionId);
    }

    List<DenominatorVersion> listDenominatorVersions(String buildingId, int limit) {
        return jdbc.query(denominatorSelect() + " WHERE d.building_id=? ORDER BY v.created_at DESC LIMIT ?",
                CarbonRuleRepository::denominator, buildingId, limit);
    }

    List<DenominatorVersion> findActiveDenominators(String buildingId, DenominatorType type,
                                                     UsageNature nature, LocalDate start,
                                                     LocalDate end) {
        return jdbc.query(denominatorSelect() + """
                 WHERE d.building_id=? AND d.denominator_type=? AND v.usage_nature=?
                   AND v.status='ACTIVE' AND v.effective_from<=?
                   AND (v.effective_to IS NULL OR v.effective_to>=?)
                """, CarbonRuleRepository::denominator, buildingId, type.name(), nature.name(),
                date(start), date(end));
    }

    int reviewDenominator(String versionId, int revision, long reviewerId, LocalDateTime now,
                          String comment, boolean approved) {
        return jdbc.update("""
                UPDATE biz_carbon_denominator_version
                SET status=?,reviewed_by=?,reviewed_at=?,review_comment=?,config_revision=config_revision+1
                WHERE denominator_version_id=? AND status='PENDING_REVIEW' AND config_revision=?
                """, approved ? "APPROVED" : "REJECTED", reviewerId, timestamp(now), comment,
                versionId, revision);
    }

    int activateDenominator(String versionId, int revision, long actorId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE biz_carbon_denominator_version
                SET status='ACTIVE',activated_by=?,activated_at=?,config_revision=config_revision+1
                WHERE denominator_version_id=? AND status='APPROVED' AND config_revision=?
                """, actorId, timestamp(now), versionId, revision);
    }

    int disableDenominator(String versionId, int revision, long actorId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE biz_carbon_denominator_version
                SET status='DISABLED',disabled_by=?,disabled_at=?,config_revision=config_revision+1
                WHERE denominator_version_id=? AND status='ACTIVE' AND config_revision=?
                """, actorId, timestamp(now), versionId, revision);
    }

    void disableOverlappingDenominators(DenominatorVersion value, long actorId, LocalDateTime now) {
        jdbc.update("""
                UPDATE biz_carbon_denominator_version SET status='DISABLED',disabled_by=?,disabled_at=?,
                    config_revision=config_revision+1
                WHERE denominator_id=? AND denominator_version_id<>? AND status='ACTIVE'
                  AND usage_nature=? AND effective_from < COALESCE(?, '9999-12-31')
                  AND COALESCE(effective_to,'9999-12-31') > ?
                """, actorId, timestamp(now), value.denominatorId(), value.denominatorVersionId(),
                value.usageNature().name(), date(value.effectiveTo()), date(value.effectiveFrom()));
    }

    String findBuildingRegion(String buildingId) {
        return optionalString("SELECT region_code FROM building WHERE building_id=?", buildingId);
    }

    String activeRoundingPolicyId() {
        return optionalString("""
                SELECT rounding_policy_version_id FROM biz_carbon_rounding_policy_version
                WHERE policy_code='CARBON_RESULT_ROUNDING' AND status='ACTIVE'
                ORDER BY version_no DESC LIMIT 1
                """);
    }

    boolean activeFormulaMatches(String formulaVersionId, FactorCategory category,
                                 UsageNature nature) {
        String expected = switch (category) {
            case STATIONARY_COMBUSTION -> "STATIONARY_COMBUSTION_CO2_V1";
            case PURCHASED_ELECTRICITY_LOCATION ->
                    "PURCHASED_ELECTRICITY_LOCATION_CO2E_V1";
            case PURCHASED_HEAT -> "PURCHASED_HEAT_CO2E_V1";
        };
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_carbon_formula_version
                WHERE formula_version_id=? AND algorithm_code=? AND status='ACTIVE'
                  AND (?='DEVELOPMENT_REFERENCE' OR usage_nature='FORMAL')
                """, Integer.class, formulaVersionId, expected, nature.name());
        return count != null && count == 1;
    }

    boolean activeRoundingPolicy(String versionId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_carbon_rounding_policy_version
                WHERE rounding_policy_version_id=? AND status='ACTIVE'
                """, Integer.class, versionId);
        return count != null && count == 1;
    }

    void insertDependencyChange(String id, String type, String objectType, String objectId,
                                String changeDetail,
                                String oldVersionId, String newVersionId, String fingerprint,
                                String buildingId, String organizationBoundary,
                                LocalDateTime from, LocalDateTime to,
                                Long actorId, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO biz_carbon_dependency_change
                (change_id,change_type,source_object_type,source_object_id,change_detail,old_version_id,
                 new_version_id,change_fingerprint,building_id,effective_from,effective_to,
                 organization_boundary,triggered_by,status,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',?)
                """, id, type, objectType, objectId, changeDetail, oldVersionId, newVersionId,
                fingerprint, buildingId, timestamp(from), timestamp(to), organizationBoundary,
                actorId, timestamp(now));
    }

    DependencyChange findDependencyChangeByFingerprint(String fingerprint) {
        return one("SELECT * FROM biz_carbon_dependency_change WHERE change_fingerprint=?",
                (rs, row) -> new DependencyChange(rs.getString("change_id"),
                        rs.getString("change_type"), rs.getString("source_object_type"),
                        rs.getString("source_object_id"), rs.getString("change_detail"),
                        rs.getString("old_version_id"),
                        rs.getString("new_version_id"), rs.getString("change_fingerprint"),
                        rs.getString("building_id"), rs.getString("organization_boundary"),
                        local(rs, "effective_from"),
                        local(rs, "effective_to"), nullableLong(rs, "triggered_by"),
                        rs.getString("status"), local(rs, "created_at"),
                        local(rs, "processed_at")), fingerprint);
    }

    private FactorVersion withComponents(FactorVersion value) {
        if (value == null) return null;
        List<FactorComponent> components = jdbc.query("""
                SELECT * FROM biz_carbon_factor_component
                WHERE factor_version_id=? ORDER BY component_type
                """, CarbonRuleRepository::component, value.factorVersionId());
        return new FactorVersion(value.factorId(), value.factorCode(), value.factorVersionId(),
                value.versionNo(), value.scopeType(), value.energyItemCode(), value.category(),
                value.resultBasis(), value.gasCode(), value.gasCoverage(), value.sourceVersionId(),
                value.applicabilityLevel(), value.buildingId(), value.regionCode(),
                value.inputUnitCode(), value.standardConditionCode(), value.usageNature(),
                value.status(), value.effectiveFrom(), value.effectiveTo(),
                value.formulaVersionId(), value.roundingPolicyVersionId(), value.configRevision(),
                value.createdBy(), value.createdAt(), value.reviewedBy(), value.reviewedAt(),
                value.reviewComment(), value.activatedBy(), value.activatedAt(), components);
    }

    private static String factorSelect() {
        return """
                SELECT f.factor_id,f.factor_code,f.scope_type,f.energy_item_code,f.factor_category,
                       f.result_basis,f.gas_code,f.gas_coverage,v.*
                FROM biz_carbon_factor f JOIN biz_carbon_factor_version v ON v.factor_id=f.factor_id
                """;
    }

    private static String denominatorSelect() {
        return """
                SELECT d.denominator_id,d.building_id,d.denominator_type,v.*
                FROM biz_carbon_denominator d JOIN biz_carbon_denominator_version v
                  ON v.denominator_id=d.denominator_id
                """;
    }

    private int next(String table, String idColumn, String id) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM " + table
                + " WHERE " + idColumn + "=?", Integer.class, id);
    }

    private String optionalString(String sql, Object... args) {
        List<String> values = jdbc.query(sql, (rs, row) -> rs.getString(1), args);
        return values.isEmpty() ? null : values.getFirst();
    }

    private <T> T one(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
        List<T> values = jdbc.query(sql, mapper, args);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static FactorSourceVersion source(ResultSet rs, int row) throws SQLException {
        return new FactorSourceVersion(rs.getString("source_id"), rs.getString("source_code"),
                rs.getString("source_version_id"), rs.getInt("version_no"),
                rs.getString("source_name"), rs.getString("publisher"),
                rs.getString("document_reference"), nullableInt(rs, "publication_year"),
                localDate(rs, "published_on"), rs.getString("applicability_note"),
                rs.getString("evidence_reference"), UsageNature.valueOf(rs.getString("usage_nature")),
                rs.getLong("created_by"), local(rs, "created_at"));
    }

    private FactorVersion factor(ResultSet rs, int row) throws SQLException {
        return new FactorVersion(rs.getString("factor_id"), rs.getString("factor_code"),
                rs.getString("factor_version_id"), rs.getInt("version_no"),
                ScopeType.valueOf(rs.getString("scope_type")), rs.getString("energy_item_code"),
                FactorCategory.valueOf(rs.getString("factor_category")), rs.getString("result_basis"),
                rs.getString("gas_code"), rs.getString("gas_coverage"),
                rs.getString("source_version_id"),
                ApplicabilityLevel.valueOf(rs.getString("applicability_level")),
                rs.getString("building_id"), rs.getString("region_code"),
                rs.getString("input_unit_code"), rs.getString("standard_condition_code"),
                UsageNature.valueOf(rs.getString("usage_nature")),
                LifecycleStatus.valueOf(rs.getString("status")), local(rs, "effective_from"),
                local(rs, "effective_to"), rs.getString("formula_version_id"),
                rs.getString("rounding_policy_version_id"), rs.getInt("config_revision"),
                rs.getLong("created_by"), local(rs, "created_at"), nullableLong(rs, "reviewed_by"),
                local(rs, "reviewed_at"), rs.getString("review_comment"),
                nullableLong(rs, "activated_by"), local(rs, "activated_at"), List.of());
    }

    private static FactorIdentity factorIdentity(ResultSet rs, int row) throws SQLException {
        return new FactorIdentity(rs.getString("factor_id"), rs.getString("factor_code"),
                ScopeType.valueOf(rs.getString("scope_type")), rs.getString("energy_item_code"),
                FactorCategory.valueOf(rs.getString("factor_category")), rs.getString("result_basis"),
                rs.getString("gas_code"), rs.getString("gas_coverage"), rs.getLong("created_by"),
                local(rs, "created_at"));
    }

    private static FactorComponent component(ResultSet rs, int row) throws SQLException {
        return new FactorComponent(rs.getString("component_id"),
                ComponentType.valueOf(rs.getString("component_type")),
                rs.getBigDecimal("component_value"), rs.getString("component_unit"),
                rs.getString("source_version_id"), rs.getString("evidence_reference"));
    }

    private static DenominatorVersion denominator(ResultSet rs, int row) throws SQLException {
        return new DenominatorVersion(rs.getString("denominator_id"),
                rs.getString("denominator_version_id"), rs.getInt("version_no"),
                rs.getString("building_id"), DenominatorType.valueOf(rs.getString("denominator_type")),
                rs.getBigDecimal("denominator_value"), rs.getString("unit_code"),
                rs.getString("source_reference"), rs.getString("evidence_reference"),
                UsageNature.valueOf(rs.getString("usage_nature")),
                LifecycleStatus.valueOf(rs.getString("status")), localDate(rs, "effective_from"),
                localDate(rs, "effective_to"), rs.getInt("config_revision"), rs.getLong("created_by"),
                local(rs, "created_at"), nullableLong(rs, "reviewed_by"), local(rs, "reviewed_at"),
                rs.getString("review_comment"), nullableLong(rs, "activated_by"),
                local(rs, "activated_at"));
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static Date date(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }

    private static LocalDateTime local(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    record FactorIdentity(String factorId, String factorCode, ScopeType scopeType,
                          String energyItemCode, FactorCategory category, String resultBasis,
                          String gasCode, String gasCoverage, long createdBy,
                          LocalDateTime createdAt) {
    }
}
