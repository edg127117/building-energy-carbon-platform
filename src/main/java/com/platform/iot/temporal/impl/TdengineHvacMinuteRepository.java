package com.platform.iot.temporal.impl;

import com.platform.config.TdengineProperties;
import com.platform.iot.temporal.HvacMinuteRepository;
import com.platform.iot.temporal.model.HvacMinuteQueryRow;
import com.platform.iot.temporal.model.RawMinuteAggregate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 使用 TDengine 实现 HVAC 正式分钟汇总的读写仓储。
 *
 * <p>查询 API 只读取已经冻结的 {@code st_raw_minute} 数据，不回查逐条原始事件。
 * 数据库名和超级表名作为标识符进行白名单校验；测点 ID 作为 SQL 值进行转义，
 * 防止把外部值误当成 TDengine 标识符拼接。</p>
 */
@Slf4j
@Repository
public class TdengineHvacMinuteRepository implements HvacMinuteRepository {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private final JdbcTemplate template;
    private final TdengineProperties properties;
    private final Set<String> identityTagChecked = ConcurrentHashMap.newKeySet();

    public TdengineHvacMinuteRepository(
            @Qualifier("taosJdbcTemplate") JdbcTemplate template,
            TdengineProperties properties) {
        this.template = template;
        this.properties = properties;
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
    public List<HvacMinuteQueryRow> findLatestByPointIds(List<String> pointIds) {
        if (pointIds.isEmpty()) {
            return List.of();
        }
        String stable = stable();
        // PARTITION BY 先按 point_id 分区，随后每个分区各取时间倒序第一条；
        // 因此 LIMIT 1 表示“每个测点一条”，不是整批查询全局只返回一条。
        String sql = """
                SELECT point_id,
                       ts AS bucket_time,
                       avg_val AS average_value,
                       min_val AS minimum_value,
                       max_val AS maximum_value,
                       sample_count,
                       data_quality
                FROM %s
                WHERE point_id IN (%s)
                PARTITION BY point_id
                ORDER BY ts DESC
                LIMIT 1
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
    public void saveAll(List<RawMinuteAggregate> aggregates) {
        if (aggregates.isEmpty()) {
            return;
        }
        aggregates.forEach(this::ensureExistingChildBuildingTag);
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
                .append("first_received_time,last_received_time,finalized_at) VALUES (")
                .append(quote(new Timestamp(aggregate.minuteStart()).toString())).append(",")
                .append(number(aggregate.averageValue())).append(",")
                .append(aggregate.dataQuality()).append(",")
                .append(number(aggregate.averageValue())).append(",")
                .append(number(aggregate.minimumValue())).append(",")
                .append(number(aggregate.maximumValue())).append(",")
                .append(aggregate.sampleCount()).append(",")
                .append(quote(new Timestamp(aggregate.firstReceivedTime()).toString())).append(",")
                .append(quote(new Timestamp(aggregate.lastReceivedTime()).toString())).append(",")
                .append(quote(new Timestamp(aggregate.finalizedAt()).toString())).append(") ");
        }
        template.execute(sql.toString().trim());
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
                       SUM(avg_val*sample_count)/SUM(sample_count) AS average_value,
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

    private String stable() {
        return safe(properties.getDatabase()) + "."
                + safe(properties.getStRawMinute());
    }

    private String pointIdIn(List<String> pointIds) {
        // pointId 是 SQL 值而非表名，逐个使用 quote 转义后再组成 IN 列表。
        return pointIds.stream().map(this::quote).collect(Collectors.joining(","));
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

    private String number(double value) {
        return String.format(Locale.ROOT, "%.12f", value);
    }
}
