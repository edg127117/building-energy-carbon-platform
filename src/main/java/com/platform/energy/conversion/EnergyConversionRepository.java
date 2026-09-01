package com.platform.energy.conversion;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
/** 只在折标模块内部保存稳定身份和不可覆盖版本；下游计算通过 Service 获取证据。 */
class EnergyConversionRepository {
    private final JdbcTemplate jdbc;

    String findStandardCoalIdForUpdate(String code) {
        return optionalString("SELECT lhv_id FROM biz_standard_coal_lhv WHERE lhv_code=? FOR UPDATE", code);
    }

    void insertStandardCoalIdentity(String id, String code, long userId, LocalDateTime now) {
        jdbc.update("INSERT INTO biz_standard_coal_lhv(lhv_id,lhv_code,created_by,created_at) VALUES (?,?,?,?)",
                id, code, userId, timestamp(now));
    }

    int nextStandardCoalVersion(String id) {
        return next("biz_standard_coal_lhv_version", "lhv_id", id);
    }

    void insertStandardCoalVersion(StandardCoalRow value) {
        jdbc.update("""
                INSERT INTO biz_standard_coal_lhv_version
                (version_id,lhv_id,version_no,lhv_value,energy_unit_version_id,coal_unit_version_id,
                 parameter_unit,status,source_type,source_reference,effective_from,effective_to,
                 config_revision,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,'PENDING_EXPERT',?,?,?,?,0,?,?)
                """, value.versionId(), value.lhvId(), value.versionNo(), value.value(),
                value.energyUnitVersionId(), value.coalUnitVersionId(), value.parameterUnit(),
                value.sourceType(), value.sourceReference(), timestamp(value.effectiveFrom()),
                timestamp(value.effectiveTo()), value.createdBy(), timestamp(value.createdAt()));
    }

    List<StandardCoalRow> listStandardCoalVersions() {
        return jdbc.query("""
                SELECT i.lhv_id,i.lhv_code,v.* FROM biz_standard_coal_lhv i
                JOIN biz_standard_coal_lhv_version v ON v.lhv_id=i.lhv_id
                ORDER BY i.lhv_code,v.version_no DESC
                """, EnergyConversionRepository::standardCoalRow);
    }

    StandardCoalRow findStandardCoalVersion(String versionId) {
        return optional("""
                SELECT i.lhv_id,i.lhv_code,v.* FROM biz_standard_coal_lhv i
                JOIN biz_standard_coal_lhv_version v ON v.lhv_id=i.lhv_id
                WHERE v.version_id=?
                """, EnergyConversionRepository::standardCoalRow, versionId);
    }

    StandardCoalRow findLatestApprovedStandardCoal(String id) {
        return optional("""
                SELECT i.lhv_id,i.lhv_code,v.* FROM biz_standard_coal_lhv i
                JOIN biz_standard_coal_lhv_version v ON v.lhv_id=i.lhv_id
                WHERE i.lhv_id=? AND v.status='APPROVED'
                ORDER BY v.version_no DESC LIMIT 1
                """, EnergyConversionRepository::standardCoalRow, id);
    }

    int approveStandardCoal(String versionId, int expectedRevision, long reviewerId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE biz_standard_coal_lhv_version
                SET status='APPROVED',approved_by=?,approved_at=?,config_revision=config_revision+1
                WHERE version_id=? AND status='PENDING_EXPERT' AND config_revision=?
                """, reviewerId, timestamp(now), versionId, expectedRevision);
    }

    void closeStandardCoalVersion(String versionId, LocalDateTime effectiveTo) {
        jdbc.update("UPDATE biz_standard_coal_lhv_version SET effective_to=? WHERE version_id=?",
                timestamp(effectiveTo), versionId);
    }

    String findFormulaIdForUpdate(String code) {
        return optionalString("SELECT formula_id FROM biz_energy_conversion_formula WHERE formula_code=? FOR UPDATE", code);
    }

    void insertFormulaIdentity(String id, String code, long userId, LocalDateTime now) {
        jdbc.update("INSERT INTO biz_energy_conversion_formula(formula_id,formula_code,created_by,created_at) VALUES (?,?,?,?)",
                id, code, userId, timestamp(now));
    }

    int nextFormulaVersion(String id) {
        return next("biz_energy_conversion_formula_version", "formula_id", id);
    }

    void insertFormulaVersion(FormulaRow value) {
        jdbc.update("""
                INSERT INTO biz_energy_conversion_formula_version
                (version_id,formula_id,version_no,method,perspective,algorithm_code,
                 applicable_input_unit_version_id,result_unit_version_id,parameter_unit,status,
                 source_type,source_reference,effective_from,effective_to,config_revision,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,'PENDING_EXPERT',?,?,?,?,0,?,?)
                """, value.versionId(), value.formulaId(), value.versionNo(), value.method(),
                value.perspective(), value.algorithmCode(), value.inputUnitVersionId(),
                value.resultUnitVersionId(), value.parameterUnit(), value.sourceType(),
                value.sourceReference(), timestamp(value.effectiveFrom()), timestamp(value.effectiveTo()),
                value.createdBy(), timestamp(value.createdAt()));
    }

    List<FormulaRow> listFormulaVersions() {
        return jdbc.query(formulaSelect() + " ORDER BY i.formula_code,v.version_no DESC",
                EnergyConversionRepository::formulaRow);
    }

    FormulaRow findFormulaVersion(String versionId) {
        return optional(formulaSelect() + " WHERE v.version_id=?",
                EnergyConversionRepository::formulaRow, versionId);
    }

    FormulaRow findLatestApprovedFormula(String id) {
        return optional(formulaSelect() + " WHERE i.formula_id=? AND v.status='APPROVED'"
                        + " ORDER BY v.version_no DESC LIMIT 1",
                EnergyConversionRepository::formulaRow, id);
    }

    int approveFormula(String versionId, int expectedRevision, long reviewerId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE biz_energy_conversion_formula_version
                SET status='APPROVED',approved_by=?,approved_at=?,config_revision=config_revision+1
                WHERE version_id=? AND status='PENDING_EXPERT' AND config_revision=?
                """, reviewerId, timestamp(now), versionId, expectedRevision);
    }

    void closeFormulaVersion(String versionId, LocalDateTime effectiveTo) {
        jdbc.update("UPDATE biz_energy_conversion_formula_version SET effective_to=? WHERE version_id=?",
                timestamp(effectiveTo), versionId);
    }

    String findParameterIdForUpdate(String code) {
        return optionalString("SELECT parameter_id FROM biz_energy_conversion_parameter WHERE parameter_code=? FOR UPDATE", code);
    }

    String findParameterItemId(String id) {
        return optionalString("SELECT energy_item_id FROM biz_energy_conversion_parameter WHERE parameter_id=?", id);
    }

    void insertParameterIdentity(String id, String code, String itemId, long userId, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO biz_energy_conversion_parameter
                (parameter_id,parameter_code,energy_item_id,created_by,created_at) VALUES (?,?,?,?,?)
                """, id, code, itemId, userId, timestamp(now));
    }

    int nextParameterVersion(String id) {
        return next("biz_energy_conversion_parameter_version", "parameter_id", id);
    }

    void insertParameterVersion(ParameterRow value) {
        jdbc.update("""
                INSERT INTO biz_energy_conversion_parameter_version
                (version_id,parameter_id,version_no,energy_item_version_id,formula_version_id,
                 parameter_value,parameter_unit,standard_coal_lhv_version_id,consumption_scope,
                 region_code,usage_scope,status,source_type,source_reference,effective_from,effective_to,
                 config_revision,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'PENDING_EXPERT',?,?,?,?,0,?,?)
                """, value.versionId(), value.parameterId(), value.versionNo(),
                value.energyItemVersionId(), value.formulaVersionId(), value.parameterValue(),
                value.parameterUnit(), value.standardCoalVersionId(), value.consumptionScope(),
                value.regionCode(), value.usageScope(), value.sourceType(), value.sourceReference(),
                timestamp(value.effectiveFrom()), timestamp(value.effectiveTo()), value.createdBy(),
                timestamp(value.createdAt()));
    }

    List<ParameterRow> listParameterVersions() {
        return jdbc.query(parameterSelect() + " ORDER BY p.parameter_code,v.version_no DESC",
                EnergyConversionRepository::parameterRow);
    }

    ParameterRow findParameterVersion(String versionId) {
        return optional(parameterSelect() + " WHERE v.version_id=?",
                EnergyConversionRepository::parameterRow, versionId);
    }

    ParameterRow findLatestApprovedParameter(String id) {
        return optional(parameterSelect() + " WHERE p.parameter_id=? AND v.status='APPROVED'"
                        + " ORDER BY v.version_no DESC LIMIT 1",
                EnergyConversionRepository::parameterRow, id);
    }

    List<ParameterRow> findMatchingParameters(String itemId, String method, String perspective,
                                               String consumptionScope, String regionCode,
                                               String usageScope, LocalDateTime at) {
        return jdbc.query(parameterSelect() + """
                 WHERE p.energy_item_id=? AND f.method=? AND f.perspective=?
                   AND v.consumption_scope=? AND v.region_code=? AND v.usage_scope=?
                   AND v.status='APPROVED' AND v.effective_from<=?
                   AND (v.effective_to IS NULL OR v.effective_to>?)
                 ORDER BY p.parameter_code,v.version_no DESC
                """, EnergyConversionRepository::parameterRow, itemId, method, perspective,
                consumptionScope, regionCode, usageScope, timestamp(at), timestamp(at));
    }

    int approveParameter(String versionId, int expectedRevision, long reviewerId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE biz_energy_conversion_parameter_version
                SET status='APPROVED',approved_by=?,approved_at=?,config_revision=config_revision+1
                WHERE version_id=? AND status='PENDING_EXPERT' AND config_revision=?
                """, reviewerId, timestamp(now), versionId, expectedRevision);
    }

    void closeParameterVersion(String versionId, LocalDateTime effectiveTo) {
        jdbc.update("UPDATE biz_energy_conversion_parameter_version SET effective_to=? WHERE version_id=?",
                timestamp(effectiveTo), versionId);
    }

    private int next(String table, String idColumn, String id) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM " + table
                + " WHERE " + idColumn + "=?", Integer.class, id);
    }

    private static String formulaSelect() {
        return """
                SELECT i.formula_id,i.formula_code,v.*,
                       iu.unit_code AS input_unit_code,ru.unit_code AS result_unit_code
                FROM biz_energy_conversion_formula i
                JOIN biz_energy_conversion_formula_version v ON v.formula_id=i.formula_id
                JOIN biz_measurement_unit_version iuv ON iuv.version_id=v.applicable_input_unit_version_id
                JOIN biz_measurement_unit iu ON iu.unit_id=iuv.unit_id
                JOIN biz_measurement_unit_version ruv ON ruv.version_id=v.result_unit_version_id
                JOIN biz_measurement_unit ru ON ru.unit_id=ruv.unit_id
                """;
    }

    private static String parameterSelect() {
        return """
                SELECT p.parameter_id,p.parameter_code,p.energy_item_id,v.*,
                       ei.item_code,fi.formula_id,fi.formula_code,f.method,f.perspective,
                       f.algorithm_code,f.applicable_input_unit_version_id,
                       iu.unit_code AS input_unit_code,f.result_unit_version_id,
                       ru.unit_code AS result_unit_code
                FROM biz_energy_conversion_parameter p
                JOIN biz_energy_conversion_parameter_version v ON v.parameter_id=p.parameter_id
                JOIN biz_energy_item ei ON ei.item_id=p.energy_item_id
                JOIN biz_energy_conversion_formula_version f ON f.version_id=v.formula_version_id
                JOIN biz_energy_conversion_formula fi ON fi.formula_id=f.formula_id
                JOIN biz_measurement_unit_version iuv ON iuv.version_id=f.applicable_input_unit_version_id
                JOIN biz_measurement_unit iu ON iu.unit_id=iuv.unit_id
                JOIN biz_measurement_unit_version ruv ON ruv.version_id=f.result_unit_version_id
                JOIN biz_measurement_unit ru ON ru.unit_id=ruv.unit_id
                """;
    }

    private String optionalString(String sql, Object... args) {
        List<String> values = jdbc.query(sql, (rs, rowNum) -> rs.getString(1), args);
        return values.isEmpty() ? null : values.getFirst();
    }

    private <T> T optional(String sql, RowMapper<T> mapper, Object... args) {
        List<T> values = jdbc.query(sql, mapper, args);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static StandardCoalRow standardCoalRow(ResultSet rs, int ignored) throws SQLException {
        return new StandardCoalRow(rs.getString("lhv_id"), rs.getString("lhv_code"),
                rs.getString("version_id"), rs.getInt("version_no"), rs.getBigDecimal("lhv_value"),
                rs.getString("energy_unit_version_id"), rs.getString("coal_unit_version_id"),
                rs.getString("parameter_unit"), rs.getString("status"), rs.getString("source_type"),
                rs.getString("source_reference"), time(rs, "effective_from"), time(rs, "effective_to"),
                rs.getInt("config_revision"), rs.getLong("created_by"), time(rs, "created_at"),
                nullableLong(rs, "approved_by"), time(rs, "approved_at"));
    }

    private static FormulaRow formulaRow(ResultSet rs, int ignored) throws SQLException {
        return new FormulaRow(rs.getString("formula_id"), rs.getString("formula_code"),
                rs.getString("version_id"), rs.getInt("version_no"), rs.getString("method"),
                rs.getString("perspective"), rs.getString("algorithm_code"),
                rs.getString("applicable_input_unit_version_id"), rs.getString("input_unit_code"),
                rs.getString("result_unit_version_id"), rs.getString("result_unit_code"),
                rs.getString("parameter_unit"), rs.getString("status"), rs.getString("source_type"),
                rs.getString("source_reference"), time(rs, "effective_from"), time(rs, "effective_to"),
                rs.getInt("config_revision"), rs.getLong("created_by"), time(rs, "created_at"),
                nullableLong(rs, "approved_by"), time(rs, "approved_at"));
    }

    private static ParameterRow parameterRow(ResultSet rs, int ignored) throws SQLException {
        return new ParameterRow(rs.getString("parameter_id"), rs.getString("parameter_code"),
                rs.getString("version_id"), rs.getInt("version_no"), rs.getString("energy_item_id"),
                rs.getString("item_code"), rs.getString("energy_item_version_id"),
                rs.getString("formula_version_id"), rs.getString("formula_id"),
                rs.getString("formula_code"), rs.getString("method"), rs.getString("perspective"),
                rs.getString("algorithm_code"), rs.getString("applicable_input_unit_version_id"),
                rs.getString("input_unit_code"), rs.getString("result_unit_version_id"),
                rs.getString("result_unit_code"), rs.getBigDecimal("parameter_value"),
                rs.getString("parameter_unit"), rs.getString("standard_coal_lhv_version_id"),
                rs.getString("consumption_scope"), rs.getString("region_code"),
                rs.getString("usage_scope"), rs.getString("status"), rs.getString("source_type"),
                rs.getString("source_reference"), time(rs, "effective_from"), time(rs, "effective_to"),
                rs.getInt("config_revision"), rs.getLong("created_by"), time(rs, "created_at"),
                nullableLong(rs, "approved_by"), time(rs, "approved_at"));
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    record StandardCoalRow(
            String lhvId, String lhvCode, String versionId, int versionNo, BigDecimal value,
            String energyUnitVersionId, String coalUnitVersionId, String parameterUnit,
            String status, String sourceType, String sourceReference, LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo, int configRevision, long createdBy, LocalDateTime createdAt,
            Long approvedBy, LocalDateTime approvedAt) {
    }

    record FormulaRow(
            String formulaId, String formulaCode, String versionId, int versionNo, String method,
            String perspective, String algorithmCode, String inputUnitVersionId, String inputUnitCode,
            String resultUnitVersionId, String resultUnitCode, String parameterUnit, String status,
            String sourceType, String sourceReference, LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo, int configRevision, long createdBy, LocalDateTime createdAt,
            Long approvedBy, LocalDateTime approvedAt) {
    }

    record ParameterRow(
            String parameterId, String parameterCode, String versionId, int versionNo,
            String itemId, String itemCode, String energyItemVersionId, String formulaVersionId,
            String formulaId, String formulaCode, String method, String perspective,
            String algorithmCode, String inputUnitVersionId, String inputUnitCode,
            String resultUnitVersionId, String resultUnitCode, BigDecimal parameterValue,
            String parameterUnit, String standardCoalVersionId, String consumptionScope,
            String regionCode, String usageScope, String status, String sourceType,
            String sourceReference, LocalDateTime effectiveFrom, LocalDateTime effectiveTo,
            int configRevision, long createdBy, LocalDateTime createdAt,
            Long approvedBy, LocalDateTime approvedAt) {
    }
}
