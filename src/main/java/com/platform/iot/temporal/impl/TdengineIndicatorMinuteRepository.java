package com.platform.iot.temporal.impl;

import com.platform.config.TdengineProperties;
import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.formula.model.FormulaCalculationException;
import com.platform.iot.formula.model.IndicatorMinuteKey;
import com.platform.iot.formula.model.IndicatorMinuteResult;
import com.platform.iot.temporal.IndicatorMinuteRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 使用专用 TDengine {@link JdbcTemplate} 持久化和查询公式分钟结果。
 *
 * <p>每个指标实例使用独立子表，来源分钟作为主时间戳，因此同一分钟补算会
 * 覆盖而不是追加重复成功值。成功表服务趋势查询，异常表保留缺失项和失败原因；
 * 本类只负责时序数据访问，不参与公式选择、权限判断或缓存更新。</p>
 */
@Repository
public class TdengineIndicatorMinuteRepository implements IndicatorMinuteRepository {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private final JdbcTemplate template;
    private final TdengineProperties properties;

    public TdengineIndicatorMinuteRepository(
            // 必须显式选择 TDengine 数据源，避免多数据源环境误用 MySQL。
            @Qualifier("taosJdbcTemplate") JdbcTemplate template,
            TdengineProperties properties) {
        this.template = template;
        this.properties = properties;
    }

    @Override
    public void saveSuccesses(List<IndicatorMinuteResult> results) {
        if (results.isEmpty()) {
            return;
        }
        // 使用一条 TDengine INSERT 写入多个子表，降低每分钟多指标的网络往返。
        String stable = indicatorStable();
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        for (IndicatorMinuteResult result : results) {
            if (!Double.isFinite(result.value())) {
                throw new IllegalArgumentException("Indicator value must be finite");
            }
            sql.append(indicatorChild(result.indicatorId())).append(" USING ").append(stable)
                    .append(" (indicator_id,indicator_code,building_id,system_group_id,equip_id)")
                    .append(" TAGS (")
                    .append(quote(result.indicatorId())).append(",")
                    .append(quote(result.indicatorCode())).append(",")
                    .append(quote(result.buildingId())).append(",")
                    .append(nullableQuote(result.systemGroupId())).append(",")
                    .append(nullableQuote(result.equipId())).append(") ")
                    .append("(ts,val,data_quality,formula_version,calculated_at) VALUES (")
                    .append(timestamp(result.minuteStart())).append(",")
                    .append(number(result.value())).append(",")
                    .append(result.dataQuality()).append(",")
                    .append(quote(result.formulaVersion())).append(",")
                    .append(timestamp(result.calculatedAt())).append(") ");
        }
        template.execute(sql.toString().trim());
    }

    @Override
    public void saveExceptions(List<FormulaCalculationException> exceptions) {
        if (exceptions.isEmpty()) {
            return;
        }
        // 异常与成功结果分表，失败重试不会污染用于趋势图的成功序列。
        String stable = exceptionStable();
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        for (FormulaCalculationException exception : exceptions) {
            if (exception.status() == null
                    || exception.status() == FormulaCalculation.Status.SUCCESS) {
                throw new IllegalArgumentException(
                        "Formula exception status must be a failure status");
            }
            sql.append(exceptionChild(exception.indicatorId())).append(" USING ").append(stable)
                    .append(" (indicator_id,indicator_code,building_id,system_group_id,equip_id)")
                    .append(" TAGS (")
                    .append(quote(exception.indicatorId())).append(",")
                    .append(quote(exception.indicatorCode())).append(",")
                    .append(quote(exception.buildingId())).append(",")
                    .append(nullableQuote(exception.systemGroupId())).append(",")
                    .append(nullableQuote(exception.equipId())).append(") ")
                    .append("(ts,calc_status,reason_code,missing_inputs,formula_version,calculated_at)")
                    .append(" VALUES (")
                    .append(timestamp(exception.minuteStart())).append(",")
                    .append(quote(exception.status().name())).append(",")
                    .append(quote(exception.reasonCode())).append(",")
                    .append(nullableQuote(joinMissingInputs(exception.missingInputs()))).append(",")
                    .append(quote(exception.formulaVersion())).append(",")
                    .append(timestamp(exception.calculatedAt())).append(") ");
        }
        template.execute(sql.toString().trim());
    }

    @Override
    public void deleteSuccesses(Set<IndicatorMinuteKey> keys) {
        if (keys.isEmpty()) {
            return;
        }
        // 每条语句同时锁定指标子表和来源分钟，避免范围删除误伤其他历史结果。
        String[] statements = keys.stream()
                .sorted(Comparator
                        .comparing(IndicatorMinuteKey::indicatorId)
                        .thenComparingLong(IndicatorMinuteKey::minuteStart))
                .map(key -> "DELETE FROM " + indicatorChild(key.indicatorId())
                        + " WHERE ts=" + timestamp(key.minuteStart()))
                .toArray(String[]::new);
        template.batchUpdate(statements);
    }

    @Override
    public Optional<IndicatorMinuteResult> findSuccess(String indicatorId, long minuteStart) {
        String sql = successSelect()
                + " WHERE indicator_id=" + quote(indicatorId)
                + " AND ts=" + timestamp(minuteStart)
                + " LIMIT 1";
        return template.query(sql, this::mapSuccess).stream().findFirst();
    }

    @Override
    public Optional<FormulaCalculationException> findException(
            String indicatorId, long minuteStart) {
        String sql = exceptionSelect()
                + " WHERE indicator_id=" + quote(indicatorId)
                + " AND ts=" + timestamp(minuteStart)
                + " ORDER BY calculated_at DESC LIMIT 1";
        return template.query(sql, this::mapException).stream().findFirst();
    }

    @Override
    public List<IndicatorMinuteResult> findLatestSuccesses(List<String> indicatorIds) {
        if (indicatorIds.isEmpty()) {
            return List.of();
        }
        // TDengine 3.2.3 对超级表分区后再排序截断可能只返回一个分区；
        // LAST_ROW 按完整指标身份取值；正常数据中指标 ID 与该身份一一对应。
        String sql = latestSuccessSelect()
                + " WHERE indicator_id IN (" + values(indicatorIds) + ")"
                + latestIdentityPartition();
        return template.query(sql, this::mapSuccess).stream()
                .sorted(Comparator.comparing(IndicatorMinuteResult::indicatorId))
                .toList();
    }

    @Override
    public List<FormulaCalculationException> findLatestExceptions(List<String> indicatorIds) {
        if (indicatorIds.isEmpty()) {
            return List.of();
        }
        // 异常表必须使用与成功表相同的取最新规则，否则 Redis 过期后仍可能漏掉失败指标。
        String sql = latestExceptionSelect()
                + " WHERE indicator_id IN (" + values(indicatorIds) + ")"
                + latestIdentityPartition();
        return template.query(sql, this::mapException).stream()
                .sorted(Comparator.comparing(FormulaCalculationException::indicatorId))
                .toList();
    }

    @Override
    public List<IndicatorMinuteResult> findHistory(
            String indicatorId, long fromInclusive, long toExclusive) {
        String sql = successSelect()
                + " WHERE indicator_id=" + quote(indicatorId)
                + " AND " + timeRange(fromInclusive, toExclusive)
                + " ORDER BY ts";
        return template.query(sql, this::mapSuccess);
    }

    @Override
    public Set<IndicatorMinuteKey> findSuccessfulKeys(
            List<String> indicatorIds, long fromInclusive, long toExclusive) {
        if (indicatorIds.isEmpty()) {
            return Set.of();
        }
        String sql = "SELECT indicator_id,ts FROM " + indicatorStable()
                + " WHERE indicator_id IN (" + values(indicatorIds) + ")"
                + " AND " + timeRange(fromInclusive, toExclusive)
                + " ORDER BY indicator_id, ts";
        return new LinkedHashSet<>(template.query(sql, (resultSet, rowNumber) ->
                new IndicatorMinuteKey(
                        resultSet.getString("indicator_id"),
                        resultSet.getTimestamp("ts").getTime())));
    }

    @Override
    public Map<IndicatorMinuteKey, Long> findLatestAttemptAt(
            Set<IndicatorMinuteKey> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        String predicates = keys.stream()
                .sorted(Comparator
                        .comparing(IndicatorMinuteKey::indicatorId)
                        .thenComparingLong(IndicatorMinuteKey::minuteStart))
                .map(key -> "(indicator_id=" + quote(key.indicatorId())
                        + " AND ts=" + timestamp(key.minuteStart()) + ")")
                .collect(Collectors.joining(" OR "));
        Map<IndicatorMinuteKey, Long> calculatedAt = new LinkedHashMap<>();
        collectAttemptTimes(indicatorStable(), predicates, calculatedAt);
        collectAttemptTimes(exceptionStable(), predicates, calculatedAt);
        return Map.copyOf(calculatedAt);
    }

    private void collectAttemptTimes(
            String stable,
            String predicates,
            Map<IndicatorMinuteKey, Long> calculatedAt) {
        String sql = "SELECT indicator_id,ts,calculated_at FROM "
                + stable + " WHERE " + predicates
                + " ORDER BY indicator_id,ts";
        template.query(sql, resultSet -> {
            IndicatorMinuteKey key = new IndicatorMinuteKey(
                    resultSet.getString("indicator_id"),
                    resultSet.getTimestamp("ts").getTime());
            calculatedAt.merge(
                    key,
                    resultSet.getTimestamp("calculated_at").getTime(),
                    Math::max);
        });
    }

    private IndicatorMinuteResult mapSuccess(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new IndicatorMinuteResult(
                resultSet.getString("indicator_id"),
                resultSet.getString("indicator_code"),
                resultSet.getString("building_id"),
                resultSet.getString("system_group_id"),
                resultSet.getString("equip_id"),
                resultSet.getTimestamp("ts").getTime(),
                resultSet.getDouble("val"),
                resultSet.getInt("data_quality"),
                resultSet.getString("formula_version"),
                resultSet.getTimestamp("calculated_at").getTime());
    }

    private FormulaCalculationException mapException(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new FormulaCalculationException(
                resultSet.getString("indicator_id"),
                resultSet.getString("indicator_code"),
                resultSet.getString("building_id"),
                resultSet.getString("system_group_id"),
                resultSet.getString("equip_id"),
                resultSet.getTimestamp("ts").getTime(),
                FormulaCalculation.Status.valueOf(resultSet.getString("calc_status")),
                resultSet.getString("reason_code"),
                splitMissingInputs(resultSet.getString("missing_inputs")),
                resultSet.getString("formula_version"),
                resultSet.getTimestamp("calculated_at").getTime());
    }

    private String successSelect() {
        return "SELECT indicator_id,indicator_code,building_id,system_group_id,equip_id,"
                + "ts,val,data_quality,formula_version,calculated_at FROM " + indicatorStable();
    }

    private String exceptionSelect() {
        return "SELECT indicator_id,indicator_code,building_id,system_group_id,equip_id,"
                + "ts,calc_status,reason_code,missing_inputs,formula_version,calculated_at FROM "
                + exceptionStable();
    }

    private String latestSuccessSelect() {
        return "SELECT indicator_id,indicator_code,building_id,system_group_id,equip_id,"
                + "LAST_ROW(ts) AS ts,LAST_ROW(val) AS val,"
                + "LAST_ROW(data_quality) AS data_quality,"
                + "LAST_ROW(formula_version) AS formula_version,"
                + "LAST_ROW(calculated_at) AS calculated_at FROM " + indicatorStable();
    }

    private String latestExceptionSelect() {
        return "SELECT indicator_id,indicator_code,building_id,system_group_id,equip_id,"
                + "LAST_ROW(ts) AS ts,LAST_ROW(calc_status) AS calc_status,"
                + "LAST_ROW(reason_code) AS reason_code,"
                + "LAST_ROW(missing_inputs) AS missing_inputs,"
                + "LAST_ROW(formula_version) AS formula_version,"
                + "LAST_ROW(calculated_at) AS calculated_at FROM " + exceptionStable();
    }

    private String latestIdentityPartition() {
        return " PARTITION BY indicator_id,indicator_code,building_id,"
                + "system_group_id,equip_id";
    }

    private String indicatorStable() {
        return qualified(properties.getStIndicatorMinute());
    }

    private String exceptionStable() {
        return qualified(properties.getStFormulaCalcException());
    }

    private String indicatorChild(String indicatorId) {
        return qualified(properties.getStIndicatorMinute() + "_" + safe(indicatorId));
    }

    private String exceptionChild(String indicatorId) {
        return qualified(properties.getStFormulaCalcException() + "_" + safe(indicatorId));
    }

    private String qualified(String identifier) {
        return safe(properties.getDatabase()) + "." + safe(identifier);
    }

    private String values(List<String> values) {
        return values.stream().distinct().sorted().map(this::quote)
                .collect(Collectors.joining(","));
    }

    private String timeRange(long fromInclusive, long toExclusive) {
        return "ts>=" + timestamp(fromInclusive) + " AND ts<" + timestamp(toExclusive);
    }

    private String timestamp(long epochMillis) {
        return quote(new Timestamp(epochMillis).toString());
    }

    private String number(double value) {
        return Double.toString(value);
    }

    private String joinMissingInputs(List<String> missingInputs) {
        return missingInputs.isEmpty() ? null : String.join(",", missingInputs);
    }

    private List<String> splitMissingInputs(String missingInputs) {
        return missingInputs == null || missingInputs.isBlank()
                ? List.of()
                : List.of(missingInputs.split(",", -1));
    }

    private String safe(String value) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Illegal TDengine identifier: " + value);
        }
        return value;
    }

    private String quote(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Required SQL value must not be null");
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private String nullableQuote(String value) {
        return value == null ? "NULL" : quote(value);
    }
}
