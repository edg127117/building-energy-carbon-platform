package com.platform.iot.temporal.impl;

import com.platform.config.TdengineProperties;
import com.platform.iot.dataquality.MinuteQualityLockRegistry;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.HvacMinuteQueryRow;
import com.platform.iot.temporal.model.MinuteQualityWriteResult;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 使用专用 TDengine 数据源读写 HVAC 正式分钟和降采样趋势。
 *
 * <p>正常聚合写入 Q0，质量补全写入 Q1/Q2；本仓储在“测点 + 分钟”进程锁内比较
 * 已有质量和任务所有权，阻止生成数据覆盖真实值。公式、质量恢复和查询 API 都只读取
 * 已冻结的 {@code st_raw_minute}，不会在各自流程中重复解释逐条事件。</p>
 *
 * <p>每个测点使用独立子表，分钟起点是行时间戳；批量最新值使用 {@code LAST_ROW}
 * 按完整测点身份分区，5/30 分钟趋势在 TDengine 侧加权降采样。数据库名和表名按
 * 标识符白名单校验，测点与建筑 ID 则作为 SQL 值转义，避免混淆两类输入边界。</p>
 */
@Slf4j
@Repository
public class TdengineHvacMinuteRepository implements HvacMinuteRepository {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private final JdbcTemplate template;
    private final TdengineProperties properties;
    private final MinuteQualityLockRegistry lockRegistry;
    private final Set<String> identityTagChecked = ConcurrentHashMap.newKeySet();

    @Autowired
    public TdengineHvacMinuteRepository(
            @Qualifier("taosJdbcTemplate") JdbcTemplate template,
            TdengineProperties properties,
            MinuteQualityLockRegistry lockRegistry) {
        this.template = template;
        this.properties = properties;
        this.lockRegistry = lockRegistry;
    }

    /**
     * 测试和非 Spring 场景使用独立锁注册表；生产环境由 Spring 注入全局单例。
     */
    public TdengineHvacMinuteRepository(
            JdbcTemplate template, TdengineProperties properties) {
        this(template, properties, new MinuteQualityLockRegistry());
    }

    @Override
    public Set<String> findExistingPointIds(long minuteStart) {
        String stable = safe(properties.getDatabase()) + "." + safe(properties.getStRawMinute());
        // 恢复时一次读取该分钟全部已完成测点，替代逐子表执行 COUNT(*)。
        List<String> pointIds = template.queryForList(
                "SELECT point_id FROM " + stable + " WHERE ts="
                        + quote(new Timestamp(minuteStart).toString()),
                String.class);
        return new LinkedHashSet<>(pointIds);
    }

    @Override
    public List<RawMinuteAggregate> findByMinute(
            long minuteStart, Set<String> buildingIds) {
        if (buildingIds.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT point_id, point_code, building_id, system_group_id, equip_id,
                       equip_code, family_code, component_code, suffix_code, is_for_calc,
                       ts, avg_val, min_val, max_val, sample_count, data_quality,
                       first_received_time, last_received_time, finalized_at, quality_task_id
                FROM %s
                WHERE ts = %s
                  AND building_id IN (%s)
                ORDER BY building_id, point_id
                """.formatted(
                stable(),
                quote(new Timestamp(minuteStart).toString()),
                buildingIdIn(buildingIds));
        return template.query(sql, this::mapAggregate);
    }

    @Override
    public List<HvacMinuteQueryRow> findLatestByPointIds(List<String> pointIds) {
        if (pointIds.isEmpty()) {
            return List.of();
        }
        String stable = stable();
        // TDengine 3.2.3 的普通 LIMIT 会限制整批分区结果，并不是“每个分区一条”。
        // 使用 LAST_ROW 按完整测点身份聚合，才能一次批量返回每个测点的最新分钟值。
        String sql = """
                SELECT point_id,
                       LAST_ROW(ts) AS bucket_time,
                       LAST_ROW(avg_val) AS average_value,
                       LAST_ROW(min_val) AS minimum_value,
                       LAST_ROW(max_val) AS maximum_value,
                       LAST_ROW(sample_count) AS sample_count,
                       LAST_ROW(data_quality) AS data_quality
                FROM %s
                WHERE point_id IN (%s)
                PARTITION BY point_id, point_code, building_id, system_group_id,
                             equip_id, equip_code, family_code, component_code,
                             suffix_code, is_for_calc
                """.formatted(stable, pointIdIn(pointIds));
        return template.query(sql, this::mapQueryRow);
    }

    @Override
    public List<HvacMinuteQueryRow> findHistory(
            List<String> pointIds,
            long fromInclusive,
            long toExclusive,
            int resolutionMinutes) {
        if (pointIds.isEmpty()) {
            return List.of();
        }
        String interval = switch (resolutionMinutes) {
            case 1 -> null;
            case 5 -> "5m";
            case 30 -> "30m";
            // 分辨率由 Service 根据查询跨度选择；Repository 再做一次白名单校验，
            // 避免任意字符串进入 INTERVAL 子句。
            default -> throw new IllegalArgumentException("分辨率只允许 1、5、30 分钟");
        };
        String sql = interval == null
                ? rawHistorySql(pointIds, fromInclusive, toExclusive)
                : downsampledHistorySql(
                        pointIds, fromInclusive, toExclusive, interval);
        return template.query(sql, this::mapQueryRow);
    }

    @Override
    public List<MinuteQualityWriteResult> saveAllWithQualityPriority(
            List<RawMinuteAggregate> aggregates, String supersedesTaskId) {
        if (aggregates.isEmpty()) {
            return List.of();
        }
        validateBatch(aggregates);
        List<RawMinuteAggregate> batch = List.copyOf(aggregates);
        List<MinuteQualityLockRegistry.MinuteKey> keys = batch.stream()
                .map(row -> new MinuteQualityLockRegistry.MinuteKey(
                        row.pointId(), row.minuteStart()))
                .toList();
        return lockRegistry.withLocks(keys,
                () -> compareAndWrite(batch, supersedesTaskId));
    }

    @Override
    public Optional<RawMinuteAggregate> findPointMinute(
            String pointId, long minuteStart) {
        String sql = aggregateSelect()
                + " WHERE point_id=" + quote(pointId)
                + " AND ts=" + quote(new Timestamp(minuteStart).toString());
        return template.query(sql, this::mapAggregate).stream().findFirst();
    }

    @Override
    public List<RawMinuteAggregate> findRange(
            Set<String> pointIds, long fromInclusive, long toExclusive) {
        if (pointIds.isEmpty()) {
            return List.of();
        }
        List<String> orderedPointIds = pointIds.stream().sorted().toList();
        String sql = aggregateSelect()
                + " WHERE point_id IN (" + pointIdIn(orderedPointIds) + ") AND "
                + timeRange(fromInclusive, toExclusive)
                + " ORDER BY point_id,ts";
        return template.query(sql, this::mapAggregate);
    }

    @Override
    public List<RawMinuteAggregate> findByQualityTaskId(String qualityTaskId) {
        String sql = aggregateSelect()
                + " WHERE quality_task_id=" + quote(qualityTaskId)
                + " ORDER BY point_id,ts";
        return template.query(sql, this::mapAggregate);
    }

    @Override
    public List<RawMinuteAggregate> findByQualityTaskId(
            String qualityTaskId,
            String pointId,
            long fromInclusive,
            long toExclusive,
            int limit) {
        if (limit <= 0 || limit > 61) {
            throw new IllegalArgumentException("任务分钟查询 limit 必须在 1 到 61 之间");
        }
        String sql = aggregateSelect()
                + " WHERE quality_task_id=" + quote(qualityTaskId)
                + " AND point_id=" + quote(pointId)
                + " AND " + timeRange(fromInclusive, toExclusive)
                + " ORDER BY ts LIMIT " + limit;
        return template.query(sql, this::mapAggregate);
    }

    @Override
    public List<RawMinuteAggregate> findLateRealMinutes(
            long fromInclusive,
            long toExclusive,
            Long afterMinuteStart,
            String afterPointId,
            int normalFinalizationDelaySeconds,
            int limit) {
        if (normalFinalizationDelaySeconds < 0) {
            throw new IllegalArgumentException(
                    "normalFinalizationDelaySeconds 不能为负数");
        }
        if (limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("limit 必须在 1 到 100 之间");
        }
        if ((afterMinuteStart == null) != (afterPointId == null)) {
            throw new IllegalArgumentException(
                    "迟到 Q0 游标的分钟和测点必须同时提供");
        }
        long normalBoundarySeconds = Math.addExact(
                60L, normalFinalizationDelaySeconds);
        String seek = afterMinuteStart == null ? "" :
                " AND (ts > "
                        + quote(new Timestamp(afterMinuteStart).toString())
                        + " OR (ts = "
                        + quote(new Timestamp(afterMinuteStart).toString())
                        + " AND point_id > " + quote(afterPointId) + "))";
        // 正式 Q0 使用独立 seek 游标扫描；业务服务随后批量核验精确 point+minute
        // 确实存在 raw late 证据，避免把普通停机恢复误判为迟到修正。
        String sql = aggregateSelect()
                + " WHERE " + timeRange(fromInclusive, toExclusive)
                + " AND data_quality=0"
                + " AND finalized_at > ts + " + normalBoundarySeconds + "s"
                + seek
                + " ORDER BY ts,point_id LIMIT " + limit;
        return template.query(sql, this::mapAggregate);
    }

    @Override
    public boolean deleteIfOwnedByTask(
            String pointId, long minuteStart, String taskId) {
        MinuteQualityLockRegistry.MinuteKey key =
                new MinuteQualityLockRegistry.MinuteKey(pointId, minuteStart);
        return lockRegistry.withLocks(List.of(key), () -> {
            Optional<RawMinuteAggregate> current =
                    findPointMinute(pointId, minuteStart);
            if (current.map(RawMinuteAggregate::qualityTaskId)
                    .filter(taskId::equals).isEmpty()) {
                return false;
            }
            // TDengine 的 DELETE 以时间条件最稳定；先在同一进程锁内核对任务所有权，
            // 再按子表时间戳删除，避免依赖普通数据列删除条件的版本兼容性。
            template.execute("DELETE FROM " + qualifiedChild(pointId)
                    + " WHERE ts="
                    + quote(new Timestamp(minuteStart).toString()));
            return true;
        });
    }

    /**
     * 在全部目标键已加锁后一次读取现状、判定每行结果，并用单条多子表 INSERT 写入
     * 被接受的分钟。批量预读避免逐测点 N+1，也让本轮所有质量决策基于同一份快照。
     */
    private List<MinuteQualityWriteResult> compareAndWrite(
            List<RawMinuteAggregate> ordered, String supersedesTaskId) {
        List<RawMinuteAggregate> currentRows = readCurrentBatch(ordered);
        Map<MinuteKey, RawMinuteAggregate> currentByKey = new LinkedHashMap<>();
        currentRows.forEach(row -> currentByKey.put(
                new MinuteKey(row.pointId(), row.minuteStart()), row));

        List<RawMinuteAggregate> accepted = new ArrayList<>();
        List<MinuteQualityWriteResult> results = new ArrayList<>();
        for (RawMinuteAggregate incoming : ordered) {
            RawMinuteAggregate current = currentByKey.get(
                    new MinuteKey(incoming.pointId(), incoming.minuteStart()));
            MinuteQualityWriteResult result =
                    decide(incoming, current, supersedesTaskId);
            results.add(result);
            if (isWritable(result.outcome())) {
                accepted.add(incoming);
            }
        }
        if (!accepted.isEmpty()) {
            accepted.forEach(this::ensureExistingChildBuildingTag);
            insertBatch(accepted);
        }
        return List.copyOf(results);
    }

    private List<RawMinuteAggregate> readCurrentBatch(
            List<RawMinuteAggregate> aggregates) {
        String predicates = aggregates.stream()
                .map(row -> "(point_id=" + quote(row.pointId())
                        + " AND ts=" + quote(new Timestamp(
                                row.minuteStart()).toString()) + ")")
                .collect(Collectors.joining(" OR "));
        // 质量判断必须基于同一个数据库快照；整批只查一次，禁止逐测点 N+1。
        return template.query(aggregateSelect() + " WHERE " + predicates,
                this::mapAggregate);
    }

    /**
     * 应用分钟质量覆盖规则：Q0 可刷新旧 Q0，较小质量数字可升级较差数据；Q1/Q2
     * 同质量只能幂等重试或显式替代指定旧任务，不能互相静默覆盖。
     */
    private MinuteQualityWriteResult decide(
            RawMinuteAggregate incoming,
            RawMinuteAggregate current,
            String supersedesTaskId) {
        if (current == null) {
            return result(incoming, MinuteQualityWriteResult.Outcome.INSERTED, null);
        }
        if (incoming.qualityTaskId() != null
                && incoming.qualityTaskId().equals(current.qualityTaskId())) {
            return result(incoming, MinuteQualityWriteResult.Outcome.IDEMPOTENT, current);
        }
        if (incoming.dataQuality() < current.dataQuality()) {
            return result(incoming, MinuteQualityWriteResult.Outcome.UPGRADED, current);
        }
        if (incoming.dataQuality() > current.dataQuality()) {
            return result(incoming,
                    MinuteQualityWriteResult.Outcome.REJECTED_HIGHER_QUALITY, current);
        }
        if (incoming.dataQuality() == 0) {
            // 同一分钟可能收到多个真实事件，重新聚合后的 Q0 必须能够刷新旧 Q0。
            return result(incoming, MinuteQualityWriteResult.Outcome.UPDATED_REAL, current);
        }
        if (supersedesTaskId != null
                && supersedesTaskId.equals(current.qualityTaskId())) {
            return result(incoming, MinuteQualityWriteResult.Outcome.UPGRADED, current);
        }
        return result(incoming,
                MinuteQualityWriteResult.Outcome.REJECTED_SAME_QUALITY, current);
    }

    private MinuteQualityWriteResult result(
            RawMinuteAggregate incoming,
            MinuteQualityWriteResult.Outcome outcome,
            RawMinuteAggregate current) {
        return new MinuteQualityWriteResult(
                incoming.pointId(),
                incoming.minuteStart(),
                outcome,
                current == null ? null : current.dataQuality(),
                current == null ? null : current.qualityTaskId());
    }

    private boolean isWritable(MinuteQualityWriteResult.Outcome outcome) {
        return switch (outcome) {
            case INSERTED, UPGRADED, UPDATED_REAL -> true;
            case IDEMPOTENT, REJECTED_HIGHER_QUALITY, REJECTED_SAME_QUALITY -> false;
        };
    }

    /**
     * 将已通过质量判定的多测点分钟拼成一条 TDengine 多子表写入。
     * 同一子表同一分钟时间戳重写保持幂等，不会追加重复行。
     */
    private void insertBatch(List<RawMinuteAggregate> aggregates) {
        String stable = safe(properties.getDatabase()) + "." + safe(properties.getStRawMinute());
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        for (RawMinuteAggregate aggregate : aggregates) {
            // TDengine允许一条INSERT同时写多个子表；同一pointCode+minuteStart再次写入
            // 会覆盖相同时间戳行，因此启动恢复可以安全重试而不产生重复记录。
            sql.append(qualifiedChild(aggregate.pointId())).append(" USING ").append(stable)
                .append(" (point_id,point_code,building_id,system_group_id,equip_id,equip_code,")
                .append("family_code,component_code,suffix_code,is_for_calc) TAGS (")
                .append(quote(aggregate.pointId())).append(",")
                .append(quote(aggregate.pointCode())).append(",")
                .append(quote(aggregate.buildingId())).append(",")
                .append(nullableQuote(aggregate.systemGroupId())).append(",")
                .append(nullableQuote(aggregate.equipId())).append(",")
                .append(nullableQuote(aggregate.equipCode())).append(",")
                .append(quote(aggregate.familyCode())).append(",")
                .append(nullableQuote(aggregate.componentCode())).append(",")
                .append(quote(aggregate.suffixCode())).append(",")
                .append(aggregate.isForCalc()).append(") ")
                .append("(ts,val,data_quality,avg_val,min_val,max_val,sample_count,")
                .append("first_received_time,last_received_time,finalized_at,")
                .append("quality_task_id) VALUES (")
                .append(quote(new Timestamp(aggregate.minuteStart()).toString())).append(",")
                .append(number(aggregate.averageValue())).append(",")
                .append(aggregate.dataQuality()).append(",")
                .append(number(aggregate.averageValue())).append(",")
                .append(number(aggregate.minimumValue())).append(",")
                .append(number(aggregate.maximumValue())).append(",")
                .append(aggregate.sampleCount()).append(",")
                .append(nullableTimestamp(aggregate.firstReceivedTime())).append(",")
                .append(nullableTimestamp(aggregate.lastReceivedTime())).append(",")
                .append(quote(new Timestamp(aggregate.finalizedAt()).toString())).append(",")
                .append(nullableQuote(aggregate.qualityTaskId())).append(") ");
        }
        template.execute(sql.toString().trim());
    }

    /**
     * 在访问 TDengine 前验证批次唯一键和 Q0/Q1/Q2 证据契约。
     * 真实 Q0 不绑定任务；生成 Q1/Q2 必须有任务 ID、零样本且没有接收时间。
     */
    private void validateBatch(List<RawMinuteAggregate> aggregates) {
        Set<MinuteKey> keys = new LinkedHashSet<>();
        for (RawMinuteAggregate aggregate : aggregates) {
            if (!keys.add(new MinuteKey(
                    aggregate.pointId(), aggregate.minuteStart()))) {
                throw new IllegalArgumentException("同一批次不能重复写入相同测点分钟");
            }
            if (aggregate.dataQuality() < 0 || aggregate.dataQuality() > 2) {
                throw new IllegalArgumentException("分钟数据质量只允许 0、1、2");
            }
            if (aggregate.dataQuality() == 0) {
                if (aggregate.qualityTaskId() != null) {
                    throw new IllegalArgumentException("真实 Q0 不能绑定补全任务");
                }
            } else if (aggregate.qualityTaskId() == null
                    || aggregate.qualityTaskId().isBlank()
                    || aggregate.sampleCount() != 0
                    || aggregate.firstReceivedTime() != null
                    || aggregate.lastReceivedTime() != null) {
                throw new IllegalArgumentException(
                        "生成 Q1/Q2 必须绑定任务、样本数为0且接收时间为空");
            }
        }
    }

    private void ensureExistingChildBuildingTag(RawMinuteAggregate aggregate) {
        if (!identityTagChecked.add(aggregate.pointId())) return;
        try {
            // 旧版子表在新增 building_id 标签后值为 NULL，首次写入时补齐审计维度。
            template.execute("ALTER TABLE " + qualifiedChild(aggregate.pointId())
                    + " SET TAG building_id=" + quote(aggregate.buildingId()));
        } catch (org.springframework.dao.DataAccessException exception) {
            // 新测点子表此时可能尚不存在，随后的 INSERT USING 会带标签自动创建。
            log.debug("分钟子表建筑标签将在首次创建时写入: pointCode={}", aggregate.pointCode());
        }
    }

    private String qualifiedChild(String pointId) {
        return safe(properties.getDatabase()) + "."
                + safe(properties.getStRawMinute() + "_" + pointId);
    }

    private String rawHistorySql(
            List<String> pointIds, long fromInclusive, long toExclusive) {
        // 1 分钟查询直接返回已经冻结的分钟行，不进行二次聚合。
        return """
                SELECT point_id,
                       ts AS bucket_time,
                       avg_val AS average_value,
                       min_val AS minimum_value,
                       max_val AS maximum_value,
                       sample_count,
                       data_quality
                FROM %s
                WHERE point_id IN (%s) AND %s
                ORDER BY point_id,ts
                """.formatted(
                stable(), pointIdIn(pointIds), timeRange(fromInclusive, toExclusive));
    }

    private String downsampledHistorySql(
            List<String> pointIds,
            long fromInclusive,
            long toExclusive,
            String interval) {
        // 窗口平均值按原始样本数加权，避免把样本量不同的分钟等权处理；
        // MAX(data_quality) 取窗口内最差质量等级，防止降采样掩盖异常数据。
        return """
                SELECT point_id,
                       _wstart AS bucket_time,
                       SUM(avg_val * CASE WHEN sample_count > 0 THEN sample_count ELSE 1 END)
                           / SUM(CASE WHEN sample_count > 0 THEN sample_count ELSE 1 END)
                           AS average_value,
                       MIN(min_val) AS minimum_value,
                       MAX(max_val) AS maximum_value,
                       SUM(sample_count) AS sample_count,
                       MAX(data_quality) AS data_quality
                FROM %s
                WHERE point_id IN (%s) AND %s
                PARTITION BY point_id
                INTERVAL(%s)
                ORDER BY point_id,_wstart
                """.formatted(
                stable(),
                pointIdIn(pointIds),
                timeRange(fromInclusive, toExclusive),
                interval);
    }

    private HvacMinuteQueryRow mapQueryRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        // 两类查询统一使用这些列别名，使上层无需区分原始分钟与降采样窗口。
        return new HvacMinuteQueryRow(
                resultSet.getString("point_id"),
                resultSet.getTimestamp("bucket_time").getTime(),
                resultSet.getDouble("average_value"),
                resultSet.getDouble("minimum_value"),
                resultSet.getDouble("maximum_value"),
                resultSet.getLong("sample_count"),
                resultSet.getInt("data_quality"));
    }

    private RawMinuteAggregate mapAggregate(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new RawMinuteAggregate(
                resultSet.getString("point_id"),
                resultSet.getString("point_code"),
                resultSet.getString("building_id"),
                resultSet.getString("system_group_id"),
                resultSet.getString("equip_id"),
                resultSet.getString("equip_code"),
                resultSet.getString("family_code"),
                resultSet.getString("component_code"),
                resultSet.getString("suffix_code"),
                resultSet.getInt("is_for_calc"),
                resultSet.getTimestamp("ts").getTime(),
                resultSet.getDouble("avg_val"),
                resultSet.getDouble("min_val"),
                resultSet.getDouble("max_val"),
                resultSet.getInt("sample_count"),
                resultSet.getInt("data_quality"),
                nullableTime(resultSet.getTimestamp("first_received_time")),
                nullableTime(resultSet.getTimestamp("last_received_time")),
                resultSet.getTimestamp("finalized_at").getTime(),
                resultSet.getString("quality_task_id"));
    }

    private String aggregateSelect() {
        return """
                SELECT point_id, point_code, building_id, system_group_id, equip_id,
                       equip_code, family_code, component_code, suffix_code, is_for_calc,
                       ts, avg_val, min_val, max_val, sample_count, data_quality,
                       first_received_time, last_received_time, finalized_at, quality_task_id
                FROM %s
                """.formatted(stable()).stripTrailing();
    }

    private String stable() {
        return safe(properties.getDatabase()) + "."
                + safe(properties.getStRawMinute());
    }

    private String pointIdIn(List<String> pointIds) {
        // pointId 是 SQL 值而非表名，逐个使用 quote 转义后再组成 IN 列表。
        return pointIds.stream().map(this::quote).collect(Collectors.joining(","));
    }

    private String buildingIdIn(Set<String> buildingIds) {
        return buildingIds.stream()
                .sorted()
                .map(this::quote)
                .collect(Collectors.joining(","));
    }

    private String timeRange(long fromInclusive, long toExclusive) {
        // 统一使用半开区间，连续查询相邻时间段时不会重复返回边界分钟。
        return "ts>=" + quote(new Timestamp(fromInclusive).toString())
                + " AND ts<" + quote(new Timestamp(toExclusive).toString());
    }

    private String safe(String value) {
        // 数据库名和超级表名不能使用 JDBC 参数占位符，因此只允许安全标识符字符。
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("非法TDengine标识符: " + value);
        }
        return value;
    }

    private String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private String nullableQuote(String value) {
        return value == null ? "NULL" : quote(value);
    }

    private String nullableTimestamp(Long value) {
        return value == null ? "NULL" : quote(new Timestamp(value).toString());
    }

    private Long nullableTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.getTime();
    }

    private String number(double value) {
        return String.format(Locale.ROOT, "%.12f", value);
    }

    private record MinuteKey(String pointId, long minuteStart) {
    }
}
