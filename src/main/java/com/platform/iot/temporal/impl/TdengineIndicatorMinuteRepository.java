package com.platform.iot.temporal.impl;

import com.platform.config.TdengineProperties;
import com.platform.iot.formula.model.FormulaCalculation;
import com.platform.iot.formula.model.FormulaCalculationException;
import com.platform.iot.formula.model.IndicatorMinuteKey;
import com.platform.iot.formula.model.IndicatorMinuteResult;
import com.platform.iot.formula.model.FormulaCalculationAttempt;
import com.platform.iot.formula.model.IndicatorMinuteState;
import com.platform.iot.temporal.IndicatorMinuteRepository;
import com.platform.iot.temporal.model.IndicatorTrendQueryRow;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 使用专用 TDengine {@link JdbcTemplate} 持久化和查询公式分钟结果。
 *
 * <p>每个指标实例使用独立子表，来源分钟作为主时间戳，因此同一分钟补算会
 * 覆盖而不是追加重复成功值。成功与异常表保留既有事实，尝试表追加质量策略证据，
 * 状态表决定某指标分钟当前是否有效；本类不参与公式选择、权限判断或缓存更新。</p>
 */
@Repository
public class TdengineIndicatorMinuteRepository implements IndicatorMinuteRepository {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private final JdbcTemplate template;
    private final TdengineProperties properties;
    private final Map<String, Long> latestAttemptTimestamps = new ConcurrentHashMap<>();
    private final Set<String> persistedAttemptIds = ConcurrentHashMap.newKeySet();

    public TdengineIndicatorMinuteRepository(
            // 必须显式选择 TDengine 数据源，避免多数据源环境误用 MySQL。
            @Qualifier("taosJdbcTemplate") JdbcTemplate template,
            TdengineProperties properties) {
        this.template = template;
        this.properties = properties;
    }

    /**
     * 按指标子表批量写成功分钟；来源分钟是主时间戳，同键补算覆盖旧值而不追加重复行。
     */
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

    /**
     * 把非成功计算写入独立异常子表，保留原因和缺失输入而不污染成功趋势序列。
     */
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

    /**
     * 追加计算尝试；同一指标的物理时间戳在单实例内严格递增，attemptId 作为业务幂等证据。
     */
    @Override
    public synchronized void saveAttempts(List<FormulaCalculationAttempt> attempts) {
        if (attempts.isEmpty()) {
            return;
        }
        String stable = attemptStable();
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        int appended = 0;
        for (FormulaCalculationAttempt attempt : attempts) {
            if (persistedAttemptIds.contains(attempt.attemptId())
                    || attemptExists(attempt.attemptId())) {
                persistedAttemptIds.add(attempt.attemptId());
                continue;
            }
            long timestamp = nextAttemptTimestamp(attempt);
            if (attempt.policyEvidenceJson() != null
                    && attempt.policyEvidenceJson().length() > 2_048) {
                throw new IllegalArgumentException("Policy evidence exceeds TDengine boundary");
            }
            sql.append(attemptChild(attempt.indicatorId())).append(" USING ").append(stable)
                    .append(" (indicator_id,indicator_code,building_id,system_group_id,equip_id)")
                    .append(" TAGS (")
                    .append(quote(attempt.indicatorId())).append(",")
                    .append(quote(attempt.indicatorCode())).append(",")
                    .append(quote(attempt.buildingId())).append(",")
                    .append(nullableQuote(attempt.systemGroupId())).append(",")
                    .append(nullableQuote(attempt.equipId())).append(") ")
                    .append("(ts,attempt_id,minute_start,calc_status,reason_code,scenario_code,")
                    .append("formula_version,policy_evidence_json,config_revision) VALUES (")
                    .append(timestamp(timestamp)).append(",")
                    .append(quote(attempt.attemptId())).append(",")
                    .append(timestamp(attempt.minuteStart())).append(",")
                    .append(quote(attempt.calcStatus())).append(",")
                    .append(nullableQuote(attempt.reasonCode())).append(",")
                    .append(quote(attempt.scenarioCode())).append(",")
                    .append(quote(attempt.formulaVersion())).append(",")
                    .append(nullableQuote(attempt.policyEvidenceJson())).append(",")
                    .append(attempt.configRevision()).append(") ");
            persistedAttemptIds.add(attempt.attemptId());
            appended++;
        }
        if (appended > 0) {
            try {
                template.execute(sql.toString().trim());
            } catch (RuntimeException exception) {
                attempts.forEach(attempt -> persistedAttemptIds.remove(attempt.attemptId()));
                throw exception;
            }
        }
    }

    private boolean attemptExists(String attemptId) {
        String sql = "SELECT COUNT(*) FROM " + attemptStable()
                + " WHERE attempt_id=" + quote(attemptId);
        Long count = template.queryForObject(sql, Long.class);
        return count != null && count > 0;
    }

    private long nextAttemptTimestamp(FormulaCalculationAttempt attempt) {
        Long persistedMaximum = latestAttemptTimestamps.get(attempt.indicatorId());
        if (persistedMaximum == null) {
            Timestamp maximumTimestamp = template.queryForObject(
                    "SELECT MAX(ts) FROM " + attemptStable()
                            + " WHERE indicator_id=" + quote(attempt.indicatorId()),
                    Timestamp.class);
            persistedMaximum = maximumTimestamp == null ? null : maximumTimestamp.getTime();
        }
        long minimum = persistedMaximum == null ? Long.MIN_VALUE : persistedMaximum + 1;
        long next = Math.max(attempt.attemptedAt(), minimum);
        latestAttemptTimestamps.put(attempt.indicatorId(), next);
        return next;
    }

    /** 当前状态以来源分钟为主键覆盖更新，历史成功和尝试事实不受影响。 */
    @Override
    public void saveStates(List<IndicatorMinuteState> states) {
        if (states.isEmpty()) {
            return;
        }
        String stable = stateStable();
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        for (IndicatorMinuteState state : states) {
            sql.append(stateChild(state.indicatorId())).append(" USING ").append(stable)
                    .append(" (indicator_id,indicator_code,building_id,system_group_id,equip_id)")
                    .append(" TAGS (")
                    .append(quote(state.indicatorId())).append(",")
                    .append(quote(state.indicatorCode())).append(",")
                    .append(quote(state.buildingId())).append(",")
                    .append(nullableQuote(state.systemGroupId())).append(",")
                    .append(nullableQuote(state.equipId())).append(") ")
                    .append("(ts,current_status,source_fact_id,attempt_id,state_updated_at,config_revision)")
                    .append(" VALUES (")
                    .append(timestamp(state.minuteStart())).append(",")
                    .append(quote(state.currentStatus())).append(",")
                    .append(nullableQuote(state.sourceFactId())).append(",")
                    .append(quote(state.attemptId())).append(",")
                    .append(timestamp(state.stateUpdatedAt())).append(",")
                    .append(state.configRevision()).append(") ");
        }
        template.execute(sql.toString().trim());
    }

    @Override
    public Map<IndicatorMinuteKey, IndicatorMinuteState> findStates(
            Set<IndicatorMinuteKey> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        String predicates = keys.stream()
                .sorted(Comparator.comparing(IndicatorMinuteKey::indicatorId)
                        .thenComparingLong(IndicatorMinuteKey::minuteStart))
                .map(key -> "(indicator_id=" + quote(key.indicatorId())
                        + " AND ts=" + timestamp(key.minuteStart()) + ")")
                .collect(Collectors.joining(" OR "));
        String sql = "SELECT indicator_id,indicator_code,building_id,system_group_id,equip_id,"
                + "ts,current_status,source_fact_id,attempt_id,state_updated_at,config_revision FROM "
                + stateStable() + " WHERE " + predicates;
        return template.query(sql, (rs, row) -> mapState(rs)).stream()
                .collect(Collectors.toUnmodifiableMap(
                        state -> new IndicatorMinuteKey(state.indicatorId(), state.minuteStart()),
                        state -> state,
                        (left, right) -> left.stateUpdatedAt() >= right.stateUpdatedAt()
                                ? left : right));
    }

    @Override
    public Map<String, IndicatorMinuteState> findLatestStates(List<String> indicatorIds) {
        if (indicatorIds.isEmpty()) {
            return Map.of();
        }
        String sql = "SELECT indicator_id,indicator_code,building_id,system_group_id,equip_id,"
                + "LAST_ROW(ts) AS ts,LAST_ROW(current_status) AS current_status,"
                + "LAST_ROW(source_fact_id) AS source_fact_id,LAST_ROW(attempt_id) AS attempt_id,"
                + "LAST_ROW(state_updated_at) AS state_updated_at,"
                + "LAST_ROW(config_revision) AS config_revision FROM " + stateStable()
                + " WHERE indicator_id IN (" + values(indicatorIds) + ")"
                + latestIdentityPartition();
        return template.query(sql, (rs, row) -> mapState(rs)).stream()
                .collect(Collectors.toUnmodifiableMap(
                        IndicatorMinuteState::indicatorId,
                        state -> state,
                        (left, right) -> left.minuteStart() >= right.minuteStart()
                                ? left : right));
    }

    /** 只删除调用方给出的“指标 + 来源分钟”成功键，质量修正时不会扩大到整段历史。 */
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

    /** 从成功超级表读取一个精确指标分钟；不存在表示该分钟没有成功公式结果。 */
    @Override
    public Optional<IndicatorMinuteResult> findSuccess(String indicatorId, long minuteStart) {
        String sql = successSelect()
                + " WHERE indicator_id=" + quote(indicatorId)
                + " AND ts=" + timestamp(minuteStart)
                + " LIMIT 1";
        return template.query(sql, this::mapSuccess).stream().findFirst();
    }

    /** 从异常超级表读取一个精确指标分钟的最后一次失败尝试。 */
    @Override
    public Optional<FormulaCalculationException> findException(
            String indicatorId, long minuteStart) {
        String sql = exceptionSelect()
                + " WHERE indicator_id=" + quote(indicatorId)
                + " AND ts=" + timestamp(minuteStart)
                + " ORDER BY calculated_at DESC LIMIT 1";
        return template.query(sql, this::mapException).stream().findFirst();
    }

    /**
     * 使用 TDengine {@code LAST_ROW} 按完整指标身份取各指标最新成功值，供缓存回源使用。
     */
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

    /** 使用与成功表一致的身份分区读取最新失败，避免缓存回源漏掉当前异常指标。 */
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

    /** 按 {@code [fromInclusive,toExclusive)} 返回单指标成功趋势，并按来源分钟升序排列。 */
    @Override
    public List<IndicatorMinuteResult> findHistory(
            String indicatorId, long fromInclusive, long toExclusive) {
        String sql = successSelect()
                + " WHERE indicator_id=" + quote(indicatorId)
                + " AND " + timeRange(fromInclusive, toExclusive)
                + " ORDER BY ts";
        return template.query(sql, this::mapSuccess);
    }

    /**
     * 一次读取多个指标的图表趋势，避免页面按指标发起 N+1 查询。
     *
     * <p>1 分钟直接读取成功值；5/30 分钟只在 TDengine 内聚合平均值、极值、样本数和
     * 最差质量。分辨率使用白名单，不能把调用方输入直接拼成 {@code INTERVAL}。</p>
     */
    @Override
    public List<IndicatorTrendQueryRow> findTrends(
            List<String> indicatorIds,
            long fromInclusive,
            long toExclusive,
            int resolutionMinutes) {
        if (indicatorIds.isEmpty()) {
            return List.of();
        }
        String interval = switch (resolutionMinutes) {
            case 1 -> null;
            case 5 -> "5m";
            case 30 -> "30m";
            default -> throw new IllegalArgumentException(
                    "指标趋势分辨率只允许 1、5、30 分钟");
        };
        String sql = interval == null
                ? rawTrendSql(indicatorIds, fromInclusive, toExclusive)
                : downsampledTrendSql(
                        indicatorIds, fromInclusive, toExclusive, interval);
        return template.query(sql, this::mapTrendRow);
    }

    /** 1 分钟趋势保留成功表原值，使图表点与精确审计分钟一致。 */
    private String rawTrendSql(
            List<String> indicatorIds, long fromInclusive, long toExclusive) {
        return """
                SELECT indicator_id,indicator_code,building_id,system_group_id,equip_id,
                       ts AS bucket_time,
                       val AS average_value,
                       val AS minimum_value,
                       val AS maximum_value,
                       1 AS sample_count,
                       data_quality
                FROM %s
                WHERE indicator_id IN (%s) AND %s
                ORDER BY indicator_id,ts
                """.formatted(
                indicatorStable(), values(indicatorIds),
                timeRange(fromInclusive, toExclusive));
    }

    /**
     * 5/30 分钟趋势按完整指标身份分区，避免不同指标实例在同一窗口被混合计算。
     */
    private String downsampledTrendSql(
            List<String> indicatorIds,
            long fromInclusive,
            long toExclusive,
            String interval) {
        return """
                SELECT indicator_id,indicator_code,building_id,system_group_id,equip_id,
                       _wstart AS bucket_time,
                       AVG(val) AS average_value,
                       MIN(val) AS minimum_value,
                       MAX(val) AS maximum_value,
                       COUNT(*) AS sample_count,
                       MAX(data_quality) AS data_quality
                FROM %s
                WHERE indicator_id IN (%s) AND %s
                PARTITION BY indicator_id,indicator_code,building_id,system_group_id,equip_id
                INTERVAL(%s)
                ORDER BY indicator_id,_wstart
                """.formatted(
                indicatorStable(), values(indicatorIds),
                timeRange(fromInclusive, toExclusive), interval);
    }

    /** 批量返回窗口内已有成功结果的精确键，恢复服务据此只补真正缺失的分钟。 */
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

    /**
     * 合并成功表与异常表的 {@code calculated_at}，返回每个精确键最后一次计算尝试时间。
     */
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
        collectV2AttemptTimes(keys, calculatedAt);
        return Map.copyOf(calculatedAt);
    }

    private void collectV2AttemptTimes(
            Set<IndicatorMinuteKey> keys,
            Map<IndicatorMinuteKey, Long> calculatedAt) {
        String predicates = keys.stream()
                .sorted(Comparator.comparing(IndicatorMinuteKey::indicatorId)
                        .thenComparingLong(IndicatorMinuteKey::minuteStart))
                .map(key -> "(indicator_id=" + quote(key.indicatorId())
                        + " AND minute_start=" + timestamp(key.minuteStart()) + ")")
                .collect(Collectors.joining(" OR "));
        String sql = "SELECT indicator_id,minute_start,ts FROM "
                + attemptStable() + " WHERE " + predicates
                + " ORDER BY indicator_id,minute_start,ts";
        template.query(sql, resultSet -> {
            IndicatorMinuteKey key = new IndicatorMinuteKey(
                    resultSet.getString("indicator_id"),
                    resultSet.getTimestamp("minute_start").getTime());
            calculatedAt.merge(
                    key, resultSet.getTimestamp("ts").getTime(), Math::max);
        });
    }

    /** 将一个超级表的尝试时间合并到结果，同键只保留较晚时间。 */
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

    /** 把精确分钟和降采样窗口映射为不含公式版本的图表读取模型。 */
    private IndicatorTrendQueryRow mapTrendRow(
            ResultSet resultSet, int rowNumber) throws SQLException {
        return new IndicatorTrendQueryRow(
                resultSet.getString("indicator_id"),
                resultSet.getString("indicator_code"),
                resultSet.getString("building_id"),
                resultSet.getString("system_group_id"),
                resultSet.getString("equip_id"),
                resultSet.getTimestamp("bucket_time").getTime(),
                resultSet.getDouble("average_value"),
                resultSet.getDouble("minimum_value"),
                resultSet.getDouble("maximum_value"),
                resultSet.getLong("sample_count"),
                resultSet.getInt("data_quality"));
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

    private String attemptStable() {
        return qualified(properties.getStFormulaCalcAttemptV2());
    }

    private String stateStable() {
        return qualified(properties.getStIndicatorMinuteState());
    }

    private String indicatorChild(String indicatorId) {
        return qualified(properties.getStIndicatorMinute() + "_" + safe(indicatorId));
    }

    private String exceptionChild(String indicatorId) {
        return qualified(properties.getStFormulaCalcException() + "_" + safe(indicatorId));
    }

    private String attemptChild(String indicatorId) {
        return qualified(properties.getStFormulaCalcAttemptV2() + "_" + safe(indicatorId));
    }

    private String stateChild(String indicatorId) {
        return qualified(properties.getStIndicatorMinuteState() + "_" + safe(indicatorId));
    }

    private IndicatorMinuteState mapState(ResultSet resultSet) throws SQLException {
        return new IndicatorMinuteState(
                resultSet.getString("indicator_id"),
                resultSet.getString("indicator_code"),
                resultSet.getString("building_id"),
                resultSet.getString("system_group_id"),
                resultSet.getString("equip_id"),
                resultSet.getTimestamp("ts").getTime(),
                resultSet.getString("current_status"),
                resultSet.getString("source_fact_id"),
                resultSet.getString("attempt_id"),
                resultSet.getTimestamp("state_updated_at").getTime(),
                resultSet.getLong("config_revision"));
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
